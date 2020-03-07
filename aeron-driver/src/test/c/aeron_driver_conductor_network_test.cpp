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

#include <cinttypes>
#include "aeron_driver_conductor_test.h"

class DriverConductorNetworkTest : public DriverConductorTest
{
public:
    DriverConductorNetworkTest() : DriverConductorTest()
    {
    }
};

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddSingleNetworkPublication)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);

    doWork();

    aeron_send_channel_endpoint_t *endpoint = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_send_channel_endpoint_t *)NULL);

    aeron_network_publication_t *publication = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id);

    ASSERT_NE(publication, (aeron_network_publication_t *)NULL);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_PUBLICATION_READY);

        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_EQ(response.correlationId(), pub_id);
        EXPECT_GT(response.logFileName().length(), 0u);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddAndRemoveSingleNetworkPublication)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(removePublication(client_id, remove_correlation_id, pub_id), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), remove_correlation_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddSingleNetworkSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);

    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_receive_channel_endpoint_t *)NULL);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_SUBSCRIPTION_READY);

        const command::SubscriptionReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), sub_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddAndRemoveSingleNetworkSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id, sub_id), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), remove_correlation_id);
    };

    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddMultipleNetworkPublications)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1, STREAM_ID_2, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, CHANNEL_1, STREAM_ID_3, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, CHANNEL_1, STREAM_ID_4, false), 0);
    doWork();

    aeron_send_channel_endpoint_t *endpoint = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 1u);

    aeron_network_publication_t *publication_1 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_1);
    aeron_network_publication_t *publication_2 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_2);
    aeron_network_publication_t *publication_3 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_3);
    aeron_network_publication_t *publication_4 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_4);

    ASSERT_NE(publication_1, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_2, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_3, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_4, (aeron_network_publication_t *)NULL);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddMultipleNetworkPublicationsDifferentChannelsSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_2, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, CHANNEL_3, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, CHANNEL_4, STREAM_ID_1, false), 0);
    doWork();

    aeron_send_channel_endpoint_t *endpoint_1 = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);
    aeron_send_channel_endpoint_t *endpoint_2 = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_2);
    aeron_send_channel_endpoint_t *endpoint_3 = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_3);
    aeron_send_channel_endpoint_t *endpoint_4 = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_4);

    ASSERT_NE(endpoint_1, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_2, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_3, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_4, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 4u);

    aeron_network_publication_t *publication_1 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_1);
    aeron_network_publication_t *publication_2 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_2);
    aeron_network_publication_t *publication_3 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_3);
    aeron_network_publication_t *publication_4 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_4);

    ASSERT_NE(publication_1, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_2, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_3, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_4, (aeron_network_publication_t *)NULL);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddAndRemoveMultipleNetworkPublicationsToSameChannelSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();
    int64_t remove_correlation_id_1 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, CHANNEL_1, STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);

    aeron_network_publication_t *publication = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_1);

    ASSERT_NE(publication, (aeron_network_publication_t *)NULL);
    ASSERT_EQ(publication->conductor_fields.refcnt, 4);

    ASSERT_EQ(removePublication(client_id, remove_correlation_id_1, pub_id_2), 0);
    doWork();

    publication = aeron_driver_conductor_find_network_publication(&m_conductor.m_conductor, pub_id_1);
    ASSERT_NE(publication, (aeron_network_publication_t *)NULL);
    ASSERT_EQ(publication->conductor_fields.refcnt, 3);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), remove_correlation_id_1);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddMultipleExclusiveNetworkPublicationsWithSameChannelSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1, STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1, STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, CHANNEL_1, STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, CHANNEL_1, STREAM_ID_1, true), 0);
    doWork();

    aeron_send_channel_endpoint_t *endpoint = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_send_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 1u);

    aeron_network_publication_t *publication_1 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_1);
    aeron_network_publication_t *publication_2 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_2);
    aeron_network_publication_t *publication_3 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_3);
    aeron_network_publication_t *publication_4 = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id_4);

    ASSERT_NE(publication_1, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_2, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_3, (aeron_network_publication_t *)NULL);
    ASSERT_NE(publication_4, (aeron_network_publication_t *)NULL);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddSingleNetworkPublicationWithSpecifiedSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, false), 0);

    doWork();

    aeron_send_channel_endpoint_t *endpoint = aeron_driver_conductor_find_send_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1_WITH_SESSION_ID_1);

    ASSERT_NE(endpoint, (aeron_send_channel_endpoint_t *)NULL);

    aeron_network_publication_t *publication = aeron_driver_conductor_find_network_publication(
        &m_conductor.m_conductor, pub_id);

    ASSERT_NE(publication, (aeron_network_publication_t *)NULL);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_PUBLICATION_READY);

        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_EQ(response.sessionId(), SESSION_ID_1);
        EXPECT_EQ(response.correlationId(), pub_id);
        EXPECT_GT(response.logFileName().length(), 0u);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldAddSecondNetworkPublicationWithSpecifiedSessionIdAndSameMtu)
{
    int64_t client_id1 = nextCorrelationId();
    int64_t pub_id1 = nextCorrelationId();
    int64_t client_id2 = nextCorrelationId();
    int64_t pub_id2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id1, pub_id1, CHANNEL_1_WITH_SESSION_ID_1_MTU_1, STREAM_ID_1, false), 0);
    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(addNetworkPublication(client_id2, pub_id2, CHANNEL_1_WITH_SESSION_ID_1_MTU_1, STREAM_ID_1, false), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_PUBLICATION_READY);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}


