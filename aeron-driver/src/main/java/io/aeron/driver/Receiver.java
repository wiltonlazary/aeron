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

import io.aeron.driver.media.DataTransportPoller;
import io.aeron.driver.media.ReceiveChannelEndpoint;
import io.aeron.driver.media.ReceiveDestinationTransport;
import io.aeron.driver.media.UdpChannel;
import org.agrona.CloseHelper;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.status.AtomicCounter;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;

import static io.aeron.driver.Configuration.PENDING_SETUPS_TIMEOUT_NS;
import static io.aeron.driver.status.SystemCounterDescriptor.BYTES_RECEIVED;
import static io.aeron.driver.status.SystemCounterDescriptor.RESOLUTION_CHANGES;

/**
 * Receiver agent for JVM based media driver, uses an event loop with command buffer
 */
public class Receiver implements Agent
{
    private final DataTransportPoller dataTransportPoller;
    private final OneToOneConcurrentArrayQueue<Runnable> commandQueue;
    private final AtomicCounter totalBytesReceived;
    private final AtomicCounter resolutionChanges;
    private final NanoClock nanoClock;
    private final ArrayList<PublicationImage> publicationImages = new ArrayList<>();
    private final ArrayList<PendingSetupMessageFromSource> pendingSetupMessages = new ArrayList<>();
    private final DriverConductorProxy conductorProxy;
    private final long reResolutionCheckIntervalNs;
    private long reResolutionDeadlineNs;

    public Receiver(final MediaDriver.Context ctx)
    {
        dataTransportPoller = ctx.dataTransportPoller();
        commandQueue = ctx.receiverCommandQueue();
        totalBytesReceived = ctx.systemCounters().get(BYTES_RECEIVED);
        resolutionChanges = ctx.systemCounters().get(RESOLUTION_CHANGES);
        nanoClock = ctx.cachedNanoClock();
        conductorProxy = ctx.driverConductorProxy();
        reResolutionCheckIntervalNs = ctx.reResolutionCheckIntervalNs();
        reResolutionDeadlineNs = nanoClock.nanoTime() + reResolutionCheckIntervalNs;
    }

    public void onClose()
    {
        dataTransportPoller.close();
    }

    public String roleName()
    {
        return "receiver";
    }

    public int doWork()
    {
        int workCount = commandQueue.drain(Runnable::run, Configuration.COMMAND_DRAIN_LIMIT);
        final int bytesReceived = dataTransportPoller.pollTransports();
        totalBytesReceived.getAndAddOrdered(bytesReceived);
        final long nowNs = nanoClock.nanoTime();

        final ArrayList<PublicationImage> publicationImages = this.publicationImages;
        for (int lastIndex = publicationImages.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final PublicationImage image = publicationImages.get(i);
            if (image.hasActivityAndNotEndOfStream(nowNs))
            {
                workCount += image.sendPendingStatusMessage();
                workCount += image.processPendingLoss();
                workCount += image.initiateAnyRttMeasurements(nowNs);
            }
            else
            {
                ArrayListUtil.fastUnorderedRemove(publicationImages, i, lastIndex--);
                image.removeFromDispatcher();
            }
        }

        checkPendingSetupMessages(nowNs);

        if (reResolutionCheckIntervalNs > 0 && (reResolutionDeadlineNs - nowNs) < 0)
        {
            dataTransportPoller.checkForReResolutions(nowNs, conductorProxy);

            reResolutionDeadlineNs = nowNs + reResolutionCheckIntervalNs;
        }

        return workCount + bytesReceived;
    }

    public void addPendingSetupMessage(
        final int sessionId,
        final int streamId,
        final int transportIndex,
        final ReceiveChannelEndpoint channelEndpoint,
        final boolean periodic,
        final InetSocketAddress controlAddress)
    {
        final PendingSetupMessageFromSource cmd = new PendingSetupMessageFromSource(
            sessionId, streamId, transportIndex, channelEndpoint, periodic, controlAddress);

        cmd.timeOfStatusMessageNs(nanoClock.nanoTime());
        pendingSetupMessages.add(cmd);
    }

    public void onAddSubscription(final ReceiveChannelEndpoint channelEndpoint, final int streamId)
    {
        channelEndpoint.addSubscription(streamId);
    }

    public void onAddSubscription(
        final ReceiveChannelEndpoint channelEndpoint, final int streamId, final int sessionId)
    {
        channelEndpoint.addSubscription(streamId, sessionId);
    }

    public void onRemoveSubscription(final ReceiveChannelEndpoint channelEndpoint, final int streamId)
    {
        channelEndpoint.removeSubscription(streamId);
    }

    public void onRemoveSubscription(
        final ReceiveChannelEndpoint channelEndpoint, final int streamId, final int sessionId)
    {
        channelEndpoint.removeSubscription(streamId, sessionId);
    }

    public void onNewPublicationImage(final ReceiveChannelEndpoint channelEndpoint, final PublicationImage image)
    {
        publicationImages.add(image);
        channelEndpoint.addPublicationImage(image);
    }

