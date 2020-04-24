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

import io.aeron.driver.MediaDriver;
import io.aeron.driver.status.SystemCounterDescriptor;
import io.aeron.exceptions.AeronException;
import io.aeron.protocol.HeaderFlyweight;
import io.aeron.status.ChannelEndpointStatus;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

import static io.aeron.logbuffer.FrameDescriptor.frameVersion;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_SNDBUF;

/**
 * Base class for UDP channel transports which is specialised for send or receive endpoints.
 */
public abstract class UdpChannelTransport implements AutoCloseable
{
    protected final MediaDriver.Context context;
    protected final UdpChannel udpChannel;
    protected final AtomicCounter invalidPackets;
    protected final ErrorHandler errorHandler;
    protected UdpTransportPoller transportPoller;
    protected SelectionKey selectionKey;
    protected final InetSocketAddress bindAddress;
    protected final InetSocketAddress endPointAddress;
    protected InetSocketAddress connectAddress;
    protected DatagramChannel sendDatagramChannel;
    protected DatagramChannel receiveDatagramChannel;
    protected int multicastTtl = 0;
    protected boolean isClosed = false;

    public UdpChannelTransport(
        final UdpChannel udpChannel,
        final InetSocketAddress endPointAddress,
        final InetSocketAddress bindAddress,
        final InetSocketAddress connectAddress,
        final MediaDriver.Context context)
    {
        this.context = context;
        this.udpChannel = udpChannel;
        this.errorHandler = context.errorHandler();
        this.endPointAddress = endPointAddress;
        this.bindAddress = bindAddress;
        this.connectAddress = connectAddress;
        this.invalidPackets = context.systemCounters().get(SystemCounterDescriptor.INVALID_PACKETS);
    }

    /**
     * Throw a {@link AeronException} with a message for a send error.
     *
     * @param bytesToSend expected to be sent to the network.
     * @param ex          experienced.
     * @param destination to which the send was addressed.
     */
    public static void sendError(final int bytesToSend, final IOException ex, final InetSocketAddress destination)
    {
        throw new AeronException(
            "failed to send " + bytesToSend + " byte packet to " + destination, ex, AeronException.Category.WARN);
    }

    /**
     * Create the underlying channel for reading and writing.
     *
     * @param statusIndicator to set for error status
     */
    public void openDatagramChannel(final AtomicCounter statusIndicator)
    {
        try
        {
            sendDatagramChannel = DatagramChannel.open(udpChannel.protocolFamily());
            receiveDatagramChannel = sendDatagramChannel;

            if (udpChannel.isMulticast())
            {
                if (null != connectAddress)
                {
                    receiveDatagramChannel = DatagramChannel.open(udpChannel.protocolFamily());
                }

                receiveDatagramChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                receiveDatagramChannel.bind(new InetSocketAddress(endPointAddress.getPort()));
                receiveDatagramChannel.join(endPointAddress.getAddress(), udpChannel.localInterface());
                sendDatagramChannel.setOption(StandardSocketOptions.IP_MULTICAST_IF, udpChannel.localInterface());

                if (udpChannel.hasMulticastTtl())
                {
                    sendDatagramChannel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, udpChannel.multicastTtl());
                    multicastTtl = sendDatagramChannel.getOption(StandardSocketOptions.IP_MULTICAST_TTL);
                }
                else if (context.socketMulticastTtl() != 0)
                {
                    sendDatagramChannel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, context.socketMulticastTtl());
                    multicastTtl = sendDatagramChannel.getOption(StandardSocketOptions.IP_MULTICAST_TTL);
                }
            }
            else
            {
                sendDatagramChannel.bind(bindAddress);
            }

            if (null != connectAddress)
            {
                sendDatagramChannel.connect(connectAddress);
            }

            if (0 != context.socketSndbufLength())
            {
                sendDatagramChannel.setOption(SO_SNDBUF, context.socketSndbufLength());
            }

            if (0 != context.socketRcvbufLength())
            {
                receiveDatagramChannel.setOption(SO_RCVBUF, context.socketRcvbufLength());
            }

