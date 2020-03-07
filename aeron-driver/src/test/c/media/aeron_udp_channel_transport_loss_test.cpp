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

#include <gtest/gtest.h>
#include <media/aeron_udp_channel_transport.h>

extern "C"
{
#include <media/aeron_udp_channel_transport_loss.h>
#include <uri/aeron_uri.h>
#include <protocol/aeron_udp_protocol.h>

#if !defined(HAVE_STRUCT_MMSGHDR)
struct mmsghdr
{
    struct msghdr msg_hdr;
    unsigned int msg_len;
};
#endif
}

class UdpChannelTransportLossTest : public testing::Test
{
public:
    UdpChannelTransportLossTest() = default;
};

typedef struct delegate_recv_state_stct
{
    int messages_received;
    int bytes_received;
}
delegate_recv_state_t;

void aeron_udp_channel_interceptor_loss_incoming_delegate(
    void *interceptor_state,
    aeron_udp_channel_incoming_interceptor_t *delegate,
    void *receiver_clientd,
    void *endpoint_clientd,
    uint8_t *buffer,
    size_t length,
    struct sockaddr_storage *addr)
{
    auto *state = (delegate_recv_state_t *)interceptor_state;

    state->messages_received++;
    state->bytes_received += (int)length;
}

TEST_F(UdpChannelTransportLossTest, shouldDiscardAllPacketsWithRateOfOne)
{
    uint16_t msg_type = AERON_HDR_TYPE_DATA;
    aeron_udp_channel_incoming_interceptor_t delegate;
    delegate_recv_state_t delegate_recv_state = { 0, 0 };
    aeron_udp_channel_interceptor_loss_params_t params;
    uint8_t data_0[1024];
    uint8_t data_1[1024];
    aeron_frame_header_t *frame_header;

    frame_header = (aeron_frame_header_t *)(data_0);
    frame_header->type = msg_type;
    frame_header = (aeron_frame_header_t *)(data_1);
    frame_header->type = msg_type;

    params.rate = 1.0;
    params.recv_msg_type_mask = 1U << msg_type;
    params.seed = 0;

    delegate.incoming_func = aeron_udp_channel_interceptor_loss_incoming_delegate;
    delegate.interceptor_state = &delegate_recv_state;

    aeron_udp_channel_interceptor_loss_configure(&params);

    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_0, 1024, NULL);
    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_1, 1024, NULL);

    EXPECT_EQ(delegate_recv_state.messages_received, 0);
}

TEST_F(UdpChannelTransportLossTest, shouldNotDiscardAllPacketsWithRateOfOneWithDifferentMessageType)
{
    uint16_t loss_msg_type = AERON_HDR_TYPE_DATA;
    uint16_t data_msg_type = AERON_HDR_TYPE_SETUP;
    aeron_udp_channel_incoming_interceptor_t delegate;
    delegate_recv_state_t delegate_recv_state = { 0, 0 };
    aeron_udp_channel_interceptor_loss_params_t params;
    uint8_t data_0[1024];
    uint8_t data_1[1024];
    aeron_frame_header_t *frame_header;

    frame_header = (aeron_frame_header_t *)(data_0);
    frame_header->type = data_msg_type;
    frame_header = (aeron_frame_header_t *)(data_1);
    frame_header->type = data_msg_type;

    params.rate = 1.0;
    params.recv_msg_type_mask = 1U << loss_msg_type;
    params.seed = 0;

    delegate.incoming_func = aeron_udp_channel_interceptor_loss_incoming_delegate;
    delegate.interceptor_state = &delegate_recv_state;

    aeron_udp_channel_interceptor_loss_configure(&params);

    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_0, 1024, NULL);
    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_1, 1024, NULL);

    EXPECT_EQ(delegate_recv_state.messages_received, 2);
}

