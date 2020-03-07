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

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.ConfigurationException;
import io.aeron.driver.media.ReceiveChannelEndpoint;
import io.aeron.driver.media.SendChannelEndpoint;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.BitUtil;
import org.agrona.LangUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.*;
import org.agrona.concurrent.broadcast.BroadcastBufferDescriptor;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.agrona.concurrent.status.CountersReader;
import org.agrona.concurrent.status.StatusIndicator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;

import static io.aeron.driver.ThreadingMode.DEDICATED;
import static io.aeron.logbuffer.LogBufferDescriptor.PAGE_MAX_SIZE;
import static io.aeron.logbuffer.LogBufferDescriptor.PAGE_MIN_SIZE;
import static java.lang.Integer.getInteger;
import static java.lang.Long.getLong;
import static java.lang.System.getProperty;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.agrona.BitUtil.fromHex;
import static org.agrona.SystemUtil.*;

/**
 * Configuration options for the {@link MediaDriver}.
 */
public class Configuration
{
    /**
     * Should the driver print its configuration on start to {@link System#out}.
     */
    public static final String PRINT_CONFIGURATION_ON_START_PROP_NAME = "aeron.print.configuration";

    /**
     * Warn if the Aeron directory exists.
     */
    public static final String DIR_WARN_IF_EXISTS_PROP_NAME = "aeron.dir.warn.if.exists";

    /**
     * Should driver attempt to an immediate forced delete of {@link CommonContext#AERON_DIR_PROP_NAME} on start
     * if it exists.
     */
    public static final String DIR_DELETE_ON_START_PROP_NAME = "aeron.dir.delete.on.start";

    /**
     * Should driver attempt to delete {@link CommonContext#AERON_DIR_PROP_NAME} on shutdown.
     */
    public static final String DIR_DELETE_ON_SHUTDOWN_PROP_NAME = "aeron.dir.delete.on.shutdown";

    /**
     * Should high resolution timer be used on Windows.
     */
    public static final String USE_WINDOWS_HIGH_RES_TIMER_PROP_NAME = "aeron.use.windows.high.res.timer";

    /**
     * Property name for default boolean value for if subscriptions should have a tether for flow control.
     */
    public static final String TETHER_SUBSCRIPTIONS_PROP_NAME = "aeron.tether.subscriptions";

    /**
     * Property name for default boolean value for if a stream is reliable. True to NAK, false to gap fill.
     */
    public static final String RELIABLE_STREAM_PROP_NAME = "aeron.reliable.stream";

    /**
     * Property name for boolean value of term buffers should be created sparse.
     */
    public static final String TERM_BUFFER_SPARSE_FILE_PROP_NAME = "aeron.term.buffer.sparse.file";

    /**
     * Property name for default boolean value for if subscriptions should be considered a group member or individual.
     */
    public static final String GROUP_RECEIVER_CONSIDERATION_PROP_NAME = "aeron.receiver.group.consideration";

    /**
     * Property name for page size to align all files to.
     */
    public static final String FILE_PAGE_SIZE_PROP_NAME = "aeron.file.page.size";

    /**
     * Default page size for alignment of all files.
     */
    public static final int FILE_PAGE_SIZE_DEFAULT = 4 * 1024;

    /**
     * Property name for boolean value for if storage checks should be performed when allocating files.
     */
    public static final String PERFORM_STORAGE_CHECKS_PROP_NAME = "aeron.perform.storage.checks";

    /**
     * Length (in bytes) of the log buffers for publication terms.
     */
    public static final String TERM_BUFFER_LENGTH_PROP_NAME = "aeron.term.buffer.length";

    /**
     * Default term buffer length.
     */
    public static final int TERM_BUFFER_LENGTH_DEFAULT = 16 * 1024 * 1024;

    /**
     * Property name for term buffer length (in bytes) for IPC buffers.
     */
    public static final String IPC_TERM_BUFFER_LENGTH_PROP_NAME = "aeron.ipc.term.buffer.length";

    /**
     * Default IPC term buffer length.
     */
    public static final int TERM_BUFFER_IPC_LENGTH_DEFAULT = 64 * 1024 * 1024;

    /**
     * Property name low file storage warning threshold.
     */
    public static final String LOW_FILE_STORE_WARNING_THRESHOLD_PROP_NAME = "aeron.low.file.store.warning.threshold";

    /**
     * Default value for low file storage warning threshold.
     */
    public static final long LOW_FILE_STORE_WARNING_THRESHOLD_DEFAULT = TERM_BUFFER_LENGTH_DEFAULT * 10L;

    /**
     * Length (in bytes) of the conductor buffer for control commands from the clients to the media driver conductor.
     */
    public static final String CONDUCTOR_BUFFER_LENGTH_PROP_NAME = "aeron.conductor.buffer.length";

    /**
     * Default buffer length for conductor buffers between the client and the media driver conductor.
     */
    public static final int CONDUCTOR_BUFFER_LENGTH_DEFAULT = (1024 * 1024) + RingBufferDescriptor.TRAILER_LENGTH;

    /**
     * Length (in bytes) of the broadcast buffers from the media driver to the clients.
     */
    public static final String TO_CLIENTS_BUFFER_LENGTH_PROP_NAME = "aeron.clients.buffer.length";

    /**
     * Default buffer length for broadcast buffers from the media driver and the clients.
     */
    public static final int TO_CLIENTS_BUFFER_LENGTH_DEFAULT = (1024 * 1024) + BroadcastBufferDescriptor.TRAILER_LENGTH;

    /**
     * Property name for length of the error buffer for the system counters.
     */
    public static final String COUNTERS_VALUES_BUFFER_LENGTH_PROP_NAME = "aeron.counters.buffer.length";

    /**
     * Default length of the memory mapped buffers for the system counters file.
     */
    public static final int COUNTERS_VALUES_BUFFER_LENGTH_DEFAULT = 1024 * 1024;

    /**
     * Property name for length of the memory mapped buffer for the distinct error log.
     */
    public static final String ERROR_BUFFER_LENGTH_PROP_NAME = "aeron.error.buffer.length";

    /**
     * Default buffer length for the error buffer for the media driver.
     */
    public static final int ERROR_BUFFER_LENGTH_DEFAULT = 1024 * 1024;

    /**
     * Property name for length of the memory mapped buffer for the loss report buffer.
     */
    public static final String LOSS_REPORT_BUFFER_LENGTH_PROP_NAME = "aeron.loss.report.buffer.length";

    /**
     * Default buffer length for the loss report buffer.
     */
    public static final int LOSS_REPORT_BUFFER_LENGTH_DEFAULT = 1024 * 1024;

