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
package io.aeron.driver.media;

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.driver.DriverConductorProxy;
import io.aeron.protocol.StatusMessageFlyweight;
import org.agrona.concurrent.CachedNanoClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import static io.aeron.driver.media.UdpChannelTransport.sendError;

abstract class MultiSndDestination
{
    static final Destination[] EMPTY_DESTINATIONS = new Destination[0];

    abstract int send(DatagramChannel channel, ByteBuffer buffer, SendChannelEndpoint channelEndpoint, int bytesToSend);

    abstract void onStatusMessage(StatusMessageFlyweight msg, InetSocketAddress address);

    void addDestination(final ChannelUri channelUri, final InetSocketAddress address)
    {
    }

    void removeDestination(final ChannelUri channelUri, final InetSocketAddress address)
    {
    }

    void checkForReResolution(
        final SendChannelEndpoint channelEndpoint, final long nowNs, final DriverConductorProxy conductorProxy)
    {
    }

    void updateDestination(final String endpoint, final InetSocketAddress newAddress)
    {
    }

    static int send(
        final DatagramChannel datagramChannel,
        final ByteBuffer buffer,
        final SendChannelEndpoint channelEndpoint,
        final int bytesToSend,
        final int position,
        final InetSocketAddress destination)
    {
        int bytesSent = 0;
        try
        {
            if (datagramChannel.isOpen())
            {
                buffer.position(position);
                channelEndpoint.sendHook(buffer, destination);
                bytesSent = datagramChannel.send(buffer, destination);
            }
        }
        catch (final PortUnreachableException ignore)
        {
        }
        catch (final IOException ex)
        {
            sendError(bytesToSend, ex, destination);
        }

        return bytesSent;
    }
}

class ManualSndMultiDestination extends MultiSndDestination
{
    private final long destinationTimeoutNs;
    private final CachedNanoClock nanoClock;
    private Destination[] destinations = EMPTY_DESTINATIONS;

    ManualSndMultiDestination(final CachedNanoClock nanoClock, final long destinationTimeoutNs)
    {
        this.destinationTimeoutNs = destinationTimeoutNs;
        this.nanoClock = nanoClock;
    }

    void onStatusMessage(final StatusMessageFlyweight msg, final InetSocketAddress address)
    {
        final long receiverId = msg.receiverId();
        final long nowNs = nanoClock.nanoTime();

        for (final Destination destination : destinations)
        {
            if (destination.isReceiverIdValid &&
                receiverId == destination.receiverId &&
                address.getPort() == destination.port)
            {
                destination.timeOfLastActivityNs = nowNs;
                break;
            }
            else if (!destination.isReceiverIdValid &&
                address.getPort() == destination.port &&
                address.getAddress().equals(destination.address.getAddress()))
            {
                destination.timeOfLastActivityNs = nowNs;
                destination.receiverId = receiverId;
                destination.isReceiverIdValid = true;
                break;
            }
        }
    }

    int send(
        final DatagramChannel channel,
        final ByteBuffer buffer,
        final SendChannelEndpoint channelEndpoint,
        final int bytesToSend)
    {
        final int position = buffer.position();
        int minBytesSent = bytesToSend;

        for (final Destination destination : destinations)
        {
            minBytesSent = Math.min(
                minBytesSent, send(channel, buffer, channelEndpoint, bytesToSend, position, destination.address));
        }

        return minBytesSent;
    }

    void addDestination(final ChannelUri channelUri, final InetSocketAddress address)
    {
        final int length = destinations.length;
        final Destination[] newElements = new Destination[length + 1];

        System.arraycopy(destinations, 0, newElements, 0, length);
        newElements[length] = new Destination(nanoClock.nanoTime(), channelUri, address);
        destinations = newElements;
    }

    void removeDestination(final ChannelUri channelUri, final InetSocketAddress address)
    {
        boolean found = false;
        int index = 0;
        for (final Destination destination : destinations)
        {
            if (destination.address.equals(address))
            {
                found = true;
                break;
            }

            index++;
        }

        if (found)
        {
            final Destination[] oldElements = destinations;
            final int length = oldElements.length;
            final int newLength = length - 1;

            if (0 == newLength)
            {
                destinations = EMPTY_DESTINATIONS;
            }
            else
            {
                final Destination[] newElements = new Destination[newLength];

                for (int i = 0, j = 0; i < length; i++)
                {
                    if (index != i)
                    {
                        newElements[j++] = oldElements[i];
                    }
                }

                destinations = newElements;
            }
        }
    }

