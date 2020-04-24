/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveProxy;
import io.aeron.archive.client.ControlResponseAdapter;
import io.aeron.archive.client.RecordingEventsAdapter;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.driver.status.SystemCounterDescriptor;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import io.aeron.test.Tests;
import org.agrona.*;
import org.agrona.collections.MutableBoolean;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.aeron.archive.ArchiveTests.awaitConnectedReply;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static org.agrona.BufferUtil.allocateDirectAligned;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class ArchiveTest
{
    private static Stream<Arguments> threadingModes()
    {
        return Stream.of(
            arguments(ThreadingMode.INVOKER, ArchiveThreadingMode.SHARED),
            arguments(ThreadingMode.SHARED, ArchiveThreadingMode.SHARED),
            arguments(ThreadingMode.DEDICATED, ArchiveThreadingMode.DEDICATED));
    }

    private static final long TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_CATALOG_ENTRIES = 1024;
    private static final String CONTROL_RESPONSE_URI = CommonContext.IPC_CHANNEL;
    private static final int CONTROL_RESPONSE_STREAM_ID = 100;
    private static final String REPLAY_URI = CommonContext.IPC_CHANNEL;
    private static final int MESSAGE_COUNT = 5000;
    private static final int SYNC_LEVEL = 0;
    private static final int PUBLISH_STREAM_ID = 1;
    private static final int MAX_FRAGMENT_SIZE = 1024;
    private static final int REPLAY_STREAM_ID = 101;

    private final UnsafeBuffer buffer = new UnsafeBuffer(allocateDirectAligned(4096, FrameDescriptor.FRAME_ALIGNMENT));
    private final Random rnd = new Random();
    private final long seed = System.nanoTime();

    @RegisterExtension
    public final TestWatcher testWatcher = ArchiveTests.newWatcher(seed);

    private long controlSessionId;
    private String publishUri;
    private Aeron client;
    private Archive archive;
    private MediaDriver driver;
    private long recordingId;
    private long remaining;
    private int messageCount;
    private int[] messageLengths;
    private long totalDataLength;
    private long totalRecordingLength;
    private volatile long recorded;
    private long requestedStartPosition;
    private volatile long stopPosition = NULL_POSITION;
    private Throwable trackerError;

    private Subscription controlResponse;
    private long correlationId;
    private long startPosition;
    private int requestedInitialTermId;

    private Thread replayConsumer = null;
    private Thread progressTracker = null;

    private void before(final ThreadingMode threadingMode, final ArchiveThreadingMode archiveThreadingMode)
    {
        rnd.setSeed(seed);
        requestedInitialTermId = rnd.nextInt(1234);

        final int termLength = 1 << (16 + rnd.nextInt(10)); // 1M to 8M
        final int mtu = 1 << (10 + rnd.nextInt(3)); // 1024 to 8096
        final int requestedStartTermOffset = BitUtil.align(rnd.nextInt(termLength), FrameDescriptor.FRAME_ALIGNMENT);
        final int requestedStartTermId = requestedInitialTermId + rnd.nextInt(1000);
        final int segmentFileLength = termLength << rnd.nextInt(4);

        publishUri = new ChannelUriStringBuilder()
            .media("udp")
            .endpoint("localhost:24325")
            .termLength(termLength)
            .mtu(mtu)
            .initialTermId(requestedInitialTermId)
            .termId(requestedStartTermId)
            .termOffset(requestedStartTermOffset)
            .build();

        requestedStartPosition =
            ((requestedStartTermId - requestedInitialTermId) * (long)termLength) + requestedStartTermOffset;

        driver = MediaDriver.launch(
            new MediaDriver.Context()
                .termBufferSparseFile(true)
                .threadingMode(threadingMode)
                .sharedIdleStrategy(YieldingIdleStrategy.INSTANCE)
                .spiesSimulateConnection(true)
                .errorHandler(Tests::onError)
                .dirDeleteOnStart(true));

        archive = Archive.launch(
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .fileSyncLevel(SYNC_LEVEL)
                .mediaDriverAgentInvoker(driver.sharedAgentInvoker())
                .deleteArchiveOnStart(true)
                .archiveDir(new File(SystemUtil.tmpDirName(), "archive-test"))
                .segmentFileLength(segmentFileLength)
                .threadingMode(archiveThreadingMode)
                .idleStrategySupplier(YieldingIdleStrategy::new)
                .errorCounter(driver.context().systemCounters().get(SystemCounterDescriptor.ERRORS))
                .errorHandler(driver.context().errorHandler()));

        client = Aeron.connect();

        recorded = 0;
    }

    @AfterEach
    public void after()
    {
        if (null != replayConsumer)
        {
            replayConsumer.interrupt();
        }

        if (null != progressTracker)
        {
            progressTracker.interrupt();
        }

        CloseHelper.closeAll(client, archive, driver);

        archive.context().deleteDirectory();
        driver.context().deleteDirectory();
    }

    @ParameterizedTest
    @MethodSource("threadingModes")
    @Timeout(10)
    public void recordAndReplayExclusivePublication(
        final ThreadingMode threadingMode, final ArchiveThreadingMode archiveThreadingMode)
    {
        before(threadingMode, archiveThreadingMode);

        final String controlChannel = archive.context().localControlChannel();
        final int controlStreamId = archive.context().localControlStreamId();

        final String recordingChannel = archive.context().recordingEventsChannel();
        final int recordingStreamId = archive.context().recordingEventsStreamId();

        final Publication controlPublication = client.addPublication(controlChannel, controlStreamId);
        final Subscription recordingEvents = client.addSubscription(recordingChannel, recordingStreamId);
        Tests.await(recordingEvents::isConnected, TIMEOUT_NS);
        final ArchiveProxy archiveProxy = new ArchiveProxy(controlPublication);

        prePublicationActionsAndVerifications(archiveProxy, controlPublication, recordingEvents);

        final ExclusivePublication recordedPublication =
            client.addExclusivePublication(publishUri, PUBLISH_STREAM_ID);

        final int sessionId = recordedPublication.sessionId();
        final int termBufferLength = recordedPublication.termBufferLength();
        final int initialTermId = recordedPublication.initialTermId();
        final int maxPayloadLength = recordedPublication.maxPayloadLength();
        final long startPosition = recordedPublication.position();

        assertEquals(requestedStartPosition, startPosition);
        assertEquals(requestedInitialTermId, recordedPublication.initialTermId());
        preSendChecks(archiveProxy, recordingEvents, sessionId, termBufferLength, startPosition);

        final int messageCount = prepAndSendMessages(recordingEvents, recordedPublication);

        postPublicationValidations(
            archiveProxy,
            recordingEvents,
            termBufferLength,
            initialTermId,
            maxPayloadLength,
            messageCount);
    }

    @ParameterizedTest
    @MethodSource("threadingModes")
    @Timeout(10)
    public void replayExclusivePublicationWhileRecording(
        final ThreadingMode threadingMode, final ArchiveThreadingMode archiveThreadingMode)
    {
        before(threadingMode, archiveThreadingMode);

        final String controlChannel = archive.context().localControlChannel();
        final int controlStreamId = archive.context().localControlStreamId();

        final String recordingChannel = archive.context().recordingEventsChannel();
        final int recordingStreamId = archive.context().recordingEventsStreamId();

        final Publication controlPublication = client.addPublication(controlChannel, controlStreamId);
        final Subscription recordingEvents = client.addSubscription(recordingChannel, recordingStreamId);
        Tests.await(recordingEvents::isConnected, TIMEOUT_NS);
        final ArchiveProxy archiveProxy = new ArchiveProxy(controlPublication);

        prePublicationActionsAndVerifications(archiveProxy, controlPublication, recordingEvents);

        final ExclusivePublication recordedPublication =
            client.addExclusivePublication(publishUri, PUBLISH_STREAM_ID);

        final int sessionId = recordedPublication.sessionId();
        final int termBufferLength = recordedPublication.termBufferLength();
        final int initialTermId = recordedPublication.initialTermId();
        final int maxPayloadLength = recordedPublication.maxPayloadLength();
        final long startPosition = recordedPublication.position();

        assertEquals(requestedStartPosition, startPosition);
        assertEquals(requestedInitialTermId, recordedPublication.initialTermId());
        preSendChecks(archiveProxy, recordingEvents, sessionId, termBufferLength, startPosition);

        final int messageCount = MESSAGE_COUNT;
        final CountDownLatch streamConsumed = new CountDownLatch(2);

        prepMessagesAndListener(recordingEvents, messageCount, streamConsumed);
        replayConsumer = validateActiveRecordingReplay(
            archiveProxy,
            termBufferLength,
            initialTermId,
            maxPayloadLength,
            messageCount,
            streamConsumed);

        publishDataToBeRecorded(recordedPublication, messageCount);
        await(streamConsumed);
    }

    @ParameterizedTest
    @MethodSource("threadingModes")
    @Timeout(10)
    public void recordAndReplayRegularPublication(
        final ThreadingMode threadingMode, final ArchiveThreadingMode archiveThreadingMode)
    {
        before(threadingMode, archiveThreadingMode);

        final String controlChannel = archive.context().localControlChannel();
        final int controlStreamId = archive.context().localControlStreamId();

        final String recordingChannel = archive.context().recordingEventsChannel();
        final int recordingStreamId = archive.context().recordingEventsStreamId();

        final Publication controlPublication = client.addPublication(controlChannel, controlStreamId);
        final Subscription recordingEvents = client.addSubscription(recordingChannel, recordingStreamId);
        Tests.await(recordingEvents::isConnected, TIMEOUT_NS);
        final ArchiveProxy archiveProxy = new ArchiveProxy(controlPublication);

        prePublicationActionsAndVerifications(archiveProxy, controlPublication, recordingEvents);

        final Publication recordedPublication = client.addExclusivePublication(publishUri, PUBLISH_STREAM_ID);

        final int sessionId = recordedPublication.sessionId();
        final int termBufferLength = recordedPublication.termBufferLength();
        final int initialTermId = recordedPublication.initialTermId();
        final int maxPayloadLength = recordedPublication.maxPayloadLength();
        final long startPosition = recordedPublication.position();

        preSendChecks(archiveProxy, recordingEvents, sessionId, termBufferLength, startPosition);

        final int messageCount = prepAndSendMessages(recordingEvents, recordedPublication);

        postPublicationValidations(
            archiveProxy,
            recordingEvents,
            termBufferLength,
            initialTermId,
            maxPayloadLength,
            messageCount);
    }

    private void preSendChecks(
        final ArchiveProxy archiveProxy,
        final Subscription recordingEvents,
        final int sessionId,
        final int termBufferLength,
        final long startPosition)
    {
        final MutableBoolean recordingStarted = new MutableBoolean();
        final RecordingEventsAdapter recordingEventsAdapter = new RecordingEventsAdapter(
            new FailRecordingEventsListener()
            {
                public void onStart(
                    final long recordingId0,
                    final long startPosition0,
                    final int sessionId0,
                    final int streamId0,
                    final String channel,
                    final String sourceIdentity)
                {
                    recordingId = recordingId0;
                    assertEquals(PUBLISH_STREAM_ID, streamId0);
                    assertEquals(sessionId, sessionId0);
                    assertEquals(startPosition, startPosition0);
                    recordingStarted.set(true);
                }
            },
            recordingEvents,
            1);

        while (!recordingStarted.get())
        {
            if (recordingEventsAdapter.poll() == 0)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }
        }

        verifyDescriptorListOngoingArchive(archiveProxy, termBufferLength);
    }

    private void postPublicationValidations(
        final ArchiveProxy archiveProxy,
        final Subscription recordingEvents,
        final int termBufferLength,
        final int initialTermId,
        final int maxPayloadLength,
        final int messageCount)
    {
        verifyDescriptorListOngoingArchive(archiveProxy, termBufferLength);
        assertNull(trackerError);

        final long requestStopCorrelationId = correlationId++;
        if (!archiveProxy.stopRecording(publishUri, PUBLISH_STREAM_ID, requestStopCorrelationId, controlSessionId))
        {
            throw new IllegalStateException("Failed to stop recording");
        }

        ArchiveTests.awaitOk(controlResponse, requestStopCorrelationId);

        final MutableBoolean recordingStopped = new MutableBoolean();
        final RecordingEventsAdapter recordingEventsAdapter = new RecordingEventsAdapter(
            new FailRecordingEventsListener()
            {
                public void onStop(final long id, final long startPosition, final long stopPosition)
                {
                    assertEquals(recordingId, id);
                    recordingStopped.set(true);
                }
            },
            recordingEvents,
            1);

        while (!recordingStopped.get())
        {
            if (recordingEventsAdapter.poll() == 0)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }
        }

        verifyDescriptorListOngoingArchive(archiveProxy, termBufferLength);
        validateArchiveFile(messageCount, recordingId);
        validateReplay(archiveProxy, messageCount, initialTermId, maxPayloadLength, termBufferLength);
    }

    private void prePublicationActionsAndVerifications(
        final ArchiveProxy archiveProxy,
        final Publication controlPublication,
        final Subscription recordingEvents)
    {
        Tests.await(controlPublication::isConnected, TIMEOUT_NS);
        Tests.await(recordingEvents::isConnected, TIMEOUT_NS);

        controlResponse = client.addSubscription(CONTROL_RESPONSE_URI, CONTROL_RESPONSE_STREAM_ID);
        final long connectCorrelationId = correlationId++;
        assertTrue(archiveProxy.connect(CONTROL_RESPONSE_URI, CONTROL_RESPONSE_STREAM_ID, connectCorrelationId));

        Tests.await(controlResponse::isConnected, TIMEOUT_NS);
        awaitConnectedReply(controlResponse, connectCorrelationId, (sessionId) -> controlSessionId = sessionId);
        verifyEmptyDescriptorList(archiveProxy);

        final long startRecordingCorrelationId = correlationId++;
        if (!archiveProxy.startRecording(
            publishUri,
            PUBLISH_STREAM_ID,
            SourceLocation.LOCAL,
            startRecordingCorrelationId,
            controlSessionId))
        {
            throw new IllegalStateException("Failed to start recording");
        }

        ArchiveTests.awaitOk(controlResponse, startRecordingCorrelationId);
    }

    private void verifyEmptyDescriptorList(final ArchiveProxy client)
    {
        final long requestRecordingsCorrelationId = correlationId++;
        client.listRecordings(0, 100, requestRecordingsCorrelationId, controlSessionId);
        ArchiveTests.awaitResponse(controlResponse, requestRecordingsCorrelationId);
    }

    private void verifyDescriptorListOngoingArchive(
        final ArchiveProxy archiveProxy, final int publicationTermBufferLength)
    {
        final long requestRecordingsCorrelationId = correlationId++;
        archiveProxy.listRecording(recordingId, requestRecordingsCorrelationId, controlSessionId);
        final MutableBoolean isDone = new MutableBoolean();

        final ControlResponseAdapter controlResponseAdapter = new ControlResponseAdapter(
            new FailControlResponseListener()
            {
                public void onRecordingDescriptor(
                    final long controlSessionId,
                    final long correlationId,
                    final long recordingId,
                    final long startTimestamp,
                    final long stopTimestamp,
                    final long startPosition,
                    final long stopPosition,
                    final int initialTermId,
                    final int segmentFileLength,
                    final int termBufferLength,
                    final int mtuLength,
                    final int sessionId,
                    final int streamId,
                    final String strippedChannel,
                    final String originalChannel,
                    final String sourceIdentity)
                {
                    assertEquals(requestRecordingsCorrelationId, correlationId);
                    assertEquals(ArchiveTest.this.recordingId, recordingId);
                    assertEquals(publicationTermBufferLength, termBufferLength);
                    assertEquals(PUBLISH_STREAM_ID, streamId);
                    assertEquals(publishUri, originalChannel);

                    isDone.set(true);
                }
            },
            controlResponse,
            1
        );

        while (!isDone.get())
        {
            if (controlResponseAdapter.poll() == 0)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }
        }
    }

    private int prepAndSendMessages(final Subscription recordingEvents, final Publication publication)
    {
        final int messageCount = MESSAGE_COUNT;
        final CountDownLatch waitForData = new CountDownLatch(1);
        prepMessagesAndListener(recordingEvents, messageCount, waitForData);
        publishDataToBeRecorded(publication, messageCount);
        await(waitForData);

        return messageCount;
    }

    private int prepAndSendMessages(final Subscription recordingEvents, final ExclusivePublication publication)
    {
        final int messageCount = MESSAGE_COUNT;
        final CountDownLatch waitForData = new CountDownLatch(1);
        prepMessagesAndListener(recordingEvents, messageCount, waitForData);
        publishDataToBeRecorded(publication, messageCount);
        await(waitForData);

        return messageCount;
    }

    private void await(final CountDownLatch waitForData)
    {
        try
        {
            waitForData.await();
        }
        catch (final InterruptedException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    private void prepMessagesAndListener(
        final Subscription recordingEvents, final int messageCount, final CountDownLatch streamConsumedLatch)
    {
        messageLengths = new int[messageCount];
        for (int i = 0; i < messageCount; i++)
        {
            final int messageLength = 64 + rnd.nextInt(MAX_FRAGMENT_SIZE - 64) - HEADER_LENGTH;
            messageLengths[i] = messageLength + HEADER_LENGTH;
            totalDataLength += BitUtil.align(messageLengths[i], FrameDescriptor.FRAME_ALIGNMENT);
        }

        progressTracker = trackRecordingProgress(recordingEvents, streamConsumedLatch);
    }

    private void publishDataToBeRecorded(final Publication publication, final int messageCount)
    {
        startPosition = publication.position();

        buffer.setMemory(0, 1024, (byte)'z');
        buffer.putStringAscii(32, "TEST");

        for (int i = 0; i < messageCount; i++)
        {
            final int dataLength = messageLengths[i] - HEADER_LENGTH;
            buffer.putInt(0, i);

            while (true)
            {
                final long result = publication.offer(buffer, 0, dataLength);
                if (result > 0)
                {
                    break;
                }

                if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED)
                {
                    throw new IllegalStateException("Publication not connected: result=" + result);
                }

                Thread.yield();
                Tests.checkInterruptStatus();
            }
        }

        final long position = publication.position();
        totalRecordingLength = position - startPosition;
        stopPosition = position;
    }

    private void validateReplay(
        final ArchiveProxy archiveProxy,
        final int messageCount,
        final int initialTermId,
        final int maxPayloadLength,
        final int termBufferLength)
    {
        try (Subscription replay = client.addSubscription(REPLAY_URI, REPLAY_STREAM_ID))
        {
            final long replayCorrelationId = correlationId++;

            if (!archiveProxy.replay(
                recordingId,
                startPosition,
                totalRecordingLength,
                REPLAY_URI,
                REPLAY_STREAM_ID,
                replayCorrelationId,
                controlSessionId))
            {
                throw new IllegalStateException("Failed to replay");
            }

            ArchiveTests.awaitOk(controlResponse, replayCorrelationId);
            Tests.await(replay::isConnected, TIMEOUT_NS);

            final Image image = replay.images().get(0);
            assertEquals(initialTermId, image.initialTermId());
            assertEquals(maxPayloadLength + HEADER_LENGTH, image.mtuLength());
            assertEquals(termBufferLength, image.termBufferLength());
            assertEquals(startPosition, image.position());

            this.messageCount = 0;
            remaining = totalDataLength;

            while (remaining > 0)
            {
                final int fragments = replay.poll(this::validateFragment, 10);
                if (0 == fragments)
                {
                    Thread.yield();
                    Tests.checkInterruptStatus();
                }
            }

            assertEquals(messageCount, this.messageCount);
            assertEquals(0L, remaining);
        }
    }

    private void validateArchiveFile(final int messageCount, final long recordingId)
    {
        final File archiveDir = archive.context().archiveDir();
        final Catalog catalog = archive.context().catalog();
        remaining = totalDataLength;
        this.messageCount = 0;

        while (catalog.stopPosition(recordingId) != stopPosition)
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        try (RecordingReader recordingReader = new RecordingReader(
            catalog.recordingSummary(recordingId, new RecordingSummary()),
            archiveDir,
            NULL_POSITION,
            AeronArchive.NULL_LENGTH))
        {
            while (!recordingReader.isDone())
            {
                recordingReader.poll(this::validateRecordingFragment, messageCount);
                Tests.checkInterruptStatus();
            }
        }

        assertEquals(0L, remaining);
        assertEquals(messageCount, this.messageCount);
    }

    private void validateRecordingFragment(
        final UnsafeBuffer buffer,
        final int offset,
        final int length,
        @SuppressWarnings("unused") final int frameType,
        @SuppressWarnings("unused") final byte flags,
        @SuppressWarnings("unused") final long reservedValue)
    {
        if (!FrameDescriptor.isPaddingFrame(buffer, offset - HEADER_LENGTH))
        {
            final int expectedLength = messageLengths[messageCount] - HEADER_LENGTH;
            if (length != expectedLength)
            {
                fail("Message length=" + length + " expected=" + expectedLength + " messageCount=" + messageCount);
            }

            assertEquals(messageCount, buffer.getInt(offset));
            assertEquals((byte)'z', buffer.getByte(offset + 4));

            remaining -= BitUtil.align(messageLengths[messageCount], FrameDescriptor.FRAME_ALIGNMENT);
            messageCount++;
        }
    }

    @SuppressWarnings("unused")
    private void validateFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        assertEquals(messageLengths[messageCount] - HEADER_LENGTH, length);
        assertEquals(messageCount, buffer.getInt(offset));
        assertEquals((byte)'z', buffer.getByte(offset + 4));

        remaining -= BitUtil.align(messageLengths[messageCount], FrameDescriptor.FRAME_ALIGNMENT);
        messageCount++;
    }

    private Thread trackRecordingProgress(final Subscription recordingEvents, final CountDownLatch streamConsumed)
    {
        final RecordingEventsAdapter recordingEventsAdapter = new RecordingEventsAdapter(
            new FailRecordingEventsListener()
            {
                public void onProgress(final long recordingId0, final long startPosition, final long position)
                {
                    assertEquals(recordingId, recordingId0);
                    recorded = position - startPosition;
                }
            },
            recordingEvents,
            1);

        final Thread thread = new Thread(
            () ->
            {
                try
                {
                    recorded = 0;

                    while (stopPosition == NULL_POSITION || recorded < totalRecordingLength)
                    {
                        if (recordingEventsAdapter.poll() == 0)
                        {
                            Tests.sleep(1);
                            Tests.checkInterruptStatus();
                        }
                    }
                }
                catch (final Throwable throwable)
                {
                    throwable.printStackTrace();
                    trackerError = throwable;
                }

                streamConsumed.countDown();
            });

        thread.setDaemon(true);
        thread.setName("recording-progress-tracker");
        thread.start();

        return thread;
    }

    private Thread validateActiveRecordingReplay(
        final ArchiveProxy archiveProxy,
        final int termBufferLength,
        final int initialTermId,
        final int maxPayloadLength,
        final int messageCount,
        final CountDownLatch waitForData)
    {
        final Thread thread = new Thread(
            () ->
            {
                while (0 == recorded)
                {
                    Tests.sleep(1);
                }

                try (Subscription replay = client.addSubscription(REPLAY_URI, REPLAY_STREAM_ID))
                {
                    final long replayCorrelationId = correlationId++;

                    if (!archiveProxy.replay(
                        recordingId,
                        startPosition,
                        Long.MAX_VALUE,
                        REPLAY_URI,
                        REPLAY_STREAM_ID,
                        replayCorrelationId,
                        controlSessionId))
                    {
                        throw new IllegalStateException("failed to start replay");
                    }

                    ArchiveTests.awaitOk(controlResponse, replayCorrelationId);
                    Tests.await(replay::isConnected, TIMEOUT_NS);

                    final Image image = replay.images().get(0);
                    assertEquals(initialTermId, image.initialTermId());
                    assertEquals(maxPayloadLength + HEADER_LENGTH, image.mtuLength());
                    assertEquals(termBufferLength, image.termBufferLength());
                    assertEquals(startPosition, image.position());

                    this.messageCount = 0;
                    remaining = totalDataLength;

                    final FragmentHandler fragmentHandler = this::validateFragment;
                    while (this.messageCount < messageCount)
                    {
                        final int fragments = replay.poll(fragmentHandler, 10);
                        if (0 == fragments)
                        {
                            Thread.yield();
                            Tests.checkInterruptStatus();
                        }
                    }
                }

                waitForData.countDown();
            });

        thread.setName("replay-consumer");
        thread.setDaemon(true);
        thread.start();

        return thread;
    }
}