    /**
     * Property name for length of the initial window which must be sufficient for Bandwidth Delay Produce (BDP).
     */
    public static final String INITIAL_WINDOW_LENGTH_PROP_NAME = "aeron.rcv.initial.window.length";

    /**
     * Default initial window length for flow control sender to receiver purposes.
     * <p>
     * Length of Initial Window:
     * <p>
     * RTT (LAN) = 100 usec
     * Throughput = 10 Gbps
     * <p>
     * Buffer = Throughput * RTT
     * Buffer = (10 * 1000 * 1000 * 1000 / 8) * 0.0001 = 125000
     * Round to 128KB
     */
    public static final int INITIAL_WINDOW_LENGTH_DEFAULT = 128 * 1024;

    /**
     * Property name for status message timeout in nanoseconds after which one will be sent.
     */
    public static final String STATUS_MESSAGE_TIMEOUT_PROP_NAME = "aeron.rcv.status.message.timeout";

    /**
     * Max timeout between SMs.
     */
    public static final long STATUS_MESSAGE_TIMEOUT_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(200);

    /**
     * Property name for ratio of sending data to polling status messages in the Sender.
     */
    public static final String SEND_TO_STATUS_POLL_RATIO_PROP_NAME = "aeron.send.to.status.poll.ratio";

    /**
     * The ratio for sending data to polling status messages in the Sender.
     */
    public static final int SEND_TO_STATUS_POLL_RATIO_DEFAULT = 6;

    /**
     * Property name for SO_RCVBUF setting on UDP sockets which must be sufficient for Bandwidth Delay Produce (BDP).
     */
    public static final String SOCKET_RCVBUF_LENGTH_PROP_NAME = "aeron.socket.so_rcvbuf";

    /**
     * Default SO_RCVBUF length.
     */
    public static final int SOCKET_RCVBUF_LENGTH_DEFAULT = 128 * 1024;

    /**
     * Property name for SO_SNDBUF setting on UDP sockets which must be sufficient for Bandwidth Delay Produce (BDP).
     */
    public static final String SOCKET_SNDBUF_LENGTH_PROP_NAME = "aeron.socket.so_sndbuf";

    /**
     * Default SO_SNDBUF length.
     */
    public static final int SOCKET_SNDBUF_LENGTH_DEFAULT = 0;

    /**
     * Property name for IP_MULTICAST_TTL setting on UDP sockets.
     */
    public static final String SOCKET_MULTICAST_TTL_PROP_NAME = "aeron.socket.multicast.ttl";

    /**
     * Multicast TTL value, 0 means use OS default.
     */
    public static final int SOCKET_MULTICAST_TTL_DEFAULT = 0;

    /**
     * Property name for linger timeout after draining on {@link Publication}s.
     */
    public static final String PUBLICATION_LINGER_PROP_NAME = "aeron.publication.linger.timeout";

    /**
     * Default time for {@link Publication}s to linger after draining and before cleanup in nanoseconds.
     */
    public static final long PUBLICATION_LINGER_DEFAULT_NS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Property name for {@link Aeron} client liveness timeout after which it is considered not alive.
     */
    public static final String CLIENT_LIVENESS_TIMEOUT_PROP_NAME = "aeron.client.liveness.timeout";

    /**
     * Default timeout for client liveness timeout after which it is considered not alive.
     */
    public static final long CLIENT_LIVENESS_TIMEOUT_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(5000);

    /**
     * Property name for {@link Image} liveness timeout for how long it lingers around after being drained.
     */
    public static final String IMAGE_LIVENESS_TIMEOUT_PROP_NAME = "aeron.image.liveness.timeout";

    /**
     * Default timeout for {@link Image} liveness timeout for how long it lingers around after being drained.
     */
    public static final long IMAGE_LIVENESS_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(10);

    /**
     * Property name for window limit on {@link Publication} side.
     */
    public static final String PUBLICATION_TERM_WINDOW_LENGTH_PROP_NAME = "aeron.publication.term.window.length";

    /**
     * Property name for window limit for IPC publications.
     */
    public static final String IPC_PUBLICATION_TERM_WINDOW_LENGTH_PROP_NAME =
        "aeron.ipc.publication.term.window.length";

    /**
     * Property name for {@link Publication} unblock timeout.
     */
    public static final String PUBLICATION_UNBLOCK_TIMEOUT_PROP_NAME = "aeron.publication.unblock.timeout";

    /**
     * Timeout for {@link Publication} unblock in nanoseconds.
     */
    public static final long PUBLICATION_UNBLOCK_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(10);

    /**
     * Property name for {@link Publication} connection timeout.
     */
    public static final String PUBLICATION_CONNECTION_TIMEOUT_PROP_NAME = "aeron.publication.connection.timeout";

    /**
     * Timeout for {@link Publication} connection timeout in nanoseconds
     */
    public static final long PUBLICATION_CONNECTION_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Property name for if spy subscriptions simulate a connection.
     */
    public static final String SPIES_SIMULATE_CONNECTION_PROP_NAME = "aeron.spies.simulate.connection";

    /**
     * Default idle strategy for agents.
     */
    private static final String DEFAULT_IDLE_STRATEGY = "org.agrona.concurrent.BackoffIdleStrategy";

    /**
     * Spin on no activity before backing off to yielding.
     */
    public static final long IDLE_MAX_SPINS = 10;

    /**
     * Yield the thread so others can run before backing off to parking.
     */
    public static final long IDLE_MAX_YIELDS = 20;

    /**
     * Park for the minimum period of time which is typically 50-55 microseconds on 64-bit non-virtualised Linux.
     * You will typically get 50-55 microseconds plus the number of nanoseconds requested if a core is available.
     * On Windows expect to wait for at least 16ms or 1ms if the high-res timers are enabled.
     */
    public static final long IDLE_MIN_PARK_NS = 1000;

    /**
     * Maximum back-off park time which doubles on each interval stepping up from the min park idle.
     */
    public static final long IDLE_MAX_PARK_NS = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * {@link IdleStrategy} to be used when mode can be controlled via a counter.
     */
    public static final String CONTROLLABLE_IDLE_STRATEGY = "org.agrona.concurrent.ControllableIdleStrategy";

    /**
     * Property name for {@link IdleStrategy} to be employed by {@link Sender} for {@link ThreadingMode#DEDICATED}.
     */
    public static final String SENDER_IDLE_STRATEGY_PROP_NAME = "aeron.sender.idle.strategy";

    /**
     * Property name for {@link IdleStrategy} to be employed by {@link DriverConductor} for
     * {@link ThreadingMode#DEDICATED} and {@link ThreadingMode#SHARED_NETWORK}.
     */
    public static final String CONDUCTOR_IDLE_STRATEGY_PROP_NAME = "aeron.conductor.idle.strategy";

