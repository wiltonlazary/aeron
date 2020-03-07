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

import io.aeron.ChannelUri;
import io.aeron.driver.media.ControlTransportPoller;
import io.aeron.driver.media.SendChannelEndpoint;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.net.InetSocketAddress;

import static io.aeron.driver.status.SystemCounterDescriptor.BYTES_SENT;
import static io.aeron.driver.status.SystemCounterDescriptor.RESOLUTION_CHANGES;

class SenderLhsPadding
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class SenderHotFields extends SenderLhsPadding
{
    protected long controlPollDeadlineNs;
    protected long reResolutionDeadlineNs;
    protected int dutyCycleCounter;
    protected int roundRobinIndex = 0;
}

class SenderRhsPadding extends SenderHotFields
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

/**
 * Agent that iterates over {@link NetworkPublication}s for sending them to registered subscribers.
 */
public class Sender extends SenderRhsPadding implements Agent
{
    private static final NetworkPublication[] EMPTY_PUBLICATIONS = new NetworkPublication[0];

    private final long statusMessageReadTimeoutNs;
    private final long reResolutionCheckIntervalNs;
    private final int dutyCycleRatio;
    private final ControlTransportPoller controlTransportPoller;
    private final OneToOneConcurrentArrayQueue<Runnable> commandQueue;
    private final AtomicCounter totalBytesSent;
    private final AtomicCounter resolutionChanges;
    private final NanoClock nanoClock;
    private final DriverConductorProxy conductorProxy;

    private NetworkPublication[] networkPublications = EMPTY_PUBLICATIONS;

    public Sender(final MediaDriver.Context ctx)
    {
        this.controlTransportPoller = ctx.controlTransportPoller();
        this.commandQueue = ctx.senderCommandQueue();
        this.totalBytesSent = ctx.systemCounters().get(BYTES_SENT);
        this.resolutionChanges = ctx.systemCounters().get(RESOLUTION_CHANGES);
        this.nanoClock = ctx.cachedNanoClock();
        this.statusMessageReadTimeoutNs = ctx.statusMessageTimeoutNs() >> 1;
        this.reResolutionCheckIntervalNs = ctx.reResolutionCheckIntervalNs();
        this.dutyCycleRatio = ctx.sendToStatusMessagePollRatio();
        this.conductorProxy = ctx.driverConductorProxy();
        this.reResolutionDeadlineNs = nanoClock.nanoTime() + reResolutionCheckIntervalNs;
    }

    public void onClose()
    {
        controlTransportPoller.close();
    }

    public int doWork()
    {
        final int workCount = commandQueue.drain(Runnable::run, Configuration.COMMAND_DRAIN_LIMIT);
        final long nowNs = nanoClock.nanoTime();
        final int bytesSent = doSend(nowNs);

        int bytesReceived = 0;
        if (0 == bytesSent || ++dutyCycleCounter >= dutyCycleRatio || (controlPollDeadlineNs - nowNs < 0))
        {
            bytesReceived = controlTransportPoller.pollTransports();

            dutyCycleCounter = 0;
            controlPollDeadlineNs = nowNs + statusMessageReadTimeoutNs;
        }

        if (reResolutionCheckIntervalNs > 0 && (reResolutionDeadlineNs - nowNs) < 0)
        {
            controlTransportPoller.checkForReResolutions(nowNs, conductorProxy);

            reResolutionDeadlineNs = nowNs + reResolutionCheckIntervalNs;
        }

        return workCount + bytesSent + bytesReceived;
    }

    public String roleName()
    {
        return "sender";
    }

    public void onRegisterSendChannelEndpoint(final SendChannelEndpoint channelEndpoint)
    {
        channelEndpoint.openChannel(conductorProxy);
        channelEndpoint.registerForRead(controlTransportPoller);
        channelEndpoint.indicateActive();
    }

    public void onCloseSendChannelEndpoint(final SendChannelEndpoint channelEndpoint)
    {
        channelEndpoint.close();
    }

    public void onNewNetworkPublication(final NetworkPublication publication)
    {
        networkPublications = ArrayUtil.add(networkPublications, publication);
        publication.channelEndpoint().registerForSend(publication);
    }

    public void onRemoveNetworkPublication(final NetworkPublication publication)
    {
        networkPublications = ArrayUtil.remove(networkPublications, publication);
        publication.channelEndpoint().unregisterForSend(publication);
        publication.senderRelease();
    }

    public void onAddDestination(
        final SendChannelEndpoint channelEndpoint, final ChannelUri channelUri, final InetSocketAddress address)
    {
        channelEndpoint.addDestination(channelUri, address);
    }

    public void onRemoveDestination(
        final SendChannelEndpoint channelEndpoint, final ChannelUri channelUri, final InetSocketAddress address)
    {
        channelEndpoint.removeDestination(channelUri, address);
    }

    public void onResolutionChange(
        final SendChannelEndpoint channelEndpoint, final String endpoint, final InetSocketAddress newAddress)
    {
        channelEndpoint.resolutionChange(endpoint, newAddress);
        resolutionChanges.getAndAddOrdered(1);
    }

    private int doSend(final long nowNs)
    {
        int bytesSent = 0;
        final NetworkPublication[] publications = this.networkPublications;
        final int length = publications.length;

        int startingIndex = roundRobinIndex++;
        if (startingIndex >= length)
        {
            roundRobinIndex = startingIndex = 0;
        }

        for (int i = startingIndex; i < length; i++)
        {
            bytesSent += publications[i].send(nowNs);
        }

        for (int i = 0; i < startingIndex; i++)
        {
            bytesSent += publications[i].send(nowNs);
        }

        totalBytesSent.getAndAddOrdered(bytesSent);

        return bytesSent;
    }
}
