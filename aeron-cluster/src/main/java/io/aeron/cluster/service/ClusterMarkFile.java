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
package io.aeron.cluster.service;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.mark.ClusterComponentType;
import io.aeron.cluster.codecs.mark.MarkFileHeaderDecoder;
import io.aeron.cluster.codecs.mark.MarkFileHeaderEncoder;
import io.aeron.cluster.codecs.mark.VarAsciiEncodingEncoder;
import org.agrona.*;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.function.Consumer;

import static io.aeron.Aeron.NULL_VALUE;

/**
 * Used to indicate if a cluster service is running and what configuration it is using. Errors encountered by
 * the service are recorded within this file by a {@link org.agrona.concurrent.errors.DistinctErrorLog}.
 */
public class ClusterMarkFile implements AutoCloseable
{
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;
    public static final int PATCH_VERSION = 0;
    public static final int SEMANTIC_VERSION = SemanticVersion.compose(MAJOR_VERSION, MINOR_VERSION, PATCH_VERSION);

    public static final int HEADER_LENGTH = 8 * 1024;
    public static final int VERSION_FAILED = -1;

    public static final String FILE_EXTENSION = ".dat";
    public static final String FILENAME = "cluster-mark" + FILE_EXTENSION;
    public static final String SERVICE_FILENAME_PREFIX = "cluster-mark-service-";

    private final MarkFileHeaderDecoder headerDecoder = new MarkFileHeaderDecoder();
    private final MarkFileHeaderEncoder headerEncoder = new MarkFileHeaderEncoder();
    private final MarkFile markFile;
    private final UnsafeBuffer buffer;
    private final UnsafeBuffer errorBuffer;

    public ClusterMarkFile(
        final File file,
        final ClusterComponentType type,
        final int errorBufferLength,
        final EpochClock epochClock,
        final long timeoutMs)
    {
        final boolean markFileExists = file.exists();

        markFile = new MarkFile(
            file,
            markFileExists,
            MarkFileHeaderDecoder.versionEncodingOffset(),
            MarkFileHeaderDecoder.activityTimestampEncodingOffset(),
            HEADER_LENGTH + errorBufferLength,
            timeoutMs,
            epochClock,
            (version) ->
            {
                if (VERSION_FAILED == version && markFileExists)
                {
                    System.err.println("mark file version -1 indicates error on previous startup.");
                }
                else if (SemanticVersion.major(version) != MAJOR_VERSION)
                {
                    throw new IllegalArgumentException("mark file major version " + SemanticVersion.major(version) +
                        " does not match software:" + MAJOR_VERSION);
                }
            },
            null);

        buffer = markFile.buffer();
        errorBuffer = new UnsafeBuffer(this.buffer, HEADER_LENGTH, errorBufferLength);

        headerEncoder.wrap(buffer, 0);
        headerDecoder.wrap(buffer, 0, MarkFileHeaderDecoder.BLOCK_LENGTH, MarkFileHeaderDecoder.SCHEMA_VERSION);

        if (markFileExists)
        {
            final UnsafeBuffer existingErrorBuffer = new UnsafeBuffer(
                buffer, headerDecoder.headerLength(), headerDecoder.errorBufferLength());

            saveExistingErrors(file, existingErrorBuffer, System.err);
            existingErrorBuffer.setMemory(0, headerDecoder.errorBufferLength(), (byte)0);
        }
        else
        {
            headerEncoder.candidateTermId(NULL_VALUE);
        }

        final ClusterComponentType existingType = headerDecoder.componentType();

        if (existingType != ClusterComponentType.NULL && existingType != type)
        {
            if (existingType != ClusterComponentType.BACKUP || ClusterComponentType.CONSENSUS_MODULE != type)
            {
                throw new IllegalStateException(
                    "existing Mark file type " + existingType + " not same as required type " + type);
            }
        }

        headerEncoder.componentType(type);
        headerEncoder.headerLength(HEADER_LENGTH);
        headerEncoder.errorBufferLength(errorBufferLength);
        headerEncoder.pid(SystemUtil.getPid());
        headerEncoder.startTimestamp(epochClock.time());
    }

    public ClusterMarkFile(
        final File directory,
        final String filename,
        final EpochClock epochClock,
        final long timeoutMs,
        final Consumer<String> logger)
    {
        markFile = new MarkFile(
            directory,
            filename,
            MarkFileHeaderDecoder.versionEncodingOffset(),
            MarkFileHeaderDecoder.activityTimestampEncodingOffset(),
            timeoutMs,
            epochClock,
            (version) ->
            {
                if (SemanticVersion.major(version) != MAJOR_VERSION)
                {
                    throw new IllegalArgumentException("mark file major version " + SemanticVersion.major(version) +
                        " does not match software:" + MAJOR_VERSION);
                }
            },
            logger);

        buffer = markFile.buffer();
        headerDecoder.wrap(buffer, 0, MarkFileHeaderDecoder.BLOCK_LENGTH, MarkFileHeaderDecoder.SCHEMA_VERSION);
        errorBuffer = new UnsafeBuffer(buffer, headerDecoder.headerLength(), headerDecoder.errorBufferLength());
    }