    /**
     * Property name for {@link IdleStrategy} to be employed by {@link Receiver} for {@link ThreadingMode#DEDICATED}.
     */
    public static final String RECEIVER_IDLE_STRATEGY_PROP_NAME = "aeron.receiver.idle.strategy";

    /**
     * Property name for {@link IdleStrategy} to be employed by {@link Sender} and {@link Receiver} for
     * {@link ThreadingMode#SHARED_NETWORK}.
     */
    public static final String SHARED_NETWORK_IDLE_STRATEGY_PROP_NAME = "aeron.sharednetwork.idle.strategy";

    /**
     * Property name for {@link IdleStrategy} to be employed by {@link Sender}, {@link Receiver},
     * and {@link DriverConductor} for {@link ThreadingMode#SHARED}.
     */
    public static final String SHARED_IDLE_STRATEGY_PROP_NAME = "aeron.shared.idle.strategy";

    /**
     * Property name for {@link FlowControl} to be employed for unicast channels.
     */
    public static final String UNICAST_FLOW_CONTROL_STRATEGY_PROP_NAME = "aeron.unicast.flow.control.strategy";

    /**
     * {@link FlowControl} to be employed for unicast channels.
     */
    public static final String UNICAST_FLOW_CONTROL_STRATEGY = getProperty(
        UNICAST_FLOW_CONTROL_STRATEGY_PROP_NAME, "io.aeron.driver.UnicastFlowControl");

    /**
     * Property name for {@link FlowControl} to be employed for multicast channels.
     */
    public static final String MULTICAST_FLOW_CONTROL_STRATEGY_PROP_NAME = "aeron.multicast.flow.control.strategy";

    /**
     * {@link FlowControl} to be employed for multicast channels.
     */
    public static final String MULTICAST_FLOW_CONTROL_STRATEGY = getProperty(
        MULTICAST_FLOW_CONTROL_STRATEGY_PROP_NAME, "io.aeron.driver.MaxMulticastFlowControl");

    /**
     * Property name for {@link FlowControlSupplier} to be employed for unicast channels.
     */
    public static final String UNICAST_FLOW_CONTROL_STRATEGY_SUPPLIER_PROP_NAME = "aeron.unicast.FlowControl.supplier";

    /**
     * Property name for {@link FlowControlSupplier} to be employed for unicast channels.
     */
    public static final String MULTICAST_FLOW_CONTROL_STRATEGY_SUPPLIER_PROP_NAME =
        "aeron.multicast.FlowControl.supplier";

    /**
     * Maximum UDP datagram payload size for IPv4. Jumbo datagrams from IPv6 are not supported.
     * <p>
     * Max length is 65,507 bytes as 65,535 minus 8 byte UDP header then minus 20 byte IP header.
     * Then round down to nearest multiple of {@link FrameDescriptor#FRAME_ALIGNMENT} to give 65,504.
     */
    public static final int MAX_UDP_PAYLOAD_LENGTH = 65504;

    /**
     * Length of the maximum transmission unit of the media driver's protocol. If this is greater
     * than the network MTU for UDP then the packet will be fragmented and can amplify the impact of loss.
     */
    public static final String MTU_LENGTH_PROP_NAME = "aeron.mtu.length";

    /**
     * The default is conservative to avoid fragmentation on IPv4 or IPv6 over Ethernet with PPPoE header,
     * or for clouds such as Google, Oracle, and AWS.
     * <p>
     * On networks that suffer little congestion then a larger value can be used to reduce syscall costs.
     */
    public static final int MTU_LENGTH_DEFAULT = 1408;

    /**
     * Length of the maximum transmission unit of the media driver's protocol for IPC.
     */
    public static final String IPC_MTU_LENGTH_PROP_NAME = "aeron.ipc.mtu.length";

    /**
     * {@link ThreadingMode} to be used by the Aeron {@link MediaDriver}.
     */
    public static final String THREADING_MODE_PROP_NAME = "aeron.threading.mode";

    /**
     * Interval in between checks for timers and timeouts.
     */
    public static final String TIMER_INTERVAL_PROP_NAME = "aeron.timer.interval";

    /**
     * Default interval in between checks for timers and timeouts.
     */
    public static final long DEFAULT_TIMER_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

    /**
     *  Timeout between a counter being freed and being available to be reused.
     */
    public static final String COUNTER_FREE_TO_REUSE_TIMEOUT_PROP_NAME = "aeron.counters.free.to.reuse.timeout";

    /**
     *  Timeout between a counter being freed and being available to be reused.
     */
    public static final long DEFAULT_COUNTER_FREE_TO_REUSE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(1);

    /**
     * Property name for {@link SendChannelEndpointSupplier}.
     */
    public static final String SEND_CHANNEL_ENDPOINT_SUPPLIER_PROP_NAME = "aeron.SendChannelEndpoint.supplier";

    /**
     * Property name for {@link ReceiveChannelEndpointSupplier}.
     */
    public static final String RECEIVE_CHANNEL_ENDPOINT_SUPPLIER_PROP_NAME = "aeron.ReceiveChannelEndpoint.supplier";

    /**
     * Property name for Application Specific Feedback added to Status Messages by the driver for flow control.
     * <p>
     * Replaced by {@link #RECEIVER_GROUP_TAG_PROP_NAME}.
     */
    @Deprecated
    public static final String SM_APPLICATION_SPECIFIC_FEEDBACK_PROP_NAME =
        "aeron.flow.control.sm.applicationSpecificFeedback";

    /**
     * Property name for {@link CongestionControlSupplier} to be employed for receivers.
     */
    public static final String CONGESTION_CONTROL_STRATEGY_SUPPLIER_PROP_NAME = "aeron.CongestionControl.supplier";

    /**
     * Property name for low end of the publication reserved session-id range which will not be automatically assigned.
     */
    public static final String PUBLICATION_RESERVED_SESSION_ID_LOW_PROP_NAME =
        "aeron.publication.reserved.session.id.low";

    /**
     * Low end of the publication reserved session-id range which will not be automatically assigned.
     */
    public static final int PUBLICATION_RESERVED_SESSION_ID_LOW_DEFAULT = -1;

    /**
     * Property name for high end of the publication reserved session-id range which will not be automatically assigned.
     */
    public static final String PUBLICATION_RESERVED_SESSION_ID_HIGH_PROP_NAME =
        "aeron.publication.reserved.session.id.high";

    /**
     * High end of the publication reserved session-id range which will not be automatically assigned.
     */
    public static final int PUBLICATION_RESERVED_SESSION_ID_HIGH_DEFAULT = 1000;

    /**
     * Limit for the number of commands drained in one operation.
     */
    public static final int COMMAND_DRAIN_LIMIT = 10;

