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
package io.aeron.driver;

import io.aeron.driver.media.UdpChannel;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.status.CountersManager;

import java.net.InetSocketAddress;

/**
 * Congestion control algorithm which uses the min of {@link MediaDriver.Context#initialWindowLength()} or half a term
 * length as a static window.
 */
public class StaticWindowCongestionControl implements CongestionControl
{
    /**
     * URI param value to identify this {@link CongestionControl} strategy.
     */
    public static final String CC_PARAM_VALUE = "static";

    private final long ccOutcome;

    public StaticWindowCongestionControl(
        final long registrationId,
        final UdpChannel udpChannel,
        final int streamId,
        final int sessionId,
        final int termLength,
        final int senderMtuLength,
        final InetSocketAddress controlAddress,
        final InetSocketAddress sourceAddress,
        final NanoClock clock,
        final MediaDriver.Context context,
        final CountersManager countersManager)
    {
        ccOutcome = CongestionControl.packOutcome(Math.min(termLength >> 1, context.initialWindowLength()), false);
    }

    public void close()
    {
    }

    public boolean shouldMeasureRtt(final long nowNs)
    {
        return false;
    }

    public void onRttMeasurementSent(final long nowNs)
    {
    }

    public void onRttMeasurement(final long nowNs, final long rttNs, final InetSocketAddress srcAddress)
    {
    }

    public long onTrackRebuild(
        final long nowNs,
        final long newConsumptionPosition,
        final long lastSmPosition,
        final long hwmPosition,
        final long startingRebuildPosition,
        final long endingRebuildPosition,
        final boolean lossOccurred)
    {
        return ccOutcome;
    }

    public int initialWindowLength()
    {
        return CongestionControl.receiverWindowLength(ccOutcome);
    }
}