TEST_F(DriverConductorNetworkTest, shouldFailToAddSecondNetworkPublicationWithSpecifiedSessionIdAndDifferentMtu)
{
    int64_t client_id1 = nextCorrelationId();
    int64_t pub_id1 = nextCorrelationId();
    int64_t client_id2 = nextCorrelationId();
    int64_t pub_id2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id1, pub_id1, CHANNEL_1_WITH_SESSION_ID_1_MTU_1, STREAM_ID_1, false), 0);
    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(addNetworkPublication(client_id2, pub_id2, CHANNEL_1_WITH_SESSION_ID_1_MTU_2, STREAM_ID_1, false), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldAddSecondNetworkPublicationWithSpecifiedSessionIdAndSameTermLength)
{
    int64_t client_id1 = nextCorrelationId();
    int64_t pub_id1 = nextCorrelationId();
    int64_t client_id2 = nextCorrelationId();
    int64_t pub_id2 = nextCorrelationId();

    const char *channelUri = CHANNEL_1_WITH_SESSION_ID_1_TERM_LENGTH_1;
    
    ASSERT_EQ(addNetworkPublication(client_id1, pub_id1, channelUri, STREAM_ID_1, false), 0);
    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(addNetworkPublication(client_id2, pub_id2, channelUri, STREAM_ID_1, false), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_PUBLICATION_READY);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldFailToAddSecondNetworkPublicationWithSpecifiedSessionIdAndDifferentTermLength)
{
    int64_t client_id1 = nextCorrelationId();
    int64_t pub_id1 = nextCorrelationId();
    int64_t client_id2 = nextCorrelationId();
    int64_t pub_id2 = nextCorrelationId();

    const char *channelUri1 = CHANNEL_1_WITH_SESSION_ID_1_TERM_LENGTH_1;
    ASSERT_EQ(addNetworkPublication(client_id1, pub_id1, channelUri1, STREAM_ID_1, false), 0);
    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    const char *channelUri2 = CHANNEL_1_WITH_SESSION_ID_1_TERM_LENGTH_2;
    ASSERT_EQ(addNetworkPublication(client_id2, pub_id2, channelUri2, STREAM_ID_1, false), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddAndRemoveSingleNetworkPublicationWithExplicitSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(removePublication(client_id, remove_correlation_id, pub_id), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), remove_correlation_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddSingleNetworkPublicationThatAvoidCollisionWithSpecifiedSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int32_t next_session_id = SESSION_ID_1;

    m_conductor.manuallySetNextSessionId(next_session_id);

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, true), 0);

    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, true), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 2u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_EXCLUSIVE_PUBLICATION_READY);

        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_NE(response.sessionId(), next_session_id);
        EXPECT_EQ(response.correlationId(), pub_id);
        EXPECT_GT(response.logFileName().length(), 0u);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnDuplicateExclusivePublicationWithSameSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, true), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);
    doWork();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, true), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id_2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnDuplicateSharedPublicationWithDifferentSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);
    doWork();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1_WITH_SESSION_ID_2, STREAM_ID_1, false), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id_2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnDuplicateSharedPublicationWithExclusivePublicationWithSameSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, true), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);
    doWork();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, false), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id_2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnDuplicateExclusivePublicationWithSharedPublicationWithSameSessionId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);
    doWork();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1_WITH_SESSION_ID_1, STREAM_ID_1, true), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id_2);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddMultipleNetworkSubscriptionsWithSameChannelSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();
    int64_t sub_id_3 = nextCorrelationId();
    int64_t sub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_3, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_4, CHANNEL_1, STREAM_ID_1, -1), 0);

    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 1u);
    ASSERT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 4u);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddMultipleNetworkSubscriptionsWithDifferentChannelSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();
    int64_t sub_id_3 = nextCorrelationId();
    int64_t sub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_2, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_3, CHANNEL_3, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_4, CHANNEL_4, STREAM_ID_1, -1), 0);

    doWork();

    aeron_receive_channel_endpoint_t *endpoint_1 = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);
    aeron_receive_channel_endpoint_t *endpoint_2 = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_2);
    aeron_receive_channel_endpoint_t *endpoint_3 = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_3);
    aeron_receive_channel_endpoint_t *endpoint_4 = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_4);

    ASSERT_NE(endpoint_1, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_2, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_3, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_NE(endpoint_4, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 4u);
    ASSERT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 4u);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldKeepSubscriptionMediaEndpointUponRemovalOfAllButOneSubscriber)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();
    int64_t sub_id_3 = nextCorrelationId();
    int64_t sub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_1, STREAM_ID_2, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_3, CHANNEL_1, STREAM_ID_3, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_4, CHANNEL_1, STREAM_ID_4, -1), 0);

    doWork();

    int64_t remove_correlation_id_1 = nextCorrelationId();
    int64_t remove_correlation_id_2 = nextCorrelationId();
    int64_t remove_correlation_id_3 = nextCorrelationId();

    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id_1, sub_id_1), 0);
    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id_2, sub_id_2), 0);
    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id_3, sub_id_3), 0);

    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    ASSERT_NE(endpoint, (aeron_receive_channel_endpoint_t *)NULL);
    ASSERT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 1u);
    ASSERT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 7u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnRemovePublicationOnUnknownRegistrationId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(removePublication(client_id, remove_correlation_id, pub_id), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), remove_correlation_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnRemoveSubscriptionOnUnknownRegistrationId)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id, sub_id), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), remove_correlation_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnAddPublicationWithInvalidUri)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, INVALID_URI, STREAM_ID_1, false), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), pub_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldErrorOnAddSubscriptionWithInvalidUri)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, INVALID_URI, STREAM_ID_1, -1), 0);
    doWork();
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_ERROR);

        const command::ErrorResponseFlyweight response(buffer, offset);

        EXPECT_EQ(response.offendingCommandCorrelationId(), sub_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToTimeoutNetworkPublication)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    doWorkForNs(
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2));
    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 0u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_CLIENT_TIMEOUT);

        const command::ClientTimeoutFlyweight response(buffer, offset);

        EXPECT_EQ(response.clientId(), client_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToNotTimeoutNetworkPublicationOnKeepalive)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    int64_t timeout =
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToTimeoutNetworkSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    doWorkForNs(
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2));
    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 0u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_CLIENT_TIMEOUT);

        const command::ClientTimeoutFlyweight response(buffer, offset);

        EXPECT_EQ(response.clientId(), client_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToNotTimeoutNetworkSubscriptionOnKeepalive)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    int64_t timeout =
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToTimeoutSendChannelEndpointWithClientKeepaliveAfterRemovePublication)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    ASSERT_EQ(removePublication(client_id, remove_correlation_id, pub_id), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    int64_t timeout =
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToTimeoutReceiveChannelEndpointWithClientKeepaliveAfterRemoveSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 1u);
    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id, sub_id), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    int64_t timeout = m_context.m_context->client_liveness_timeout_ns;

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldCreatePublicationImageForActiveNetworkSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 1u);

    aeron_publication_image_t *image = aeron_driver_conductor_find_publication_image(
        &m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);
    EXPECT_EQ(aeron_publication_image_num_subscriptions(image), 1u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

        const command::ImageBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.sessionId(), SESSION_ID);
        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_EQ(response.correlationId(), aeron_publication_image_registration_id(image));
        EXPECT_EQ(response.subscriptionRegistrationId(), sub_id);

        EXPECT_EQ(std::string(aeron_publication_image_log_file_name(image)), response.logFileName());
        EXPECT_EQ(SOURCE_IDENTITY, response.sourceIdentity());
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldNotCreatePublicationImageForNonActiveNetworkSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_2, 1000);

    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 0u);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldRemoveSubscriptionFromImageWhenRemoveSubscription)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 1u);

    aeron_publication_image_t *image =
        aeron_driver_conductor_find_publication_image(&m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);
    EXPECT_EQ(aeron_publication_image_num_subscriptions(image), 1u);

    int64_t remove_correlation_id = nextCorrelationId();
    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id, sub_id), 0);
    doWork();

    EXPECT_EQ(aeron_publication_image_num_subscriptions(image), 0u);

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);
}


