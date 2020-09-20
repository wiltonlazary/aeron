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

#ifndef AERON_C_COUNTERS_H
#define AERON_C_COUNTERS_H

// Driver counters

#define AERON_COUNTER_SYSTEM_COUNTER_TYPE_ID (0)

#define AERON_COUNTER_PUBLISHER_LIMIT_NAME "pub-lmt"
#define AERON_COUNTER_PUBLISHER_LIMIT_TYPE_ID (1)

#define AERON_COUNTER_SENDER_POSITION_NAME "snd-pos"
#define AERON_COUNTER_SENDER_POSITION_TYPE_ID (2)

#define AERON_COUNTER_RECEIVER_HWM_NAME "rcv-hwm"
#define AERON_COUNTER_RECEIVER_HWM_TYPE_ID (3)

#define AERON_COUNTER_SUBSCRIPTION_POSITION_NAME "sub-pos"
#define AERON_COUNTER_SUBSCRIPTION_POSITION_TYPE_ID (4)

#define AERON_COUNTER_RECEIVER_POSITION_NAME "rcv-pos"
#define AERON_COUNTER_RECEIVER_POSITION_TYPE_ID (5)

#define AERON_COUNTER_SEND_CHANNEL_STATUS_NAME "snd-channel"
#define AERON_COUNTER_SEND_CHANNEL_STATUS_TYPE_ID (6)

#define AERON_COUNTER_RECEIVE_CHANNEL_STATUS_NAME "rcv-channel"
#define AERON_COUNTER_RECEIVE_CHANNEL_STATUS_TYPE_ID (7)

#define AERON_COUNTER_SENDER_LIMIT_NAME "snd-lmt"
#define AERON_COUNTER_SENDER_LIMIT_TYPE_ID (9)

#define AERON_COUNTER_PER_IMAGE_TYPE_ID (10)

#define AERON_COUNTER_CLIENT_HEARTBEAT_TIMESTAMP_NAME "client-heartbeat"
#define AERON_COUNTER_CLIENT_HEARTBEAT_TIMESTAMP_TYPE_ID (11)

#define AERON_COUNTER_PUBLISHER_POSITION_NAME "pub-pos (sampled)"
#define AERON_COUNTER_PUBLISHER_POSITION_TYPE_ID (12)

#define AERON_COUNTER_SENDER_BPE_NAME "snd-bpe"
#define AERON_COUNTER_SENDER_BPE_TYPE_ID  (13)

#define AERON_COUNTER_RCV_LOCAL_SOCKADDR_NAME "rcv-local-sockaddr"
#define AERON_COUNTER_SND_LOCAL_SOCKADDR_NAME "snd-local-sockaddr"
#define AERON_COUNTER_LOCAL_SOCKADDR_TYPE_ID (14)

// Archive counters

#define AERON_COUNTER_ARCHIVE_RECORDING_POSITION_TYPE_ID (100);

#define AERON_COUNTER_ARCHIVE_ERROR_COUNT_TYPE_ID (101);

#define AERON_COUNTER_ARCHIVE_CONTROL_SESSIONS_TYPE_ID (102);

// Cluster counters

#define AERON_COUNTER_CLUSTER_CONSENSUS_MODULE_STATE_TYPE_ID (200)

#define AERON_COUNTER_CLUSTER_NODE_ROLE_TYPE_ID (201)

#define AERON_COUNTER_CLUSTER_CONTROL_TOGGLE_TYPE_ID (202)

#define AERON_COUNTER_CLUSTER_COMMIT_POSITION_TYPE_ID (203)

#define AERON_COUNTER_CLUSTER_RECOVERY_STATE_TYPE_ID (204)

#define AERON_COUNTER_CLUSTER_SNAPSHOT_COUNTER_TYPE_ID (205)

#define AERON_COUNTER_CLUSTER_ELECTION_STATE_TYPE_ID (207)

#define AERON_COUNTER_CLUSTER_BACKUP_STATE_TYPE_ID (208)

#define AERON_COUNTER_CLUSTER_BACKUP_LIVE_LOG_POSITION_TYPE_ID (209)

#define AERON_COUNTER_CLUSTER_BACKUP_QUERY_DEADLINE_TYPE_ID (210)

#define AERON_COUNTER_CLUSTER_BACKUP_ERROR_COUNT_TYPE_ID (211)

#define AERON_COUNTER_CLUSTER_CONSENSUS_MODULE_ERROR_COUNT_TYPE_ID (212)

#define AERON_COUNTER_CLUSTER_CLIENT_TIMEOUT_COUNT_TYPE_ID (213)

#define AERON_COUNTER_CLUSTER_INVALID_REQUEST_COUNT_TYPE_ID (214)

#define AERON_COUNTER_CLUSTER_CLUSTERED_SERVICE_ERROR_COUNT_TYPE_ID (215)

#endif //AERON_C_COUNTERS_H