    /**
     * Capacity for the command queues used between driver agents.
     */
    public static final int CMD_QUEUE_CAPACITY = 256;

    /**
     * Timeout on cleaning up pending SETUP message state on subscriber.
     */
    public static final long PENDING_SETUPS_TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(1000);

    /**
     * Timeout between SETUP messages for publications during initial setup phase.
     */
    public static final long PUBLICATION_SETUP_TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(100);

    /**
     * Timeout between heartbeats for publications.
     */
    public static final long PUBLICATION_HEARTBEAT_TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(100);

    /**
     * Expected size of multicast receiver groups property name.
     */
    public static final String NAK_MULTICAST_GROUP_SIZE_PROP_NAME = "aeron.nak.multicast.group.size";

    /**
     * Default multicast receiver group size estimate for NAK delay randomisation.
     */
    public static final int NAK_MULTICAST_GROUP_SIZE_DEFAULT = 10;

    /**
     * Max backoff time for multicast NAK delay randomisation in nanoseconds.
     */
    public static final String NAK_MULTICAST_MAX_BACKOFF_PROP_NAME = "aeron.nak.multicast.max.backoff";

    /**
     * Default max backoff for NAK delay randomisation in nanoseconds.
     */
    public static final long NAK_MAX_BACKOFF_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Unicast NAK delay in nanoseconds property name.
     */
    public static final String NAK_UNICAST_DELAY_PROP_NAME = "aeron.nak.unicast.delay";

    /**
     * Default Unicast NAK delay in nanoseconds.
     */
    public static final long NAK_UNICAST_DELAY_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Property for setting how long to delay before sending a retransmit following a NAK.
     */
    public static final String RETRANSMIT_UNICAST_DELAY_PROP_NAME = "aeron.retransmit.unicast.delay";

    /**
     * Default delay before retransmission of data for unicast in nanoseconds.
     */
    public static final long RETRANSMIT_UNICAST_DELAY_DEFAULT_NS = TimeUnit.NANOSECONDS.toNanos(0);

    /**
     * Property for setting how long to linger after delay on a NAK.
     */
    public static final String RETRANSMIT_UNICAST_LINGER_PROP_NAME = "aeron.retransmit.unicast.linger";

    /**
     * Default delay for linger for unicast in nanoseconds.
     */
    public static final long RETRANSMIT_UNICAST_LINGER_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Property name of the timeout for when an untethered subscription that is outside the window limit will
     * participate in local flow control.
     */
    public static final String UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME = "aeron.untethered.window.limit.timeout";

    /**
     * Default timeout for when an untethered subscription that is outside the window limit will participate in
     * local flow control.
     */
    public static final long UNTETHERED_WINDOW_LIMIT_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(5);

    /**
     * Property name of the timeout for when an untethered subscription is resting after not being able to keep up
     * before it is allowed to rejoin a stream.
     */
    public static final String UNTETHERED_RESTING_TIMEOUT_PROP_NAME = "aeron.untethered.resting.timeout";

    /**
     * Default timeout for when an untethered subscription is resting after not being able to keep up
     * before it is allowed to rejoin a stream.
     */
    public static final long UNTETHERED_RESTING_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(10);

    /**
     * Default max number of active retransmissions per connected stream.
     */
    public static final int MAX_RETRANSMITS_DEFAULT = 16;

    /**
     * Property name for the class used to validate if a driver should terminate based on token.
     */
    public static final String TERMINATION_VALIDATOR_PROP_NAME = "aeron.driver.termination.validator";

    /**
     * Property name for default boolean value for if a stream can be rejoined. True to allow rejoin, false to not.
     */
    public static final String REJOIN_STREAM_PROP_NAME = "aeron.rejoin.stream";

    /**
     * Property name for default group tag (gtag) to send in all Status Messages.
     */
    public static final String RECEIVER_GROUP_TAG_PROP_NAME = "aeron.receiver.group.tag";

    /**
     * Property name for default group tag (gtag) used by the tagged flow control strategy to group receivers.
     */
    public static final String FLOW_CONTROL_GROUP_TAG_PROP_NAME = "aeron.flow.control.group.tag";

    /**
     * Property name for default minimum group size used by flow control strategies to determine
     * connectivity.
     */
    public static final String FLOW_CONTROL_GROUP_MIN_SIZE_PROP_NAME =
        "aeron.flow.control.group.min.size";

    /**
     * Default value for the receiver timeout used to determine if the receiver should still be monitored for
     * flow control purposes.
     */
    public static final long FLOW_CONTROL_RECEIVER_TIMEOUT_DEFAULT_NS = TimeUnit.SECONDS.toNanos(2);

    /**
     * Property name for flow control timeouts.
     */
    public static final String FLOW_CONTROL_RECEIVER_TIMEOUT_PROP_NAME =
        "aeron.flow.control.receiver.timeout";

    private static final String MIN_FLOW_CONTROL_TIMEOUT_OLD_PROP_NAME =
        "aeron.MinMulticastFlowControl.receiverTimeout";

    /**
     * Property name for resolver name of the Media Driver.
     */
    public static final String RESOLVER_NAME_PROP_NAME = "aeron.driver.resolver.name";

    /**
     * Property name for resolver interface.
     */
    public static final String RESOLVER_INTERFACE_PROP_NAME = "aeron.driver.resolver.interface";

    /**
     * Property name for resolver bootstrap neighbor.
     */
    public static final String RESOLVER_BOOTSTRAP_NEIGHBOR_PROP_NAME = "aeron.driver.resolver.bootstrap.neighbor";

    /**
     * Property name for re-resolution check interval.
     */
    public static final String RE_RESOLUTION_CHECK_INTERVAL_PROP_NAME = "aeron.driver.reresolution.check.interval";

    /**
     * Default value for the re-resolution check interval.
     */
    public static final long RE_RESOLUTION_CHECK_INTERVAL_DEFAULT_NS = TimeUnit.SECONDS.toNanos(1);

    public static boolean printConfigurationOnStart()
    {
        return "true".equalsIgnoreCase(getProperty(PRINT_CONFIGURATION_ON_START_PROP_NAME, "false"));
    }

    public static boolean useWindowsHighResTimer()
    {
        return "true".equalsIgnoreCase(getProperty(USE_WINDOWS_HIGH_RES_TIMER_PROP_NAME, "false"));
    }

    public static boolean warnIfDirExists()
    {
        return "true".equalsIgnoreCase(getProperty(DIR_WARN_IF_EXISTS_PROP_NAME, "false"));
    }

    public static boolean dirDeleteOnStart()
    {
        return "true".equalsIgnoreCase(getProperty(DIR_DELETE_ON_START_PROP_NAME, "false"));
    }