            sendDatagramChannel.configureBlocking(false);
            receiveDatagramChannel.configureBlocking(false);
        }
        catch (final IOException ex)
        {
            if (null != statusIndicator)
            {
                statusIndicator.setOrdered(ChannelEndpointStatus.ERRORED);
            }

            CloseHelper.quietClose(sendDatagramChannel);
            if (receiveDatagramChannel != sendDatagramChannel)
            {
                CloseHelper.quietClose(receiveDatagramChannel);
            }

            sendDatagramChannel = null;
            receiveDatagramChannel = null;

            throw new AeronException(
                "channel error - " + ex.getMessage() +
                " (at " + ex.getStackTrace()[0].toString() + "): " +
                udpChannel.originalUriString(), ex);
        }
    }

    /**
     * Register this transport for reading from a {@link UdpTransportPoller}.
     *
     * @param transportPoller to register read with
     */
    public void registerForRead(final UdpTransportPoller transportPoller)
    {
        this.transportPoller = transportPoller;
        selectionKey = transportPoller.registerForRead(this);
    }

    /**
     * Return underlying {@link UdpChannel}
     *
     * @return underlying channel
     */
    public UdpChannel udpChannel()
    {
        return udpChannel;
    }

    /**
     * The {@link DatagramChannel} for this transport channel.
     *
     * @return {@link DatagramChannel} for this transport channel.
     */
    public DatagramChannel receiveDatagramChannel()
    {
        return receiveDatagramChannel;
    }

    /**
     * Get the multicast TTL value for sending datagrams on the channel.
     *
     * @return the multicast TTL value for sending datagrams on the channel.
     */
    public int multicastTtl()
    {
        return multicastTtl;
    }

    /**
     * Get the bind address and port in endpoint-style format (ip:port).
     * <p>
     * Must be called after the channel is opened.
     *
     * @return the bind address and port in endpoint-style format (ip:port).
     */
    public String bindAddressAndPort()
    {
        try
        {
            final InetSocketAddress localAddress = (InetSocketAddress)receiveDatagramChannel.getLocalAddress();
            if (null == localAddress)
            {
                return "";
            }

            return localAddress.getAddress().getHostAddress() + ":" + localAddress.getPort();
        }
        catch (final IOException ex)
        {
            return "";
        }
    }

    /**
     * Close transport, canceling any pending read operations and closing channel.
     */
    public void close()
    {
        if (!isClosed)
        {
            isClosed = true;
            if (null != selectionKey)
            {
                selectionKey.cancel();
            }

            if (null != transportPoller)
            {
                transportPoller.cancelRead(this);
                transportPoller.selectNowWithoutProcessing();
            }

            CloseHelper.close(errorHandler, sendDatagramChannel);

            if (receiveDatagramChannel != sendDatagramChannel && null != receiveDatagramChannel)
            {
                CloseHelper.close(errorHandler, receiveDatagramChannel);
            }

            if (null != transportPoller)
            {
                transportPoller.selectNowWithoutProcessing();
            }
        }
    }

    /**
     * Is transport representing a multicast media or unicast
     *
     * @return if transport is multicast media
     */
    public boolean isMulticast()
    {
        return udpChannel.isMulticast();
    }

    /**
     * Is the received frame valid. This method will do some basic checks on the header and can be
     * overridden in a subclass for further validation.
     *
     * @param buffer containing the frame.
     * @param length of the frame.
     * @return true if the frame is believed valid otherwise false.
     */
    public boolean isValidFrame(final UnsafeBuffer buffer, final int length)
    {
        boolean isFrameValid = true;

        if (frameVersion(buffer, 0) != HeaderFlyweight.CURRENT_VERSION)
        {
            isFrameValid = false;
            invalidPackets.increment();
        }
        else if (length < HeaderFlyweight.MIN_HEADER_LENGTH)
        {
            isFrameValid = false;
            invalidPackets.increment();
        }

        return isFrameValid;
    }

    @SuppressWarnings("unused")
    public void sendHook(final ByteBuffer buffer, final InetSocketAddress address)
    {
    }

    @SuppressWarnings("unused")
    public void receiveHook(final UnsafeBuffer buffer, final int length, final InetSocketAddress address)
    {
    }

    /**
     * Receive a datagram from the media layer.
     *
     * @param buffer into which the datagram will be received.
     * @return the source address of the datagram if one is available otherwise false.
     */
    public InetSocketAddress receive(final ByteBuffer buffer)
    {
        buffer.clear();

        InetSocketAddress address = null;
        try
        {
            if (receiveDatagramChannel.isOpen())
            {
                address = (InetSocketAddress)receiveDatagramChannel.receive(buffer);
            }
        }
        catch (final PortUnreachableException ignored)
        {
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return address;
    }

    /**
     * Endpoint has moved to a new address. Handle this.
     *
     * @param newAddress      to send data to.
     * @param statusIndicator for the channel
     */
    public void updateEndpoint(final InetSocketAddress newAddress, final AtomicCounter statusIndicator)
    {
        try
        {
            if (null != sendDatagramChannel)
            {
                sendDatagramChannel.disconnect();
                sendDatagramChannel.connect(newAddress);
                connectAddress = newAddress;

                if (null != statusIndicator)
                {
                    statusIndicator.setOrdered(ChannelEndpointStatus.ACTIVE);
                }
            }
        }
        catch (final Exception ex)
        {
            if (null != statusIndicator)
            {
                statusIndicator.setOrdered(ChannelEndpointStatus.ERRORED);
            }

            throw new AeronException(
                "re-resolve endpoint channel error - " + ex.getMessage() +
                    " (at " + ex.getStackTrace()[0].toString() + "): " +
                    udpChannel.originalUriString(), ex);
        }
    }
}
