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

import io.aeron.test.SlowTest;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.junit.jupiter.api.*;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.cluster.TestCluster.*;
import static io.aeron.cluster.service.Cluster.Role.FOLLOWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SlowTest
public class DynamicMembershipTest
{
    private TestCluster cluster;

    @AfterEach
    void after()
    {
        CloseHelper.close(cluster);
    }

    @Test
    @Timeout(30)
    public void shouldQueryClusterMembers(final TestInfo testInfo)
    {
        cluster = startThreeNodeStaticCluster(NULL_VALUE);
        try
        {
            final TestNode leader = cluster.awaitLeader();
            final ClusterTool.ClusterMembership clusterMembership = leader.clusterMembership();

            assertEquals(leader.index(), clusterMembership.leaderMemberId);
            assertEquals("", clusterMembership.passiveMembersStr);
            assertEquals(cluster.staticClusterMembers(), clusterMembership.activeMembersStr);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshots(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(leader, 4);

            assertEquals(leader.index(), clusterMembership.leaderMemberId);
            assertEquals("", clusterMembership.passiveMembersStr);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshotsThenSend(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponseMessageCount(messageCount);
            cluster.awaitServiceMessageCount(leader, messageCount);
            cluster.awaitServiceMessageCount(dynamicMember, messageCount);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshotsWithCatchup(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponseMessageCount(messageCount);
            cluster.awaitServiceMessageCount(leader, messageCount);

            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            cluster.awaitServiceMessageCount(dynamicMember, messageCount);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeWithEmptySnapshot(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.takeSnapshot(leader);
            cluster.awaitSnapshotCount(cluster.node(0), 1);
            cluster.awaitSnapshotCount(cluster.node(1), 1);
            cluster.awaitSnapshotCount(cluster.node(2), 1);

            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            cluster.awaitSnapshotLoadedForService(dynamicMember);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeWithSnapshot(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponseMessageCount(messageCount);

            cluster.takeSnapshot(leader);
            cluster.awaitSnapshotCount(cluster.node(0), 1);
            cluster.awaitSnapshotCount(cluster.node(1), 1);
            cluster.awaitSnapshotCount(cluster.node(2), 1);

            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            cluster.awaitSnapshotLoadedForService(dynamicMember);
            assertEquals(messageCount, dynamicMember.service().messageCount());
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeWithSnapshotThenSend(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();

            cluster.connectClient();
            final int preSnapshotMessageCount = 10;
            final int postSnapshotMessageCount = 7;
            final int totalMessageCount = preSnapshotMessageCount + postSnapshotMessageCount;
            cluster.sendMessages(preSnapshotMessageCount);
            cluster.awaitResponseMessageCount(preSnapshotMessageCount);

            cluster.takeSnapshot(leader);
            cluster.awaitSnapshotCount(cluster.node(0), 1);
            cluster.awaitSnapshotCount(cluster.node(1), 1);
            cluster.awaitSnapshotCount(cluster.node(2), 1);

            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            cluster.awaitSnapshotLoadedForService(dynamicMember);
            assertEquals(preSnapshotMessageCount, dynamicMember.service().messageCount());

            cluster.sendMessages(postSnapshotMessageCount);
            cluster.awaitResponseMessageCount(totalMessageCount);
            cluster.awaitServiceMessageCount(dynamicMember, totalMessageCount);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldRemoveFollower(final TestInfo testInfo)
    {
        cluster = startThreeNodeStaticCluster(NULL_VALUE);
        try
        {
            final TestNode leader = cluster.awaitLeader();
            final TestNode follower = cluster.followers().get(0);

            follower.terminationExpected(true);
            leader.removeMember(follower.index(), false);

            cluster.awaitNodeTermination(follower);
            cluster.stopNode(follower);

            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(leader, 2);
            assertEquals(leader.index(), clusterMembership.leaderMemberId);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldRemoveLeader(final TestInfo testInfo)
    {
        cluster = startThreeNodeStaticCluster(NULL_VALUE);
        try
        {
            final TestNode initialLeader = cluster.awaitLeader();

            initialLeader.terminationExpected(true);
            initialLeader.removeMember(initialLeader.index(), false);

            cluster.awaitNodeTermination(initialLeader);
            cluster.stopNode(initialLeader);

            final TestNode newLeader = cluster.awaitLeader(initialLeader.index());
            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(newLeader, 2);

            assertEquals(newLeader.index(), clusterMembership.leaderMemberId);
            assertNotEquals(initialLeader.index(), clusterMembership.leaderMemberId);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldRemoveLeaderAfterDynamicNodeJoined(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode initialLeader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            awaitMembershipSize(initialLeader, 4);

            initialLeader.terminationExpected(true);
            initialLeader.removeMember(initialLeader.index(), false);

            cluster.awaitNodeTermination(initialLeader);
            cluster.stopNode(initialLeader);

            final TestNode newLeader = cluster.awaitLeader(initialLeader.index());
            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(newLeader, 3);

            assertEquals(newLeader.index(), clusterMembership.leaderMemberId);
            assertNotEquals(initialLeader.index(), clusterMembership.leaderMemberId);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldRemoveLeaderAfterDynamicNodeJoinedThenRestartCluster(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode initialLeader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            awaitMembershipSize(initialLeader, 4);

            final int initialLeaderIndex = initialLeader.index();
            initialLeader.terminationExpected(true);
            initialLeader.removeMember(initialLeaderIndex, false);

            cluster.awaitNodeTermination(initialLeader);
            cluster.stopNode(initialLeader);

            final TestNode newLeader = cluster.awaitLeader(initialLeaderIndex);
            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(newLeader, 3);

            assertEquals(newLeader.index(), clusterMembership.leaderMemberId);
            assertNotEquals(initialLeaderIndex, clusterMembership.leaderMemberId);

            cluster.stopAllNodes();

            for (int i = 0; i < 3; i++)
            {
                if (initialLeaderIndex != i)
                {
                    cluster.startStaticNode(i, false);
                }
            }

            cluster.awaitLeader();
            assertEquals(1, cluster.followers().size());
            awaitElectionClosed(cluster.followers().get(0));
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldJoinDynamicNodeToSingleStaticLeader(final TestInfo testInfo)
    {
        cluster = startCluster(1, 1);
        try
        {
            final TestNode initialLeader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(1, true);

            awaitElectionClosed(dynamicMember);
            awaitMembershipSize(initialLeader, 2);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    @Timeout(30)
    public void shouldDynamicallyJoinClusterOfThreeNoSnapshotsAndRestartDynamicNode(final TestInfo testInfo)
    {
        cluster = startCluster(3, 1);
        try
        {
            final TestNode leader = cluster.awaitLeader();
            final TestNode dynamicMember = cluster.startDynamicNode(3, true);

            awaitElectionClosed(dynamicMember);
            assertEquals(FOLLOWER, dynamicMember.role());

            final ClusterTool.ClusterMembership clusterMembership = awaitMembershipSize(leader, 4);

            assertEquals(leader.index(), clusterMembership.leaderMemberId);
            assertEquals("", clusterMembership.passiveMembersStr);

            cluster.connectClient();

            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponseMessageCount(messageCount);
            cluster.awaitServiceMessageCount(cluster.node(0), messageCount);
            cluster.awaitServiceMessageCount(cluster.node(1), messageCount);
            cluster.awaitServiceMessageCount(cluster.node(2), messageCount);
            cluster.awaitServiceMessageCount(cluster.node(3), messageCount);

            cluster.stopNode(dynamicMember);
            final TestNode staticMember = cluster.startStaticNodeFromDynamicNode(3);

            awaitElectionClosed(staticMember);
            cluster.awaitServiceMessageCount(cluster.node(3), messageCount);
        }
        catch (final Throwable ex)
        {
            cluster.dumpData(testInfo);
            LangUtil.rethrowUnchecked(ex);
        }
    }
}