TEST_F(UdpChannelTransportLossTest, shouldNotDiscardAllPacketsWithRateOfZero)
{
    uint16_t loss_msg_type = AERON_HDR_TYPE_DATA;
    aeron_udp_channel_incoming_interceptor_t delegate;
    delegate_recv_state_t delegate_recv_state = { 0, 0 };
    aeron_udp_channel_interceptor_loss_params_t params;
    uint8_t data_0[1024];
    uint8_t data_1[1024];
    aeron_frame_header_t *frame_header;

    frame_header = (aeron_frame_header_t *)(data_0);
    frame_header->type = loss_msg_type;
    frame_header = (aeron_frame_header_t *)(data_1);
    frame_header->type = loss_msg_type;

    params.rate = 0.0;
    params.recv_msg_type_mask = 1U << loss_msg_type;
    params.seed = 0;

    delegate.incoming_func = aeron_udp_channel_interceptor_loss_incoming_delegate;
    delegate.interceptor_state = &delegate_recv_state;

    aeron_udp_channel_interceptor_loss_configure(&params);

    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_0, 1024, NULL);
    aeron_udp_channel_interceptor_loss_incoming(
        NULL, &delegate, NULL, NULL, data_1, 1024, NULL);

    EXPECT_EQ(delegate_recv_state.messages_received, 2);
}

TEST_F(UdpChannelTransportLossTest, shouldDiscardRoughlyHalfTheMessages)
{
    uint16_t msg_type = AERON_HDR_TYPE_DATA;
    aeron_udp_channel_incoming_interceptor_t delegate;
    delegate_recv_state_t delegate_recv_state = { 0, 0 };
    aeron_udp_channel_interceptor_loss_params_t params;
    aeron_frame_header_t *frame_header;

    const size_t vlen = 10;
    uint8_t data[vlen * 1024];

    params.rate = 0.5;
    params.recv_msg_type_mask = 1U << msg_type;
    params.seed = 23764;

    delegate.incoming_func = aeron_udp_channel_interceptor_loss_incoming_delegate;
    delegate.interceptor_state = &delegate_recv_state;

    aeron_udp_channel_interceptor_loss_configure(&params);

    for (size_t i = 0; i < vlen; i++)
    {
        frame_header = (aeron_frame_header_t *)(data + (i * 1024));
        frame_header->type = msg_type;

        aeron_udp_channel_interceptor_loss_incoming(
            NULL, &delegate, NULL, NULL, data + (i * 1024), 1024, NULL);
    }

    EXPECT_LT(delegate_recv_state.messages_received, static_cast<int>(vlen));
    EXPECT_GT(delegate_recv_state.messages_received, 0);
    EXPECT_LT(delegate_recv_state.bytes_received, static_cast<int64_t>(vlen * 1024));
    EXPECT_GT(delegate_recv_state.bytes_received, 0);
    EXPECT_EQ(delegate_recv_state.messages_received, 6);
}

TEST_F(UdpChannelTransportLossTest, shouldParseAllParams)
{
    aeron_udp_channel_interceptor_loss_params_t params;
    memset(&params, 0, sizeof(params));

    char *uri = strdup("rate=0.20|seed=10|recv-msg-mask=0xF");
    int i = aeron_udp_channel_interceptor_loss_parse_params(uri, &params);

    EXPECT_EQ(i, 0);
    EXPECT_EQ(params.rate, 0.2);
    EXPECT_EQ(params.seed, 10ull);
    EXPECT_EQ(params.recv_msg_type_mask, 0xFul);
}

TEST_F(UdpChannelTransportLossTest, shouldFailOnInvalidRate)
{
    aeron_udp_channel_interceptor_loss_params_t params;
    memset(&params, 0, sizeof(params));

    char *uri = strdup("rate=abc");
    int i = aeron_udp_channel_interceptor_loss_parse_params(uri, &params);

    EXPECT_EQ(i, -1);
}

TEST_F(UdpChannelTransportLossTest, shouldFailOnInvalidSeed)
{
    aeron_udp_channel_interceptor_loss_params_t params;
    memset(&params, 0, sizeof(params));

    char *uri = strdup("seed=abc");
    int i = aeron_udp_channel_interceptor_loss_parse_params(uri, &params);

    EXPECT_EQ(i, -1);
}

TEST_F(UdpChannelTransportLossTest, shouldFailOnInvalidRecvMsgMask)
{
    aeron_udp_channel_interceptor_loss_params_t params;
    memset(&params, 0, sizeof(params));

    char *uri = strdup("recv-msg-mask=zzz");
    int i = aeron_udp_channel_interceptor_loss_parse_params(uri, &params);

    EXPECT_EQ(i, -1);
}