TEST_F(DriverConductorNetworkTest, shouldTimeoutImageAndSendUnavailableImageWhenNoAcitvity)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 1u);

    aeron_publication_image_t *image = aeron_driver_conductor_find_publication_image(
        &m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);
    EXPECT_EQ(aeron_publication_image_num_subscriptions(image), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    int64_t image_correlation_id = image->conductor_fields.managed_resource.registration_id;

    int64_t timeout = m_context.m_context->image_liveness_timeout_ns +
        (m_context.m_context->client_liveness_timeout_ns * 2) + (m_context.m_context->timer_interval_ns * 3);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 0u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_UNAVAILABLE_IMAGE);

        const command::ImageMessageFlyweight response(buffer, offset);

        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_EQ(response.correlationId(), image_correlation_id);
        EXPECT_EQ(response.subscriptionRegistrationId(), sub_id);
        EXPECT_EQ(response.channel(), CHANNEL_1);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldRemoveSubscriptionAfterImageTimeout)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id = nextCorrelationId();
    int64_t remove_correlation_id = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    int64_t timeout = m_context.m_context->image_liveness_timeout_ns +
        (m_context.m_context->client_liveness_timeout_ns * 2) + (m_context.m_context->timer_interval_ns * 3);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 3u);
    EXPECT_EQ(aeron_driver_conductor_num_images(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_active_network_subscriptions(&m_conductor.m_conductor, CHANNEL_1, STREAM_ID_1), 0u);
    ASSERT_EQ(removeSubscription(client_id, remove_correlation_id, sub_id), 0);
    doWork();
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldSendAvailableImageForMultipleSubscriptions)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    aeron_publication_image_t *image = aeron_driver_conductor_find_publication_image(
        &m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);
    EXPECT_EQ(aeron_publication_image_num_subscriptions(image), 2u);

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

        const command::ImageBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.sessionId(), SESSION_ID);
        EXPECT_EQ(response.streamId(), STREAM_ID_1);
        EXPECT_EQ(response.correlationId(), aeron_publication_image_registration_id(image));
        EXPECT_TRUE(response.subscriptionRegistrationId() == sub_id_1 || response.subscriptionRegistrationId() == sub_id_2);
        EXPECT_EQ(std::string(aeron_publication_image_log_file_name(image)), response.logFileName());
        EXPECT_EQ(SOURCE_IDENTITY, response.sourceIdentity());
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 2u);
}