    public void close()
    {
        CloseHelper.close(markFile);
    }

    /**
     * Get the current value of a candidate term id if a vote is placed in an election.
     *
     * @return the current candidate term id within an election after voting or {@link Aeron#NULL_VALUE} if
     * no voting phase of an election is currently active.
     */
    public long candidateTermId()
    {
        return buffer.getLongVolatile(MarkFileHeaderDecoder.candidateTermIdEncodingOffset());
    }

    /**
     * Record the fact that a node has voted in a current election for a candidate so it can survive a restart.
     *
     * @param candidateTermId to record that a vote has taken place.
     * @param fileSyncLevel   as defined by cluster file sync level.
     */
    public void candidateTermId(final long candidateTermId, final int fileSyncLevel)
    {
        buffer.putLongVolatile(MarkFileHeaderEncoder.candidateTermIdEncodingOffset(), candidateTermId);
        if (fileSyncLevel > 0)
        {
            markFile.mappedByteBuffer().force();
        }
    }

    public int memberId()
    {
        return buffer.getIntVolatile(MarkFileHeaderDecoder.memberIdEncodingOffset());
    }

    public void memberId(final int memberId)
    {
        buffer.putIntVolatile(MarkFileHeaderEncoder.memberIdEncodingOffset(), memberId);
    }

    public void signalReady()
    {
        markFile.signalReady(SEMANTIC_VERSION);
    }

    public void signalFailedStart()
    {
        markFile.signalReady(VERSION_FAILED);
    }

    public void updateActivityTimestamp(final long nowMs)
    {
        markFile.timestampOrdered(nowMs);
    }

    public long activityTimestampVolatile()
    {
        return markFile.timestampVolatile();
    }

    public MarkFileHeaderEncoder encoder()
    {
        return headerEncoder;
    }

    public MarkFileHeaderDecoder decoder()
    {
        return headerDecoder;
    }

    public UnsafeBuffer buffer()
    {
        return buffer;
    }

    public UnsafeBuffer errorBuffer()
    {
        return errorBuffer;
    }

    public static void saveExistingErrors(final File markFile, final AtomicBuffer errorBuffer, final PrintStream logger)
    {
        try
        {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final int observations = CommonContext.printErrorLog(errorBuffer, new PrintStream(baos, false, "US-ASCII"));
            if (observations > 0)
            {
                final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSSZ");
                final String errorLogFilename =
                    markFile.getParent() + '-' + dateFormat.format(new Date()) + "-error.log";

                if (null != logger)
                {
                    logger.println("WARNING: existing errors saved to: " + errorLogFilename);
                }

                try (FileOutputStream out = new FileOutputStream(errorLogFilename))
                {
                    baos.writeTo(out);
                }
            }
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public static void checkHeaderLength(
        final String aeronDirectory,
        final String archiveChannel,
        final String serviceControlChannel,
        final String ingressChannel,
        final String serviceName,
        final String authenticator)
    {
        Objects.requireNonNull(aeronDirectory);
        Objects.requireNonNull(archiveChannel);
        Objects.requireNonNull(serviceControlChannel);

        final int lengthRequired =
            MarkFileHeaderEncoder.BLOCK_LENGTH +
            (6 * VarAsciiEncodingEncoder.lengthEncodingLength()) +
            aeronDirectory.length() +
            archiveChannel.length() +
            serviceControlChannel.length() +
            (null == ingressChannel ? 0 : ingressChannel.length()) +
            (null == serviceName ? 0 : serviceName.length()) +
            (null == authenticator ? 0 : authenticator.length());

        if (lengthRequired > HEADER_LENGTH)
        {
            throw new ClusterException(
                "MarkFile length required " + lengthRequired + " greater than " + HEADER_LENGTH);
        }
    }

    public static String markFilenameForService(final int serviceId)
    {
        return SERVICE_FILENAME_PREFIX + serviceId + FILE_EXTENSION;
    }

    public ClusterNodeControlProperties loadControlProperties()
    {
        final MarkFileHeaderDecoder headerDecoder = new MarkFileHeaderDecoder();
        headerDecoder.wrap(
            this.headerDecoder.buffer(),
            this.headerDecoder.initialOffset(),
            MarkFileHeaderDecoder.BLOCK_LENGTH,
            MarkFileHeaderDecoder.SCHEMA_VERSION);

        final int toServiceStreamId = headerDecoder.serviceStreamId();
        final int toConsensusModuleStreamId = headerDecoder.consensusModuleStreamId();
        final String aeronDirectoryName = headerDecoder.aeronDirectory();
        final String archiveChannel = headerDecoder.archiveChannel();
        final String serviceControlChannel = headerDecoder.serviceControlChannel();

        return new ClusterNodeControlProperties(
            toServiceStreamId, toConsensusModuleStreamId, aeronDirectoryName, archiveChannel, serviceControlChannel);
    }
}
