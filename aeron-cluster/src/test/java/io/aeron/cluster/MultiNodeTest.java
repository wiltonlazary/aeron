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

import io.aeron.cluster.service.Cluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiNodeTest
{
    @Test
    @Timeout(20)
    public void shouldElectAppointedLeaderWithThreeNodesWithNoReplayNoSnapshot()
    {
        final int appointedLeaderIndex = 1;

        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(appointedLeaderIndex))
        {
            final TestNode leader = cluster.awaitLeader();

            assertEquals(appointedLeaderIndex, leader.index());
            assertEquals(Cluster.Role.LEADER, leader.role());
            assertEquals(Cluster.Role.FOLLOWER, cluster.node(0).role());
            assertEquals(Cluster.Role.FOLLOWER, cluster.node(2).role());
        }
    }

    @Test
    @Timeout(20)
    public void shouldReplayWithAppointedLeaderWithThreeNodesWithNoSnapshot()
    {
        final int appointedLeaderIndex = 1;

        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(appointedLeaderIndex))
        {
            TestNode leader = cluster.awaitLeader();

            assertEquals(appointedLeaderIndex, leader.index());
            assertEquals(Cluster.Role.LEADER, leader.role());

            cluster.connectClient();
            final int messageCount = 10;
            cluster.sendMessages(messageCount);
            cluster.awaitResponseMessageCount(messageCount);
            cluster.awaitServiceMessageCount(leader, messageCount);

            cluster.stopAllNodes();
            cluster.restartAllNodes(false);

            leader = cluster.awaitLeader();
            cluster.awaitServiceMessageCount(leader, messageCount);
            cluster.awaitServiceMessageCount(cluster.node(0), messageCount);
            cluster.awaitServiceMessageCount(cluster.node(2), messageCount);
        }
    }

    @Test
    @Timeout(20)
    public void shouldCatchUpWithAppointedLeaderWithThreeNodesWithNoSnapshot()
    {
        final int appointedLeaderIndex = 1;

        try (TestCluster cluster = TestCluster.startThreeNodeStaticCluster(appointedLeaderIndex))
        {
            TestNode leader = cluster.awaitLeader();

            assertEquals(appointedLeaderIndex, leader.index());
            assertEquals(Cluster.Role.LEADER, leader.role());

            cluster.connectClient();
            final int preCatchupMessageCount = 5;
            final int postCatchupMessageCount = 10;
            final int totalMessageCount = preCatchupMessageCount + postCatchupMessageCount;
            cluster.sendMessages(preCatchupMessageCount);
            cluster.awaitResponseMessageCount(preCatchupMessageCount);
            cluster.awaitServiceMessageCount(leader, preCatchupMessageCount);

            cluster.stopNode(cluster.node(0));

            cluster.sendMessages(postCatchupMessageCount);
            cluster.awaitResponseMessageCount(postCatchupMessageCount);

            cluster.stopAllNodes();
            cluster.restartAllNodes(false);

            leader = cluster.awaitLeader();
            cluster.awaitServiceMessageCount(leader, totalMessageCount);
            cluster.awaitServiceMessageCount(cluster.node(0), totalMessageCount);
            cluster.awaitServiceMessageCount(cluster.node(2), totalMessageCount);
        }
    }
}
