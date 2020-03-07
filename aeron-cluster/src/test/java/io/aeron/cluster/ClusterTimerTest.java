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
package io.aeron.cluster;

import io.aeron.Counter;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClusterTimerTest
{
    private static final long MAX_CATALOG_ENTRIES = 128;
    private static final int INTERVAL_MS = 20;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private AeronCluster aeronCluster;

    private final AtomicLong snapshotCount = new AtomicLong();
    private final Counter mockSnapshotCounter = mock(Counter.class);

    @BeforeEach
    public void before()
    {
        when(mockSnapshotCounter.incrementOrdered()).thenAnswer((inv) -> snapshotCount.getAndIncrement());

        launchClusteredMediaDriver(true);
    }

    @AfterEach
    public void after()
    {
        CloseHelper.closeAll(aeronCluster, container, clusteredMediaDriver);

        if (null != clusteredMediaDriver)
        {
            clusteredMediaDriver.consensusModule().context().deleteDirectory();
            clusteredMediaDriver.archive().context().deleteDirectory();
            clusteredMediaDriver.mediaDriver().context().deleteDirectory();
        }
    }

    @Test
    @Timeout(10)
    public void shouldRestartServiceWithTimerFromSnapshotWithFurtherLog()
    {
        final AtomicLong triggeredTimersCounter = new AtomicLong();

        launchReschedulingService(triggeredTimersCounter);
        connectClient();

        while (triggeredTimersCounter.get() < 2)
        {
            Thread.yield();
            Tests.checkInterruptStatus();
        }

        final CountersReader counters = aeronCluster.context().aeron().countersReader();
        final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
        assertNotNull(controlToggle);
        assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

        TestCluster.awaitCount(snapshotCount, 1);
        TestCluster.awaitCount(triggeredTimersCounter, 4);

        forceCloseForRestart();
        triggeredTimersCounter.set(0);

        launchClusteredMediaDriver(false);
        launchReschedulingService(triggeredTimersCounter);
        connectClient();

        TestCluster.awaitCount(triggeredTimersCounter, 2 + 4);

        forceCloseForRestart();
        final long triggeredSinceStart = triggeredTimersCounter.getAndSet(0);

        triggeredTimersCounter.set(0);

        launchClusteredMediaDriver(false);
        launchReschedulingService(triggeredTimersCounter);
        connectClient();

        TestCluster.awaitCount(triggeredTimersCounter, triggeredSinceStart + 4);

        ClusterTests.failOnClusterError();
    }

    @Test
    @Timeout(10)
    public void shouldTriggerRescheduledTimerAfterReplay()
    {
        final AtomicLong triggeredTimersCounter = new AtomicLong();

        launchReschedulingService(triggeredTimersCounter);
        connectClient();

        TestCluster.awaitCount(triggeredTimersCounter, 2);

        forceCloseForRestart();

        long triggeredSinceStart = triggeredTimersCounter.getAndSet(0);

        launchClusteredMediaDriver(false);
        launchReschedulingService(triggeredTimersCounter);

        TestCluster.awaitCount(triggeredTimersCounter, triggeredSinceStart + 2);

        forceCloseForRestart();

        triggeredSinceStart = triggeredTimersCounter.getAndSet(0);

        launchClusteredMediaDriver(false);
        launchReschedulingService(triggeredTimersCounter);

        TestCluster.awaitCount(triggeredTimersCounter, triggeredSinceStart + 4);

        ClusterTests.failOnClusterError();
    }

    private void launchReschedulingService(final AtomicLong triggeredTimersCounter)
    {
        final ClusteredService service = new StubClusteredService()
        {
            int timerId = 1;

            public void onTimerEvent(final long correlationId, final long timestamp)
            {
                triggeredTimersCounter.getAndIncrement();
                scheduleNext(serviceCorrelationId(timerId++), timestamp + INTERVAL_MS);
            }

            public void onStart(final Cluster cluster, final Image snapshotImage)
            {
                super.onStart(cluster, snapshotImage);
                this.cluster = cluster;

                if (null != snapshotImage)
                {
                    final FragmentHandler fragmentHandler =
                        (buffer, offset, length, header) -> timerId = buffer.getInt(offset);

                    while (true)
                    {
                        final int fragments = snapshotImage.poll(fragmentHandler, 1);
                        if (fragments == 1 || snapshotImage.isEndOfStream())
                        {
                            break;
                        }

                        idleStrategy.idle();
                    }
                }
            }

            public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
            {
                final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(SIZE_OF_INT);
                buffer.putInt(0, timerId);

                idleStrategy.reset();
                while (snapshotPublication.offer(buffer, 0, SIZE_OF_INT) < 0)
                {
                    idleStrategy.idle();
                }
            }

            public void onNewLeadershipTermEvent(
                final long leadershipTermId,
                final long logPosition,
                final long timestamp,
                final long termBaseLogPosition,
                final int leaderMemberId,
                final int logSessionId,
                final TimeUnit timeUnit,
                final int appVersion)
            {
                scheduleNext(serviceCorrelationId(timerId++), timestamp + INTERVAL_MS);
            }

            private void scheduleNext(final long correlationId, final long deadlineMs)
            {
                idleStrategy.reset();
                while (!cluster.scheduleTimer(correlationId, deadlineMs))
                {
                    idleStrategy.idle();
                }
            }
        };

        container = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(service)
                .terminationHook(ClusterTests.TERMINATION_HOOK)
                .errorHandler(ClusterTests.errorHandler(0)));
    }

    private AeronCluster connectToCluster()
    {
        return AeronCluster.connect();
    }

    private void forceCloseForRestart()
    {
        CloseHelper.closeAll(aeronCluster, container, clusteredMediaDriver);
    }

    private void connectClient()
    {
        aeronCluster = null;
        aeronCluster = connectToCluster();
    }

    private void launchClusteredMediaDriver(final boolean initialLaunch)
    {
        clusteredMediaDriver = null;

        clusteredMediaDriver = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .warnIfDirectoryExists(initialLaunch)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .errorHandler(ClusterTests.errorHandler(0))
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .recordingEventsEnabled(false)
                .deleteArchiveOnStart(initialLaunch),
            new ConsensusModule.Context()
                .errorHandler(ClusterTests.errorHandler(0))
                .snapshotCounter(mockSnapshotCounter)
                .terminationHook(ClusterTests.TERMINATION_HOOK)
                .deleteDirOnStart(initialLaunch));
    }
}