TEST_F(DriverConductorNetworkTest, shouldSendAvailableImageForSecondSubscriptionAfterCreatingImage)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    aeron_publication_image_t *image = aeron_driver_conductor_find_publication_image(
        &m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();

    size_t response_number = 0;
    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        if (0 == response_number)
        {
            ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_SUBSCRIPTION_READY);

            const command::SubscriptionReadyFlyweight response(buffer, offset);

            EXPECT_EQ(response.correlationId(), sub_id_1);
        }
        else if (1 == response_number)
        {
            ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

            const command::ImageBuffersReadyFlyweight response(buffer, offset);

            EXPECT_EQ(response.sessionId(), SESSION_ID);
            EXPECT_EQ(response.streamId(), STREAM_ID_1);
            EXPECT_EQ(response.correlationId(), aeron_publication_image_registration_id(image));
            EXPECT_EQ(response.subscriptionRegistrationId(), sub_id_1);
            EXPECT_EQ(std::string(aeron_publication_image_log_file_name(image)), response.logFileName());
            EXPECT_EQ(SOURCE_IDENTITY, response.sourceIdentity());
        }
        else if (2 == response_number)
        {
            ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_SUBSCRIPTION_READY);

            const command::SubscriptionReadyFlyweight response(buffer, offset);

            EXPECT_EQ(response.correlationId(), sub_id_2);
        }
        else if (3 == response_number)
        {
            ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

            const command::ImageBuffersReadyFlyweight response(buffer, offset);

            EXPECT_EQ(response.sessionId(), SESSION_ID);
            EXPECT_EQ(response.streamId(), STREAM_ID_1);
            EXPECT_EQ(response.correlationId(), aeron_publication_image_registration_id(image));
            EXPECT_EQ(response.subscriptionRegistrationId(), sub_id_2);
            EXPECT_EQ(std::string(aeron_publication_image_log_file_name(image)), response.logFileName());
            EXPECT_EQ(SOURCE_IDENTITY, response.sourceIdentity());
        }

        response_number++;
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 4u);
}

