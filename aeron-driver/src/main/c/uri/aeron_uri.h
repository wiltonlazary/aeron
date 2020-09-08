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

#ifndef AERON_URI_H
#define AERON_URI_H

#include "aeron_driver_common.h"
#include "aeronmd.h"

typedef struct aeron_uri_param_stct
{
    const char *key;
    const char *value;
}
aeron_uri_param_t;

typedef struct aeron_uri_params_stct
{
    size_t length;
    aeron_uri_param_t *array;
}
aeron_uri_params_t;

#define AERON_UDP_CHANNEL_RELIABLE_KEY "reliable"
#define AERON_UDP_CHANNEL_TTL_KEY "ttl"

#define AERON_UDP_CHANNEL_ENDPOINT_KEY "endpoint"
#define AERON_UDP_CHANNEL_INTERFACE_KEY "interface"
#define AERON_UDP_CHANNEL_CONTROL_KEY "control"
#define AERON_UDP_CHANNEL_CONTROL_MODE_KEY "control-mode"
#define AERON_UDP_CHANNEL_CONTROL_MODE_MANUAL_VALUE "manual"
#define AERON_UDP_CHANNEL_CONTROL_MODE_DYNAMIC_VALUE "dynamic"

#define AERON_URI_INITIAL_TERM_ID_KEY "init-term-id"
#define AERON_URI_TERM_ID_KEY "term-id"
#define AERON_URI_TERM_OFFSET_KEY "term-offset"

#define AERON_URI_TERM_LENGTH_KEY "term-length"
#define AERON_URI_LINGER_TIMEOUT_KEY "linger"
#define AERON_URI_MTU_LENGTH_KEY "mtu"
#define AERON_URI_SPARSE_TERM_KEY "sparse"
#define AERON_URI_EOS_KEY "eos"
#define AERON_URI_TETHER_KEY "tether"
#define AERON_URI_TAGS_KEY "tags"
#define AERON_URI_SESSION_ID_KEY "session-id"
#define AERON_URI_GROUP_KEY "group"
#define AERON_URI_REJOIN_KEY "rejoin"
#define AERON_URI_FC_KEY "fc"
#define AERON_URI_GTAG_KEY "gtag"
#define AERON_URI_CC_KEY "cc"
#define AERON_URI_SPIES_SIMULATE_CONNECTION_KEY "ssc"
#define AERON_URI_ATS_KEY "ats"

typedef struct aeron_uri_publication_params_stct
{
    bool has_position;
    bool is_sparse;
    bool signal_eos;
    bool spies_simulate_connection;
    size_t mtu_length;
    size_t term_length;
    size_t term_offset;
    int32_t initial_term_id;
    int32_t term_id;
    uint64_t linger_timeout_ns;
    bool has_session_id;
    int32_t session_id;
    int64_t entity_tag;
}
aeron_uri_publication_params_t;

typedef struct aeron_uri_subscription_params_stct
{
    bool is_reliable;
    bool is_sparse;
    bool is_tether;
    bool is_rejoin;
    aeron_inferable_boolean_t group;
    bool has_session_id;
    int32_t session_id;
}
aeron_uri_subscription_params_t;

typedef struct aeron_udp_channel_params_stct
{
    const char *endpoint;
    const char *bind_interface;
    const char *control;
    const char *control_mode;
    const char *channel_tag;
    const char *entity_tag;
    const char *ttl;
    aeron_uri_params_t additional_params;
}
aeron_udp_channel_params_t;

typedef struct aeron_ipc_channel_params_stct
{
    const char *channel_tag;
    const char *entity_tag;
    aeron_uri_params_t additional_params;
}
aeron_ipc_channel_params_t;

typedef enum aeron_uri_type_enum
{
    AERON_URI_UDP, AERON_URI_IPC, AERON_URI_UNKNOWN
}
aeron_uri_type_t;

typedef struct aeron_uri_stct
{
    char mutable_uri[AERON_MAX_PATH];
    aeron_uri_type_t type;

    union
    {
        aeron_udp_channel_params_t udp;
        aeron_ipc_channel_params_t ipc;
    }
    params;
}
aeron_uri_t;

typedef enum aeron_uri_ats_status_en
{
    AERON_URI_ATS_STATUS_DEFAULT,
    AERON_URI_ATS_STATUS_ENABLED,
    AERON_URI_ATS_STATUS_DISABLED
}
aeron_uri_ats_status_t;

typedef int (*aeron_uri_parse_callback_t)(void *clientd, const char *key, const char *value);

int aeron_uri_parse_params(char *uri, aeron_uri_parse_callback_t param_func, void *clientd);

int aeron_udp_uri_parse(char *uri, aeron_udp_channel_params_t *params);
int aeron_ipc_uri_parse(char *uri, aeron_ipc_channel_params_t *params);

int aeron_uri_parse(size_t uri_length, const char *uri, aeron_uri_t *params);

void aeron_uri_close(aeron_uri_t *params);

uint8_t aeron_uri_multicast_ttl(aeron_uri_t *uri);

const char *aeron_uri_find_param_value(const aeron_uri_params_t *uri_params, const char *key);
int aeron_uri_get_int64(aeron_uri_params_t *uri_params, const char *key, int64_t *retval);
int aeron_uri_get_bool(aeron_uri_params_t *uri_params, const char *key, bool *retval);
int aeron_uri_get_ats(aeron_uri_params_t *uri_params, aeron_uri_ats_status_t *uri_ats_status);

typedef struct aeron_driver_context_stct aeron_driver_context_t;
typedef struct aeron_driver_conductor_stct aeron_driver_conductor_t;

int aeron_uri_publication_params(
    aeron_uri_t *uri,
    aeron_uri_publication_params_t *params,
    aeron_driver_conductor_t *context,
    bool is_exclusive);

int aeron_uri_subscription_params(
    aeron_uri_t *uri,
    aeron_uri_subscription_params_t *params,
    aeron_driver_conductor_t *conductor);

int64_t aeron_uri_parse_tag(const char *tag_str);

#endif //AERON_URI_H