    public void onRegisterReceiveChannelEndpoint(final ReceiveChannelEndpoint channelEndpoint)
    {
        if (!channelEndpoint.hasDestinationControl())
        {
            channelEndpoint.openChannel(conductorProxy);
            channelEndpoint.registerForRead(dataTransportPoller);
            channelEndpoint.indicateActive();

            if (channelEndpoint.hasExplicitControl())
            {
                addPendingSetupMessage(0, 0, 0, channelEndpoint, true, channelEndpoint.explicitControlAddress());
                channelEndpoint.sendSetupElicitingStatusMessage(0, channelEndpoint.explicitControlAddress(), 0, 0);
            }
        }
        else
        {
            channelEndpoint.indicateActive();
        }
    }

    public void onCloseReceiveChannelEndpoint(final ReceiveChannelEndpoint channelEndpoint)
    {
        final ArrayList<PendingSetupMessageFromSource> pendingSetupMessages = this.pendingSetupMessages;
        for (int lastIndex = pendingSetupMessages.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final PendingSetupMessageFromSource pending = pendingSetupMessages.get(i);

            if (pending.channelEndpoint() == channelEndpoint)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSetupMessages, i, lastIndex--);
                pending.removeFromDataPacketDispatcher();
            }
        }

        channelEndpoint.closeMultiRcvDestination(dataTransportPoller);
        channelEndpoint.close();
    }

    public void onRemoveCoolDown(final ReceiveChannelEndpoint channelEndpoint, final int sessionId, final int streamId)
    {
        channelEndpoint.removeCoolDown(sessionId, streamId);
    }

    public void onAddDestination(
        final ReceiveChannelEndpoint channelEndpoint, final ReceiveDestinationTransport transport)
    {
        transport.openChannel(conductorProxy, channelEndpoint.statusIndicatorCounter());

        final int transportIndex = channelEndpoint.addDestination(transport);
        final SelectionKey key = dataTransportPoller.registerForRead(channelEndpoint, transport, transportIndex);
        transport.selectionKey(key);

        if (transport.hasExplicitControl())
        {
            addPendingSetupMessage(0, 0, transportIndex, channelEndpoint, true, transport.explicitControlAddress());
            channelEndpoint.sendSetupElicitingStatusMessage(transportIndex, transport.explicitControlAddress(), 0, 0);
        }

        for (final PublicationImage image : publicationImages)
        {
            if (channelEndpoint == image.channelEndpoint())
            {
                image.addDestination(transportIndex, transport);
            }
        }
    }

    public void onRemoveDestination(final ReceiveChannelEndpoint channelEndpoint, final UdpChannel udpChannel)
    {
        final int transportIndex = channelEndpoint.destination(udpChannel);

        if (ArrayUtil.UNKNOWN_INDEX != transportIndex)
        {
            final ReceiveDestinationTransport transport = channelEndpoint.destination(transportIndex);

            dataTransportPoller.cancelRead(channelEndpoint, transport);
            channelEndpoint.removeDestination(transportIndex);
            CloseHelper.close(transport);
            dataTransportPoller.selectNowWithoutProcessing();

            for (final PublicationImage image : publicationImages)
            {
                if (channelEndpoint == image.channelEndpoint())
                {
                    image.removeDestination(transportIndex);
                }
            }
        }
    }

    public void onResolutionChange(
        final ReceiveChannelEndpoint channelEndpoint, final UdpChannel channel, final InetSocketAddress newAddress)
    {
        final int transportIndex = channelEndpoint.hasDestinationControl() ?
            channelEndpoint.destination(channel) : 0;

        for (final PendingSetupMessageFromSource pending : this.pendingSetupMessages)
        {
            if (pending.channelEndpoint() == channelEndpoint &&
                pending.isPeriodic() &&
                pending.transportIndex() == transportIndex)
            {
                pending.controlAddress(newAddress);
                resolutionChanges.getAndAddOrdered(1);
            }
        }

        channelEndpoint.updateControlAddress(transportIndex, newAddress);
    }

    private void checkPendingSetupMessages(final long nowNs)
    {
        final ArrayList<PendingSetupMessageFromSource> pendingSetupMessages = this.pendingSetupMessages;
        for (int lastIndex = pendingSetupMessages.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final PendingSetupMessageFromSource pending = pendingSetupMessages.get(i);

            if ((pending.timeOfStatusMessageNs() + PENDING_SETUPS_TIMEOUT_NS) - nowNs < 0)
            {
                if (!pending.isPeriodic())
                {
                    ArrayListUtil.fastUnorderedRemove(pendingSetupMessages, i, lastIndex--);
                    pending.removeFromDataPacketDispatcher();
                }
                else if (pending.shouldElicitSetupMessage())
                {
                    pending.channelEndpoint().sendSetupElicitingStatusMessage(
                        pending.transportIndex(), pending.controlAddress(), pending.sessionId(), pending.streamId());
                    pending.timeOfStatusMessageNs(nowNs);
                }
            }
        }
    }
}