TEST_F(DriverConductorNetworkTest, shouldTimeoutImageAndSendUnavailableImageWhenNoActivityForMultipleSubscriptions)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();

    aeron_receive_channel_endpoint_t *endpoint = aeron_driver_conductor_find_receive_channel_endpoint(
        &m_conductor.m_conductor, CHANNEL_1);

    createPublicationImage(endpoint, STREAM_ID_1, 1000);

    aeron_publication_image_t *image = aeron_driver_conductor_find_publication_image(
        &m_conductor.m_conductor, endpoint, STREAM_ID_1);

    EXPECT_NE(image, (aeron_publication_image_t *)NULL);

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, CHANNEL_1, STREAM_ID_1, -1), 0);
    doWork();

    int64_t image_correlation_id = aeron_publication_image_registration_id(image);
    int64_t timeout = m_context.m_context->image_liveness_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2);

    doWorkForNs(
        timeout,
        100,
        [&]()
        {
            clientKeepalive(client_id);
        });

    size_t response_number = 0;
    auto handler =
        [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
        {
            if (0 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_SUBSCRIPTION_READY);

                const command::SubscriptionReadyFlyweight response(buffer, offset);

                EXPECT_EQ(response.correlationId(), sub_id_1);
            }
            else if (1 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

                const command::ImageBuffersReadyFlyweight response(buffer, offset);

                EXPECT_EQ(response.correlationId(), image_correlation_id);
            }
            else if (2 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_SUBSCRIPTION_READY);

                const command::SubscriptionReadyFlyweight response(buffer, offset);

                EXPECT_EQ(response.correlationId(), sub_id_2);
            }
            else if (3 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_AVAILABLE_IMAGE);

                const command::ImageBuffersReadyFlyweight response(buffer, offset);

                EXPECT_EQ(response.correlationId(), image_correlation_id);
            }
            else if (4 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_UNAVAILABLE_IMAGE);

                const command::ImageMessageFlyweight response(buffer, offset);

                EXPECT_EQ(response.streamId(), STREAM_ID_1);
                EXPECT_EQ(response.correlationId(), image_correlation_id);
                EXPECT_EQ(response.subscriptionRegistrationId(), sub_id_1);
                EXPECT_EQ(response.channel(), CHANNEL_1);
            }
            else if (5 == response_number)
            {
                ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_UNAVAILABLE_IMAGE);

                const command::ImageMessageFlyweight response(buffer, offset);

                EXPECT_EQ(response.streamId(), STREAM_ID_1);
                EXPECT_EQ(response.correlationId(), image_correlation_id);
                EXPECT_EQ(response.subscriptionRegistrationId(), sub_id_2);
                EXPECT_EQ(response.channel(), CHANNEL_1);
            }

            response_number++;
        };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 6u);
}

