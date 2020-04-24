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
package io.aeron.agent;

import io.aeron.cluster.ConsensusModule;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

import static io.aeron.agent.ClusterEventCode.EVENT_CODE_TYPE;
import static io.aeron.agent.ClusterEventCode.NEW_LEADERSHIP_TERM;
import static io.aeron.agent.ClusterEventEncoder.*;
import static io.aeron.agent.CommonEventEncoder.captureLength;
import static io.aeron.agent.CommonEventEncoder.encodedLength;
import static io.aeron.agent.EventConfiguration.EVENT_RING_BUFFER;

/**
 * Event logger interface used by interceptors for recording cluster events into a {@link RingBuffer} for a
 * {@link ConsensusModule} events via a Java Agent.
 */
public final class ClusterEventLogger
{
    public static final ClusterEventLogger LOGGER = new ClusterEventLogger(EVENT_RING_BUFFER);

    private final ManyToOneRingBuffer ringBuffer;

    ClusterEventLogger(final ManyToOneRingBuffer eventRingBuffer)
    {
        ringBuffer = eventRingBuffer;
    }

    public void logNewLeadershipTerm(
        final long logLeadershipTermId,
        final long logTruncatePosition,
        final long leadershipTermId,
        final long logPosition,
        final long timestamp,
        final int leaderMemberId,
        final int logSessionId,
        final boolean isStartup)
    {
        final int length = newLeaderShipTermLength();
        final int captureLength = captureLength(length);
        final int encodedLength = encodedLength(captureLength);
        final int index = ringBuffer.tryClaim(toEventCodeId(NEW_LEADERSHIP_TERM), encodedLength);

        if (index > 0)
        {
            try
            {
                encodeNewLeadershipTerm(
                    (UnsafeBuffer)ringBuffer.buffer(),
                    index,
                    captureLength,
                    length,
                    logLeadershipTermId,
                    logTruncatePosition,
                    leadershipTermId,
                    logPosition,
                    timestamp,
                    leaderMemberId,
                    logSessionId,
                    isStartup);
            }
            finally
            {
                ringBuffer.commit(index);
            }
        }
    }

    public <T extends Enum<T>> void logStateChange(
        final ClusterEventCode eventCode, final T oldState, final T newState, final int memberId)
    {
        final int length = stateChangeLength(oldState, newState);
        final int captureLength = captureLength(length);
        final int encodedLength = encodedLength(captureLength);
        final int index = ringBuffer.tryClaim(toEventCodeId(eventCode), encodedLength);

        if (index > 0)
        {
            try
            {
                encodeStateChange(
                    (UnsafeBuffer)ringBuffer.buffer(),
                    index,
                    captureLength,
                    length,
                    oldState,
                    newState,
                    memberId);
            }
            finally
            {
                ringBuffer.commit(index);
            }
        }
    }

    public static int toEventCodeId(final ClusterEventCode eventCode)
    {
        return EVENT_CODE_TYPE << 16 | (eventCode.id() & 0xFFFF);
    }
}