    public static boolean dirDeleteOnShutdown()
    {
        return "true".equalsIgnoreCase(getProperty(DIR_DELETE_ON_SHUTDOWN_PROP_NAME, "false"));
    }

    public static boolean termBufferSparseFile()
    {
        return "true".equalsIgnoreCase(getProperty(TERM_BUFFER_SPARSE_FILE_PROP_NAME, "false"));
    }

    public static boolean tetherSubscriptions()
    {
        return "true".equalsIgnoreCase(getProperty(TETHER_SUBSCRIPTIONS_PROP_NAME, "true"));
    }

    public static boolean reliableStream()
    {
        return "true".equalsIgnoreCase(getProperty(RELIABLE_STREAM_PROP_NAME, "true"));
    }

    public static boolean performStorageChecks()
    {
        return "true".equalsIgnoreCase(getProperty(PERFORM_STORAGE_CHECKS_PROP_NAME, "true"));
    }

    public static boolean spiesSimulateConnection()
    {
        return "true".equalsIgnoreCase(getProperty(SPIES_SIMULATE_CONNECTION_PROP_NAME, "false"));
    }

    public static CommonContext.InferableBoolean receiverGroupConsideration()
    {
        return CommonContext.InferableBoolean.parse(getProperty(GROUP_RECEIVER_CONSIDERATION_PROP_NAME));
    }

    public static int conductorBufferLength()
    {
        return getSizeAsInt(CONDUCTOR_BUFFER_LENGTH_PROP_NAME, CONDUCTOR_BUFFER_LENGTH_DEFAULT);
    }

    public static int toClientsBufferLength()
    {
        return getSizeAsInt(TO_CLIENTS_BUFFER_LENGTH_PROP_NAME, TO_CLIENTS_BUFFER_LENGTH_DEFAULT);
    }

    public static int counterValuesBufferLength()
    {
        return getSizeAsInt(COUNTERS_VALUES_BUFFER_LENGTH_PROP_NAME, COUNTERS_VALUES_BUFFER_LENGTH_DEFAULT);
    }

    public static int errorBufferLength()
    {
        return getSizeAsInt(ERROR_BUFFER_LENGTH_PROP_NAME, ERROR_BUFFER_LENGTH_DEFAULT);
    }

    public static int nakMulticastGroupSize()
    {
        return getInteger(NAK_MULTICAST_GROUP_SIZE_PROP_NAME, NAK_MULTICAST_GROUP_SIZE_DEFAULT);
    }

    public static long nakMulticastMaxBackoffNs()
    {
        return getDurationInNanos(NAK_MULTICAST_MAX_BACKOFF_PROP_NAME, NAK_MAX_BACKOFF_DEFAULT_NS);
    }

    public static long nakUnicastDelayNs()
    {
        return getDurationInNanos(NAK_UNICAST_DELAY_PROP_NAME, NAK_UNICAST_DELAY_DEFAULT_NS);
    }

    public static long timerIntervalNs()
    {
        return getDurationInNanos(TIMER_INTERVAL_PROP_NAME, DEFAULT_TIMER_INTERVAL_NS);
    }

    public static long lowStorageWarningThreshold()
    {
        return getSizeAsLong(LOW_FILE_STORE_WARNING_THRESHOLD_PROP_NAME, LOW_FILE_STORE_WARNING_THRESHOLD_DEFAULT);
    }

    public static int publicationTermWindowLength()
    {
        return getSizeAsInt(PUBLICATION_TERM_WINDOW_LENGTH_PROP_NAME, 0);
    }

    public static int ipcPublicationTermWindowLength()
    {
        return getSizeAsInt(IPC_PUBLICATION_TERM_WINDOW_LENGTH_PROP_NAME, 0);
    }

    public static long untetheredWindowLimitTimeoutNs()
    {
        return getDurationInNanos(
            UNTETHERED_WINDOW_LIMIT_TIMEOUT_PROP_NAME, UNTETHERED_WINDOW_LIMIT_TIMEOUT_DEFAULT_NS);
    }

    public static long untetheredRestingTimeoutNs()
    {
        return getDurationInNanos(
            UNTETHERED_RESTING_TIMEOUT_PROP_NAME, UNTETHERED_RESTING_TIMEOUT_DEFAULT_NS);
    }

    public static boolean rejoinStream()
    {
        return "true".equalsIgnoreCase(getProperty(REJOIN_STREAM_PROP_NAME, "true"));
    }

    public static Long groupTag()
    {
        return getLong(RECEIVER_GROUP_TAG_PROP_NAME, null);
    }

    @SuppressWarnings("deprecation")
    public static long flowControlGroupTag()
    {
        final String propertyValue = getProperty(
            PreferredMulticastFlowControl.PREFERRED_ASF_PROP_NAME, PreferredMulticastFlowControl.PREFERRED_ASF_DEFAULT);
        final long legacyAsfValue = new UnsafeBuffer(BitUtil.fromHex(propertyValue)).getLong(0, LITTLE_ENDIAN);

        return getLong(FLOW_CONTROL_GROUP_TAG_PROP_NAME, legacyAsfValue);
    }

    public static int flowControlGroupMinSize()
    {
        return getInteger(FLOW_CONTROL_GROUP_MIN_SIZE_PROP_NAME, 0);
    }

    public static long flowControlReceiverTimeoutNs()
    {
        return getDurationInNanos(
            FLOW_CONTROL_RECEIVER_TIMEOUT_PROP_NAME,
            getDurationInNanos(MIN_FLOW_CONTROL_TIMEOUT_OLD_PROP_NAME, FLOW_CONTROL_RECEIVER_TIMEOUT_DEFAULT_NS));
    }

    public static String resolverName()
    {
        return getProperty(RESOLVER_NAME_PROP_NAME);
    }

    public static String resolverInterface()
    {
        return getProperty(RESOLVER_INTERFACE_PROP_NAME);
    }

    public static String resolverBootstrapNeighbor()
    {
        return getProperty(RESOLVER_BOOTSTRAP_NEIGHBOR_PROP_NAME);
    }

    public static long reResolutionCheckIntervalNs()
    {
        return getDurationInNanos(RE_RESOLUTION_CHECK_INTERVAL_PROP_NAME, RE_RESOLUTION_CHECK_INTERVAL_DEFAULT_NS);
    }

    /**
     * How far ahead a producer can get from a consumer position.
     *
     * @param termBufferLength        for when default is not set and considering an appropriate minimum.
     * @param defaultTermWindowLength to take priority.
     * @return the length to be used for the producer window.
     */
    public static int producerWindowLength(final int termBufferLength, final int defaultTermWindowLength)
    {
        int termWindowLength = termBufferLength >> 1;

        if (0 != defaultTermWindowLength)
        {
            termWindowLength = Math.min(defaultTermWindowLength, termWindowLength);
        }

        return termWindowLength;
    }