TEST_F(DriverConductorNetworkTest, shouldUseExistingChannelEndpointOnAddPublicationWithSameTagIdAndSameStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1 "|tags=1001", STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, "aeron:udp?tags=1001", STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    doWorkForNs(
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2));
    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldUseExistingChannelEndpointOnAddPublicationWithSameTagIdDifferentStreamId)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1 "|tags=1001", STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, "aeron:udp?tags=1001", STREAM_ID_2, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 2u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    doWorkForNs(
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2));
    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_send_channel_endpoints(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldUseExistingChannelEndpointOnAddSubscriptionWithSameTagId)
{
    int64_t client_id = nextCorrelationId();
    int64_t sub_id_1 = nextCorrelationId();
    int64_t sub_id_2 = nextCorrelationId();

    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_1, CHANNEL_1 "|tags=1001", STREAM_ID_1, false), 0);
    ASSERT_EQ(addNetworkSubscription(client_id, sub_id_2, "aeron:udp?tags=1001", STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 2u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 2u);

    doWorkForNs(
        m_context.m_context->publication_linger_timeout_ns + (m_context.m_context->client_liveness_timeout_ns * 2));
    EXPECT_EQ(aeron_driver_conductor_num_clients(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_network_subscriptions(&m_conductor.m_conductor), 0u);
    EXPECT_EQ(aeron_driver_conductor_num_receive_channel_endpoints(&m_conductor.m_conductor), 0u);
}

TEST_F(DriverConductorNetworkTest, shouldBeAbleToAddAndRemoveDestinationToManualMdcPublication)
{
    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();
    int64_t add_destination_id = nextCorrelationId();
    int64_t remove_destination_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_MDC_MANUAL, STREAM_ID_1, false), 0);
    doWork();
    EXPECT_EQ(aeron_driver_conductor_num_network_publications(&m_conductor.m_conductor), 1u);
    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 1u);

    ASSERT_EQ(addDestination(client_id, add_destination_id, pub_id, CHANNEL_1), 0);
    doWork();
    auto add_handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), add_destination_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(add_handler), 1u);

    ASSERT_EQ(removeDestination(client_id, remove_destination_id, pub_id, CHANNEL_1), 0);
    doWork();
    auto remove_handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_OPERATION_SUCCESS);

        const command::OperationSucceededFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), remove_destination_id);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(remove_handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldNotAddDynamicSessionIdInReservedRange)
{
    m_conductor.manuallySetNextSessionId(m_conductor.m_conductor.publication_reserved_session_id_low);

    int64_t client_id = nextCorrelationId();
    int64_t pub_id = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id, CHANNEL_1, STREAM_ID_1, false), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_PUBLICATION_READY);
        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_TRUE(
            response.sessionId() < m_conductor.m_conductor.publication_reserved_session_id_low ||
            m_conductor.m_conductor.publication_reserved_session_id_high < response.sessionId())
                << "Session Id [" << response.sessionId() << "] should not be in the range: "
                << m_conductor.m_conductor.publication_reserved_session_id_low
                << " to "
                << m_conductor.m_conductor.publication_reserved_session_id_high;

    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldNotAccidentallyBumpIntoExistingSessionId)
{
    int next_session_id = SESSION_ID_3;
    m_conductor.manuallySetNextSessionId(next_session_id);

    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, CHANNEL_1_WITH_SESSION_ID_3, STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, CHANNEL_1_WITH_SESSION_ID_4, STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, CHANNEL_1_WITH_SESSION_ID_5, STREAM_ID_1, true), 0);

    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 3u);

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, CHANNEL_1, STREAM_ID_1, true), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_EXCLUSIVE_PUBLICATION_READY);
        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), pub_id_4);
        EXPECT_NE(response.sessionId(), SESSION_ID_3);
        EXPECT_NE(response.sessionId(), SESSION_ID_4);
        EXPECT_NE(response.sessionId(), SESSION_ID_5);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}