    void checkForReResolution(
        final SendChannelEndpoint channelEndpoint, final long nowNs, final DriverConductorProxy conductorProxy)
    {
        for (final Destination destination : destinations)
        {
            if (nowNs > (destination.timeOfLastActivityNs + destinationTimeoutNs))
            {
                final String endpoint = destination.channelUri.get(CommonContext.ENDPOINT_PARAM_NAME);
                final InetSocketAddress address = destination.address;

                conductorProxy.reResolveEndpoint(endpoint, channelEndpoint, address);
                destination.timeOfLastActivityNs = nowNs;
            }
        }
    }

    void updateDestination(final String endpoint, final InetSocketAddress newAddress)
    {
        for (final Destination destination : destinations)
        {
            if (endpoint.equals(destination.channelUri.get(CommonContext.ENDPOINT_PARAM_NAME)))
            {
                destination.address = newAddress;
                destination.port = newAddress.getPort();
            }
        }
    }
}

class DynamicSndMultiDestination extends MultiSndDestination
{
    private final long destinationTimeoutNs;
    private final CachedNanoClock nanoClock;
    private Destination[] destinations = EMPTY_DESTINATIONS;

    DynamicSndMultiDestination(final CachedNanoClock nanoClock, final long destinationTimeoutNs)
    {
        this.nanoClock = nanoClock;
        this.destinationTimeoutNs = destinationTimeoutNs;
    }

    void onStatusMessage(final StatusMessageFlyweight msg, final InetSocketAddress address)
    {
        final long receiverId = msg.receiverId();
        final long nowNs = nanoClock.nanoTime();
        boolean isExisting = false;

        for (final Destination destination : destinations)
        {
            if (receiverId == destination.receiverId && address.getPort() == destination.port)
            {
                destination.timeOfLastActivityNs = nowNs;
                isExisting = true;
                break;
            }
        }

        if (!isExisting)
        {
            add(new Destination(nowNs, receiverId, address));
        }
    }

    int send(
        final DatagramChannel channel,
        final ByteBuffer buffer,
        final SendChannelEndpoint channelEndpoint,
        final int bytesToSend)
    {
        final long nowNs = nanoClock.nanoTime();
        final int position = buffer.position();
        int minBytesSent = bytesToSend;
        int removed = 0;

        for (int lastIndex = destinations.length - 1, i = lastIndex; i >= 0; i--)
        {
            final Destination destination = destinations[i];
            if ((destination.timeOfLastActivityNs + destinationTimeoutNs) - nowNs < 0)
            {
                if (i != lastIndex)
                {
                    destinations[i] = destinations[lastIndex--];
                }
                removed++;
            }
            else
            {
                minBytesSent = Math.min(
                    minBytesSent, send(channel, buffer, channelEndpoint, bytesToSend, position, destination.address));
            }
        }

        if (removed > 0)
        {
            truncateDestinations(removed);
        }

        return minBytesSent;
    }

    private void add(final Destination destination)
    {
        final int length = destinations.length;
        final Destination[] newElements = new Destination[length + 1];

        System.arraycopy(destinations, 0, newElements, 0, length);
        newElements[length] = destination;
        destinations = newElements;
    }

    private void truncateDestinations(final int removed)
    {
        final int length = destinations.length;
        final int newLength = length - removed;

        if (0 == newLength)
        {
            destinations = EMPTY_DESTINATIONS;
        }
        else
        {
            final Destination[] newElements = new Destination[newLength];
            System.arraycopy(destinations, 0, newElements, 0, newLength);
            destinations = newElements;
        }
    }
}

final class Destination
{
    long receiverId;
    long timeOfLastActivityNs;
    boolean isReceiverIdValid;
    int port;
    InetSocketAddress address;
    final ChannelUri channelUri;

    Destination(final long nowNs, final long receiverId, final InetSocketAddress address)
    {
        this.timeOfLastActivityNs = nowNs;
        this.receiverId = receiverId;
        this.isReceiverIdValid = true;
        this.channelUri = null;
        this.address = address;
        this.port = address.getPort();
    }

    Destination(final long nowMs, final ChannelUri channelUri, final InetSocketAddress address)
    {
        this.timeOfLastActivityNs = nowMs;
        this.receiverId = 0;
        this.isReceiverIdValid = false;
        this.channelUri = channelUri;
        this.address = address;
        this.port = address.getPort();
    }
}