    /**
     * Get the {@link IdleStrategy} that should be applied to {@link org.agrona.concurrent.Agent}s.
     *
     * @param strategyName       of the class to be created.
     * @param controllableStatus status indicator for what the strategy should do.
     * @return the newly created IdleStrategy.
     */
    public static IdleStrategy agentIdleStrategy(final String strategyName, final StatusIndicator controllableStatus)
    {
        IdleStrategy idleStrategy = null;

        switch (strategyName)
        {
            case "org.agrona.concurrent.NoOpIdleStrategy":
                idleStrategy = NoOpIdleStrategy.INSTANCE;
                break;

            case "org.agrona.concurrent.BusySpinIdleStrategy":
                idleStrategy = BusySpinIdleStrategy.INSTANCE;
                break;

            case "org.agrona.concurrent.YieldingIdleStrategy":
                idleStrategy = YieldingIdleStrategy.INSTANCE;
                break;

            case DEFAULT_IDLE_STRATEGY:
                idleStrategy = new BackoffIdleStrategy(
                    IDLE_MAX_SPINS, IDLE_MAX_YIELDS, IDLE_MIN_PARK_NS, IDLE_MAX_PARK_NS);
                break;

            case CONTROLLABLE_IDLE_STRATEGY:
                idleStrategy = new ControllableIdleStrategy(controllableStatus);
                controllableStatus.setOrdered(ControllableIdleStrategy.PARK);
                break;

            default:
                try
                {
                    idleStrategy = (IdleStrategy)Class.forName(strategyName).getConstructor().newInstance();
                }
                catch (final Exception ex)
                {
                    LangUtil.rethrowUnchecked(ex);
                }
                break;
        }

        return idleStrategy;
    }

    public static IdleStrategy senderIdleStrategy(final StatusIndicator controllableStatus)
    {
        return agentIdleStrategy(
            getProperty(SENDER_IDLE_STRATEGY_PROP_NAME, DEFAULT_IDLE_STRATEGY), controllableStatus);
    }

    public static IdleStrategy conductorIdleStrategy(final StatusIndicator controllableStatus)
    {
        return agentIdleStrategy(
            getProperty(CONDUCTOR_IDLE_STRATEGY_PROP_NAME, DEFAULT_IDLE_STRATEGY), controllableStatus);
    }

    public static IdleStrategy receiverIdleStrategy(final StatusIndicator controllableStatus)
    {
        return agentIdleStrategy(
            getProperty(RECEIVER_IDLE_STRATEGY_PROP_NAME, DEFAULT_IDLE_STRATEGY), controllableStatus);
    }

    public static IdleStrategy sharedNetworkIdleStrategy(final StatusIndicator controllableStatus)
    {
        return agentIdleStrategy(
            getProperty(SHARED_NETWORK_IDLE_STRATEGY_PROP_NAME, DEFAULT_IDLE_STRATEGY), controllableStatus);
    }

    public static IdleStrategy sharedIdleStrategy(final StatusIndicator controllableStatus)
    {
        return agentIdleStrategy(
            getProperty(SHARED_IDLE_STRATEGY_PROP_NAME, DEFAULT_IDLE_STRATEGY), controllableStatus);
    }

    public static int termBufferLength()
    {
        return getSizeAsInt(TERM_BUFFER_LENGTH_PROP_NAME, TERM_BUFFER_LENGTH_DEFAULT);
    }

    public static int ipcTermBufferLength()
    {
        return getSizeAsInt(IPC_TERM_BUFFER_LENGTH_PROP_NAME, TERM_BUFFER_IPC_LENGTH_DEFAULT);
    }

    public static int initialWindowLength()
    {
        return getSizeAsInt(INITIAL_WINDOW_LENGTH_PROP_NAME, INITIAL_WINDOW_LENGTH_DEFAULT);
    }

    public static int socketMulticastTtl()
    {
        return getInteger(SOCKET_MULTICAST_TTL_PROP_NAME, SOCKET_MULTICAST_TTL_DEFAULT);
    }

    public static int socketSndbufLength()
    {
        return getSizeAsInt(SOCKET_SNDBUF_LENGTH_PROP_NAME, SOCKET_SNDBUF_LENGTH_DEFAULT);
    }

    public static int socketRcvbufLength()
    {
        return getSizeAsInt(SOCKET_RCVBUF_LENGTH_PROP_NAME, SOCKET_RCVBUF_LENGTH_DEFAULT);
    }

    public static int mtuLength()
    {
        return getSizeAsInt(MTU_LENGTH_PROP_NAME, MTU_LENGTH_DEFAULT);
    }

    public static int ipcMtuLength()
    {
        return getSizeAsInt(IPC_MTU_LENGTH_PROP_NAME, MTU_LENGTH_DEFAULT);
    }

    public static int filePageSize()
    {
        return getSizeAsInt(FILE_PAGE_SIZE_PROP_NAME, FILE_PAGE_SIZE_DEFAULT);
    }

    public static int publicationReservedSessionIdLow()
    {
        return getInteger(PUBLICATION_RESERVED_SESSION_ID_LOW_PROP_NAME, PUBLICATION_RESERVED_SESSION_ID_LOW_DEFAULT);
    }

    public static int publicationReservedSessionIdHigh()
    {
        return getInteger(PUBLICATION_RESERVED_SESSION_ID_HIGH_PROP_NAME, PUBLICATION_RESERVED_SESSION_ID_HIGH_DEFAULT);
    }

    public static long statusMessageTimeoutNs()
    {
        return getDurationInNanos(STATUS_MESSAGE_TIMEOUT_PROP_NAME, STATUS_MESSAGE_TIMEOUT_DEFAULT_NS);
    }

    public static int sendToStatusMessagePollRatio()
    {
        return getInteger(SEND_TO_STATUS_POLL_RATIO_PROP_NAME, SEND_TO_STATUS_POLL_RATIO_DEFAULT);
    }

    public static long counterFreeToReuseTimeoutNs()
    {
        return getDurationInNanos(COUNTER_FREE_TO_REUSE_TIMEOUT_PROP_NAME, DEFAULT_COUNTER_FREE_TO_REUSE_TIMEOUT_NS);
    }

    public static long clientLivenessTimeoutNs()
    {
        return getDurationInNanos(CLIENT_LIVENESS_TIMEOUT_PROP_NAME, CLIENT_LIVENESS_TIMEOUT_DEFAULT_NS);
    }

    public static long imageLivenessTimeoutNs()
    {
        return getDurationInNanos(IMAGE_LIVENESS_TIMEOUT_PROP_NAME, IMAGE_LIVENESS_TIMEOUT_DEFAULT_NS);
    }

