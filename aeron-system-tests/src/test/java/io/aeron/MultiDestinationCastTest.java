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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.test.MediaDriverTestWatcher;
import io.aeron.test.TestMediaDriver;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.SystemUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MultiDestinationCastTest
{
    private static final String PUB_MDC_DYNAMIC_URI = "aeron:udp?control=localhost:24325";
    private static final String SUB1_MDC_DYNAMIC_URI = "aeron:udp?control=localhost:24325|group=true";
    private static final String SUB2_MDC_DYNAMIC_URI = "aeron:udp?control=localhost:24325|group=true";
    private static final String SUB3_MDC_DYNAMIC_URI = CommonContext.SPY_PREFIX + PUB_MDC_DYNAMIC_URI;

    private static final String PUB_MDC_MANUAL_URI = "aeron:udp?control-mode=manual|tags=3,4";
    private static final String SUB1_MDC_MANUAL_URI = "aeron:udp?endpoint=localhost:24326|group=true";
    private static final String SUB2_MDC_MANUAL_URI = "aeron:udp?endpoint=localhost:24327|group=true";
    private static final String SUB3_MDC_MANUAL_URI = CommonContext.SPY_PREFIX + PUB_MDC_MANUAL_URI;

    private static final int STREAM_ID = 1001;

    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int MESSAGES_PER_TERM = 64;
    private static final int MESSAGE_LENGTH =
        (TERM_BUFFER_LENGTH / MESSAGES_PER_TERM) - DataHeaderFlyweight.HEADER_LENGTH;
    private static final String ROOT_DIR =
        SystemUtil.tmpDirName() + "aeron-system-tests-" + UUID.randomUUID().toString() + File.separator;

    private final MediaDriver.Context driverBContext = new MediaDriver.Context();

    private Aeron clientA;
    private Aeron clientB;
    private TestMediaDriver driverA;
    private TestMediaDriver driverB;
    private Publication publication;
    private Subscription subscriptionA;
    private Subscription subscriptionB;
    private Subscription subscriptionC;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final FragmentHandler fragmentHandlerA = mock(FragmentHandler.class, "fragmentHandlerA");
    private final FragmentHandler fragmentHandlerB = mock(FragmentHandler.class, "fragmentHandlerB");
    private final FragmentHandler fragmentHandlerC = mock(FragmentHandler.class, "fragmentHandlerC");

    @RegisterExtension
    public MediaDriverTestWatcher testWatcher = new MediaDriverTestWatcher();

    private void launch()
    {
        final String baseDirA = ROOT_DIR + "A";
        final String baseDirB = ROOT_DIR + "B";

        buffer.putInt(0, 1);

        final MediaDriver.Context driverAContext = new MediaDriver.Context()
            .errorHandler(Throwable::printStackTrace)
            .publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .aeronDirectoryName(baseDirA)
            .threadingMode(ThreadingMode.SHARED);

        driverBContext.publicationTermBufferLength(TERM_BUFFER_LENGTH)
            .errorHandler(Throwable::printStackTrace)
            .aeronDirectoryName(baseDirB)
            .threadingMode(ThreadingMode.SHARED);

        driverA = TestMediaDriver.launch(driverAContext, testWatcher);
        driverB = TestMediaDriver.launch(driverBContext, testWatcher);
        clientA = Aeron.connect(new Aeron.Context().aeronDirectoryName(driverAContext.aeronDirectoryName()));
        clientB = Aeron.connect(new Aeron.Context().aeronDirectoryName(driverBContext.aeronDirectoryName()));
    }

    @AfterEach
    public void closeEverything()
    {
        CloseHelper.closeAll(clientB, clientA, driverB, driverA);
        IoUtil.delete(new File(ROOT_DIR), true);
    }

    @Test
    @Timeout(10)
    public void shouldSpinUpAndShutdownWithDynamic()
    {
        launch();

        publication = clientA.addPublication(PUB_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionA = clientA.addSubscription(SUB1_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(SUB2_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionC = clientA.addSubscription(SUB3_MDC_DYNAMIC_URI, STREAM_ID);

        while (subscriptionA.hasNoImages() || subscriptionB.hasNoImages() || subscriptionC.hasNoImages())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }
    }

    @Test
    @Timeout(10)
    public void shouldSpinUpAndShutdownWithManual()
    {
        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_MANUAL_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(SUB2_MDC_MANUAL_URI, STREAM_ID);
        subscriptionC = clientA.addSubscription(SUB3_MDC_MANUAL_URI, STREAM_ID);

        publication = clientA.addPublication(PUB_MDC_MANUAL_URI, STREAM_ID);
        publication.addDestination(SUB1_MDC_MANUAL_URI);
        final long correlationId = publication.asyncAddDestination(SUB2_MDC_MANUAL_URI);

        while (subscriptionA.hasNoImages() || subscriptionB.hasNoImages() || subscriptionC.hasNoImages())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        assertFalse(clientA.isCommandActive(correlationId));
    }

    @Test
    @Timeout(20)
    public void shouldSendToTwoPortsWithDynamic()
    {
        final int numMessagesToSend = MESSAGES_PER_TERM * 3;

        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(SUB2_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionC = clientA.addSubscription(SUB3_MDC_DYNAMIC_URI, STREAM_ID);
        publication = clientA.addPublication(PUB_MDC_DYNAMIC_URI, STREAM_ID);

        while (subscriptionA.hasNoImages() || subscriptionB.hasNoImages() || subscriptionC.hasNoImages())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            pollForFragment(subscriptionA, fragmentHandlerA);
            pollForFragment(subscriptionB, fragmentHandlerB);
            pollForFragment(subscriptionC, fragmentHandlerC);
        }

        verifyFragments(fragmentHandlerA, numMessagesToSend);
        verifyFragments(fragmentHandlerB, numMessagesToSend);
        verifyFragments(fragmentHandlerC, numMessagesToSend);
    }

    @Test
    @Timeout(20)
    public void shouldSendToTwoPortsWithDynamicSingleDriver()
    {
        final int numMessagesToSend = MESSAGES_PER_TERM * 3;

        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionB = clientA.addSubscription(SUB2_MDC_DYNAMIC_URI, STREAM_ID);
        subscriptionC = clientA.addSubscription(SUB3_MDC_DYNAMIC_URI, STREAM_ID);
        publication = clientA.addPublication(PUB_MDC_DYNAMIC_URI, STREAM_ID);

        while (!subscriptionA.isConnected() || !subscriptionB.isConnected() || !subscriptionC.isConnected())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            pollForFragment(subscriptionA, fragmentHandlerA);
            pollForFragment(subscriptionB, fragmentHandlerB);
            pollForFragment(subscriptionC, fragmentHandlerC);
        }

        verifyFragments(fragmentHandlerA, numMessagesToSend);
        verifyFragments(fragmentHandlerB, numMessagesToSend);
        verifyFragments(fragmentHandlerC, numMessagesToSend);
    }

    @Test
    @Timeout(10)
    public void shouldSendToTwoPortsWithManualSingleDriver()
    {
        final int numMessagesToSend = MESSAGES_PER_TERM * 3;

        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_MANUAL_URI, STREAM_ID);
        subscriptionB = clientA.addSubscription(SUB2_MDC_MANUAL_URI, STREAM_ID);

        publication = clientA.addPublication(PUB_MDC_MANUAL_URI, STREAM_ID);
        publication.addDestination(SUB1_MDC_MANUAL_URI);
        publication.addDestination(SUB2_MDC_MANUAL_URI);

        while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            pollForFragment(subscriptionA, fragmentHandlerA);
            pollForFragment(subscriptionB, fragmentHandlerB);
        }

        verifyFragments(fragmentHandlerA, numMessagesToSend);
        verifyFragments(fragmentHandlerB, numMessagesToSend);
    }

    @Test
    @Timeout(10)
    public void shouldManuallyRemovePortDuringActiveStream() throws InterruptedException
    {
        final int numMessagesToSend = MESSAGES_PER_TERM * 3;
        final int numMessageForSub2 = 10;
        final CountDownLatch unavailableImage = new CountDownLatch(1);

        driverBContext.imageLivenessTimeoutNs(TimeUnit.MILLISECONDS.toNanos(500));

        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_MANUAL_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(
            SUB2_MDC_MANUAL_URI, STREAM_ID, null, (image) -> unavailableImage.countDown());

        publication = clientA.addPublication(PUB_MDC_MANUAL_URI, STREAM_ID);
        publication.addDestination(SUB1_MDC_MANUAL_URI);
        publication.addDestination(SUB2_MDC_MANUAL_URI);

        while (!subscriptionA.isConnected() || !subscriptionB.isConnected())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            pollForFragment(subscriptionA, fragmentHandlerA);

            if (i < numMessageForSub2)
            {
                pollForFragment(subscriptionB, fragmentHandlerB);
            }
            else
            {
                subscriptionB.poll(fragmentHandlerB, 10);
                Thread.yield();
            }

            if (i == numMessageForSub2 - 1)
            {
                publication.removeDestination(SUB2_MDC_MANUAL_URI);
            }
        }

        unavailableImage.await();

        verifyFragments(fragmentHandlerA, numMessagesToSend);
        verifyFragments(fragmentHandlerB, numMessageForSub2);
    }

    @Test
    @Timeout(10)
    public void shouldManuallyAddPortDuringActiveStream() throws InterruptedException
    {
        final int numMessagesToSend = MESSAGES_PER_TERM * 3;
        final int numMessageForSub2 = 10;
        final CountingFragmentHandler fragmentHandlerA = new CountingFragmentHandler(
            "fragmentHandlerA", numMessagesToSend);
        final CountingFragmentHandler fragmentHandlerB = new CountingFragmentHandler(
            "fragmentHandlerB", numMessageForSub2);

        final CountDownLatch availableImage = new CountDownLatch(1);

        launch();

        subscriptionA = clientA.addSubscription(SUB1_MDC_MANUAL_URI, STREAM_ID);
        subscriptionB = clientB.addSubscription(
            SUB2_MDC_MANUAL_URI, STREAM_ID, (image) -> availableImage.countDown(), null);

        publication = clientA.addPublication(PUB_MDC_MANUAL_URI, STREAM_ID);
        publication.addDestination(SUB1_MDC_MANUAL_URI);

        while (!subscriptionA.isConnected())
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        for (int i = 0; i < numMessagesToSend; i++)
        {
            while (publication.offer(buffer, 0, buffer.capacity()) < 0L)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            subscriptionA.poll(fragmentHandlerA, 10);

            if (i > (numMessagesToSend - numMessageForSub2))
            {
                subscriptionB.poll(fragmentHandlerB, 10);
            }

            if (i == (numMessagesToSend - numMessageForSub2 - 1))
            {
                // If we add B before A has reached i number of messages
                // then B will receive more than the expected `numMessageForSub2`.
                final int published = i + 1;
                final Supplier<String> message =
                    () -> "Handler: " + fragmentHandlerA.toString() + ", published: " + published;
                while (!fragmentHandlerA.hasReached(published))
                {
                    if (subscriptionA.poll(fragmentHandlerA, 10) <= 0)
                    {
                        Tests.yieldingWait(message);
                    }
                }

                publication.addDestination(SUB2_MDC_MANUAL_URI);
                availableImage.await();
            }
        }

        while (!fragmentHandlerA.isComplete() || !fragmentHandlerB.isComplete())
        {
            if (!fragmentHandlerA.isComplete() && subscriptionA.poll(fragmentHandlerA, 10) <= 0)
            {
                Tests.yieldingWait(fragmentHandlerA::toString);
            }

            if (!fragmentHandlerB.isComplete() && subscriptionB.poll(fragmentHandlerB, 10) <= 0)
            {
                Tests.yieldingWait(fragmentHandlerB::toString);
            }
        }
    }

    private static int pollForFragment(final Subscription subscription, final FragmentHandler handler)
    {
        return Tests.pollForFragments(subscription, handler, 1, TimeUnit.MILLISECONDS.toNanos(500));
    }

    private void verifyFragments(final FragmentHandler fragmentHandler, final int numMessagesToSend)
    {
        verify(fragmentHandler, times(numMessagesToSend)).onFragment(
            any(DirectBuffer.class),
            anyInt(),
            eq(MESSAGE_LENGTH),
            any(Header.class));
    }

    private static final class CountingFragmentHandler implements FragmentHandler
    {
        private final String name;
        private final int expected;
        private int received = 0;

        private CountingFragmentHandler(final String name, final int expected)
        {
            this.name = name;
            this.expected = expected;
        }

        public boolean isComplete()
        {
            return expected == received;
        }

        public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            received++;
        }

        public String toString()
        {
            return "CountingFragmentHandler{" +
                "name='" + name + '\'' +
                ", expected=" + expected +
                ", received=" + received +
                '}';
        }

        public boolean hasReached(final int i)
        {
            return i == received;
        }
    }
}