TEST_F(DriverConductorNetworkTest, shouldNotAccidentallyBumpIntoExistingSessionIdWithSessionIdWrapping)
{
    int32_t session_id_1 = INT32_MAX - 1;
    int32_t session_id_2 = session_id_1 + 1;
    int32_t session_id_3 = INT32_MIN;
    int32_t session_id_4 = session_id_3 + 1;

    std::string channel1StreamId1(std::string(CHANNEL_1) + "|session-id=" + std::to_string(session_id_1));
    std::string channel1StreamId2(std::string(CHANNEL_1) + "|session-id=" + std::to_string(session_id_2));
    std::string channel1StreamId3(std::string(CHANNEL_1) + "|session-id=" + std::to_string(session_id_3));
    std::string channel1StreamId4(std::string(CHANNEL_1) + "|session-id=" + std::to_string(session_id_4));

    m_conductor.manuallySetNextSessionId(session_id_1);

    int64_t client_id = nextCorrelationId();
    int64_t pub_id_1 = nextCorrelationId();
    int64_t pub_id_2 = nextCorrelationId();
    int64_t pub_id_3 = nextCorrelationId();
    int64_t pub_id_4 = nextCorrelationId();
    int64_t pub_id_5 = nextCorrelationId();

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_1, channel1StreamId1.c_str(), STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_2, channel1StreamId2.c_str(), STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_3, channel1StreamId3.c_str(), STREAM_ID_1, true), 0);
    ASSERT_EQ(addNetworkPublication(client_id, pub_id_4, channel1StreamId4.c_str(), STREAM_ID_1, true), 0);

    doWork();

    EXPECT_EQ(readAllBroadcastsFromConductor(null_handler), 4u);

    ASSERT_EQ(addNetworkPublication(client_id, pub_id_5, CHANNEL_1, STREAM_ID_1, true), 0);

    doWork();

    auto handler = [&](std::int32_t msgTypeId, AtomicBuffer& buffer, util::index_t offset, util::index_t length)
    {
        ASSERT_EQ(msgTypeId, AERON_RESPONSE_ON_EXCLUSIVE_PUBLICATION_READY);
        const command::PublicationBuffersReadyFlyweight response(buffer, offset);

        EXPECT_EQ(response.correlationId(), pub_id_5);
        EXPECT_NE(response.sessionId(), session_id_1);
        EXPECT_NE(response.sessionId(), session_id_2);
        EXPECT_NE(response.sessionId(), session_id_3);
        EXPECT_NE(response.sessionId(), session_id_4);
    };

    EXPECT_EQ(readAllBroadcastsFromConductor(handler), 1u);
}