    public static long publicationUnblockTimeoutNs()
    {
        return getDurationInNanos(PUBLICATION_UNBLOCK_TIMEOUT_PROP_NAME, PUBLICATION_UNBLOCK_TIMEOUT_DEFAULT_NS);
    }

    public static long publicationConnectionTimeoutNs()
    {
        return getDurationInNanos(PUBLICATION_CONNECTION_TIMEOUT_PROP_NAME, PUBLICATION_CONNECTION_TIMEOUT_DEFAULT_NS);
    }

    public static long publicationLingerTimeoutNs()
    {
        return getDurationInNanos(PUBLICATION_LINGER_PROP_NAME, PUBLICATION_LINGER_DEFAULT_NS);
    }

    public static long retransmitUnicastDelayNs()
    {
        return getDurationInNanos(RETRANSMIT_UNICAST_DELAY_PROP_NAME, RETRANSMIT_UNICAST_DELAY_DEFAULT_NS);
    }

    public static long retransmitUnicastLingerNs()
    {
        return getDurationInNanos(RETRANSMIT_UNICAST_LINGER_PROP_NAME, RETRANSMIT_UNICAST_LINGER_DEFAULT_NS);
    }

    public static int lossReportBufferLength()
    {
        return getSizeAsInt(LOSS_REPORT_BUFFER_LENGTH_PROP_NAME, LOSS_REPORT_BUFFER_LENGTH_DEFAULT);
    }

    public static ThreadingMode threadingMode()
    {
        final String propertyValue = getProperty(THREADING_MODE_PROP_NAME);
        if (null == propertyValue)
        {
            return DEDICATED;
        }

        return ThreadingMode.valueOf(propertyValue);
    }

    public static byte[] applicationSpecificFeedback()
    {
        final String propertyValue = getProperty(SM_APPLICATION_SPECIFIC_FEEDBACK_PROP_NAME);
        if (null == propertyValue)
        {
            return ArrayUtil.EMPTY_BYTE_ARRAY;
        }

        return fromHex(propertyValue);
    }

    /**
     * Get the supplier of {@link SendChannelEndpoint}s which can be used for
     * debugging, monitoring, or modifying the behaviour when sending to the channel.
     *
     * @return the {@link SendChannelEndpointSupplier}.
     */
    public static SendChannelEndpointSupplier sendChannelEndpointSupplier()
    {
        SendChannelEndpointSupplier supplier = null;
        try
        {
            final String className = getProperty(SEND_CHANNEL_ENDPOINT_SUPPLIER_PROP_NAME);
            if (null == className)
            {
                return new DefaultSendChannelEndpointSupplier();
            }

            supplier = (SendChannelEndpointSupplier)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return supplier;
    }

    /**
     * Get the supplier of {@link ReceiveChannelEndpoint}s which can be used for
     * debugging, monitoring, or modifying the behaviour when receiving from the channel.
     *
     * @return the {@link SendChannelEndpointSupplier}.
     */
    public static ReceiveChannelEndpointSupplier receiveChannelEndpointSupplier()
    {
        ReceiveChannelEndpointSupplier supplier = null;
        try
        {
            final String className = getProperty(RECEIVE_CHANNEL_ENDPOINT_SUPPLIER_PROP_NAME);
            if (null == className)
            {
                return new DefaultReceiveChannelEndpointSupplier();
            }

            supplier = (ReceiveChannelEndpointSupplier)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return supplier;
    }

    /**
     * Get the supplier of {@link FlowControl}s which can be used for changing behavior of flow control for unicast
     * publications.
     *
     * @return the {@link FlowControlSupplier}.
     */
    public static FlowControlSupplier unicastFlowControlSupplier()
    {
        FlowControlSupplier supplier = null;
        try
        {
            final String className = getProperty(UNICAST_FLOW_CONTROL_STRATEGY_SUPPLIER_PROP_NAME);
            if (null == className)
            {
                return new DefaultUnicastFlowControlSupplier();
            }

            supplier = (FlowControlSupplier)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return supplier;
    }

    /**
     * Get the supplier of {@link FlowControl}s which can be used for changing behavior of flow control for multicast
     * publications.
     *
     * @return the {@link FlowControlSupplier}.
     */
    public static FlowControlSupplier multicastFlowControlSupplier()
    {
        FlowControlSupplier supplier = null;
        try
        {
            final String className = getProperty(MULTICAST_FLOW_CONTROL_STRATEGY_SUPPLIER_PROP_NAME);
            if (null == className)
            {
                return new DefaultMulticastFlowControlSupplier();
            }

            supplier = (FlowControlSupplier)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return supplier;
    }

    /**
     * Get the supplier of {@link CongestionControl} implementations which can be used for receivers.
     *
     * @return the {@link CongestionControlSupplier}
     */
    public static CongestionControlSupplier congestionControlSupplier()
    {
        CongestionControlSupplier supplier = null;
        try
        {
            final String className = getProperty(CONGESTION_CONTROL_STRATEGY_SUPPLIER_PROP_NAME);
            if (null == className)
            {
                return new DefaultCongestionControlSupplier();
            }

            supplier = (CongestionControlSupplier)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return supplier;
    }

    /**
     * Validate that the initial window length is greater than MTU.
     *
     * @param initialWindowLength to be validated.
     * @param mtuLength           against which to validate.
     */
    public static void validateInitialWindowLength(final int initialWindowLength, final int mtuLength)
    {
        if (mtuLength > initialWindowLength)
        {
            throw new ConfigurationException("initial window length must be >= to MTU length: " + mtuLength);
        }
    }

    /**
     * Validate that the MTU is an appropriate length. MTU lengths must be a multiple of
     * {@link FrameDescriptor#FRAME_ALIGNMENT}.
     *
     * @param mtuLength to be validated.
     * @throws ConfigurationException if the MTU length is not valid.
     */
    public static void validateMtuLength(final int mtuLength)
    {
        if (mtuLength < DataHeaderFlyweight.HEADER_LENGTH || mtuLength > MAX_UDP_PAYLOAD_LENGTH)
        {
            throw new ConfigurationException(
                "mtuLength must be a >= HEADER_LENGTH and <= MAX_UDP_PAYLOAD_LENGTH: " + mtuLength);
        }

        if ((mtuLength & (FrameDescriptor.FRAME_ALIGNMENT - 1)) != 0)
        {
            throw new ConfigurationException("mtuLength must be a multiple of FRAME_ALIGNMENT: " + mtuLength);
        }
    }

    /**
     * Validate the publication linger timeout is an appropriate value.
     *
     * @param timeoutNs to be validated.
     * @param driverLingerTimeoutNs set for the driver operation.
     * @throws ConfigurationException if the values are not valid.
     */
    public static void validatePublicationLingerTimeoutNs(final long timeoutNs, final long driverLingerTimeoutNs)
    {
        if (timeoutNs < driverLingerTimeoutNs)
        {
            throw new ConfigurationException("linger=" + driverLingerTimeoutNs + " < timeoutNs =" + timeoutNs);
        }
    }

    /**
     * Get the {@link TerminationValidator} implementations which can be used for validating a termination request
     * sent to the driver to ensure the client has the right to terminate a driver.
     *
     * @return the {@link TerminationValidator}
     */
    public static TerminationValidator terminationValidator()
    {
        TerminationValidator validator = null;
        try
        {
            final String className = getProperty(TERMINATION_VALIDATOR_PROP_NAME);
            if (null == className)
            {
                return new DefaultDenyTerminationValidator();
            }

            validator = (TerminationValidator)Class.forName(className).getConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return validator;
    }

    /**
     * Validate that the socket buffer lengths are sufficient for the media driver configuration.
     *
     * @param ctx to be validated.
     */
    public static void validateSocketBufferLengths(final MediaDriver.Context ctx)
    {
        try (DatagramChannel probe = DatagramChannel.open())
        {
            final int defaultSoSndBuf = probe.getOption(StandardSocketOptions.SO_SNDBUF);

            probe.setOption(StandardSocketOptions.SO_SNDBUF, Integer.MAX_VALUE);
            final int maxSoSndBuf = probe.getOption(StandardSocketOptions.SO_SNDBUF);

            if (maxSoSndBuf < ctx.socketSndbufLength())
            {
                System.err.format(
                    "WARNING: Could not get desired SO_SNDBUF, adjust OS to allow %s: attempted=%d, actual=%d%n",
                    SOCKET_SNDBUF_LENGTH_PROP_NAME,
                    ctx.socketSndbufLength(),
                    maxSoSndBuf);
            }

            probe.setOption(StandardSocketOptions.SO_RCVBUF, Integer.MAX_VALUE);
            final int maxSoRcvBuf = probe.getOption(StandardSocketOptions.SO_RCVBUF);

            if (maxSoRcvBuf < ctx.socketRcvbufLength())
            {
                System.err.format(
                    "WARNING: Could not get desired SO_RCVBUF, adjust OS to allow %s: attempted=%d, actual=%d%n",
                    SOCKET_RCVBUF_LENGTH_PROP_NAME,
                    ctx.socketRcvbufLength(),
                    maxSoRcvBuf);
            }

            final int soSndBuf = 0 == ctx.socketSndbufLength() ? defaultSoSndBuf : ctx.socketSndbufLength();

            if (ctx.mtuLength() > soSndBuf)
            {
                throw new ConfigurationException(String.format(
                    "MTU greater than socket SO_SNDBUF, adjust %s to match MTU: mtuLength=%d, SO_SNDBUF=%d",
                    SOCKET_SNDBUF_LENGTH_PROP_NAME,
                    ctx.mtuLength(),
                    soSndBuf));
            }

            if (ctx.initialWindowLength() > maxSoRcvBuf)
            {
                throw new ConfigurationException("window length greater than socket SO_RCVBUF, increase '" +
                    Configuration.INITIAL_WINDOW_LENGTH_PROP_NAME +
                    "' to match window: windowLength=" + ctx.initialWindowLength() + ", SO_RCVBUF=" + maxSoRcvBuf);
            }
        }
        catch (final IOException ex)
        {
            throw new AeronException("probe socket: " + ex.toString(), ex);
        }
    }

    /**
     * Validate that page size is valid and alignment is valid.
     *
     * @param pageSize to be checked.
     * @throws ConfigurationException if the size is not as expected.
     */
    public static void validatePageSize(final int pageSize)
    {
        if (pageSize < PAGE_MIN_SIZE)
        {
            throw new ConfigurationException(
                "page size less than min size of " + PAGE_MIN_SIZE + ": " + pageSize);
        }

        if (pageSize > PAGE_MAX_SIZE)
        {
            throw new ConfigurationException(
                "page size greater than max size of " + PAGE_MAX_SIZE + ": " + pageSize);
        }

        if (!BitUtil.isPowerOfTwo(pageSize))
        {
            throw new ConfigurationException("page size not a power of 2: " + pageSize);
        }
    }

    /**
     * Validate the range of session ids based on a high and low value provided which accounts for the values wrapping.
     *
     * @param low  value in the range.
     * @param high value in the range.
     * @throws ConfigurationException if the values are not valid.
     */
    public static void validateSessionIdRange(final int low, final int high)
    {
        if (low > high)
        {
            throw new ConfigurationException("low session id value " + low + " must be <= high value " + high);
        }

        if (Math.abs((long)high - low) > Integer.MAX_VALUE)
        {
            throw new ConfigurationException("reserved range to too large");
        }
    }

    /**
     * Compute the length of the {@link org.agrona.concurrent.status.CountersManager} metadata buffer based on the
     * length of the counters value buffer length.
     *
     * @param counterValuesBufferLength to compute the metadata buffer length from as a ratio.
     * @return the length that should be used for the metadata buffer for counters.
     */
    public static int countersMetadataBufferLength(final int counterValuesBufferLength)
    {
        return counterValuesBufferLength * (CountersReader.METADATA_LENGTH / CountersReader.COUNTER_LENGTH);
    }

    /**
     * Validate that the timeouts for unblocking publications from a client are valid.
     *
     * @param publicationUnblockTimeoutNs after which an uncommitted publication will be unblocked.
     * @param clientLivenessTimeoutNs     after which a client will be considered not alive.
     * @param timerIntervalNs             interval at which the driver will check timeouts.
     * @throws ConfigurationException if the values are not valid.
     */
    public static void validateUnblockTimeout(
        final long publicationUnblockTimeoutNs, final long clientLivenessTimeoutNs, final long timerIntervalNs)
    {
        if (publicationUnblockTimeoutNs <= clientLivenessTimeoutNs)
        {
            throw new ConfigurationException(
                "publicationUnblockTimeoutNs=" + publicationUnblockTimeoutNs +
                " <= clientLivenessTimeoutNs=" + clientLivenessTimeoutNs);
        }

        if (clientLivenessTimeoutNs <= timerIntervalNs)
        {
            throw new ConfigurationException(
                "clientLivenessTimeoutNs=" + clientLivenessTimeoutNs +
                " <= timerIntervalNs=" + timerIntervalNs);
        }
    }

    /**
     * Create a source identity for a given source address.
     *
     * @param srcAddress to be used for the identity.
     * @return a source identity string for a given address.
     */
    public static String sourceIdentity(final InetSocketAddress srcAddress)
    {
        return srcAddress.getHostString() + ':' + srcAddress.getPort();
    }
}
