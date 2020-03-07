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
import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.media.ImageConnection;
import io.aeron.driver.media.ReceiveChannelEndpoint;
import io.aeron.driver.media.ReceiveDestinationTransport;
import io.aeron.driver.reports.LossReport;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.TermRebuilder;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.RttMeasurementFlyweight;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.CachedEpochClock;
import org.agrona.concurrent.CachedNanoClock;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import java.net.InetSocketAddress;
import java.util.ArrayList;

import static io.aeron.driver.LossDetector.lossFound;
import static io.aeron.driver.LossDetector.rebuildOffset;
import static io.aeron.driver.PublicationImage.State.ACTIVE;
import static io.aeron.driver.PublicationImage.State.INIT;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static io.aeron.logbuffer.TermGapFiller.tryFillGap;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.UnsafeAccess.UNSAFE;

class PublicationImagePadding1
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class PublicationImageConductorFields extends PublicationImagePadding1
{
    protected long cleanPosition;
    protected final ArrayList<UntetheredSubscription> untetheredSubscriptions = new ArrayList<>();
    protected ReadablePosition[] subscriberPositions;
    protected LossReport lossReport;
    protected LossReport.ReportEntry reportEntry;
}

class PublicationImagePadding2 extends PublicationImageConductorFields
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7, p8;
}

class PublicationImageReceiverFields extends PublicationImagePadding2
{
    protected boolean isEndOfStream = false;
    protected long lastPacketTimestampNs;
    protected ImageConnection[] imageConnections = new ImageConnection[1];
}

class PublicationImagePadding3 extends PublicationImageReceiverFields
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

/**
 * State maintained for active sessionIds within a channel for receiver processing
 */
public class PublicationImage
    extends PublicationImagePadding3
    implements LossHandler, DriverManagedResource, Subscribable
{
    enum State
    {
        INIT, ACTIVE, INACTIVE, LINGER, DONE
    }

    private volatile long beginSmChange = Aeron.NULL_VALUE;
    private volatile long endSmChange = Aeron.NULL_VALUE;
    private long nextSmPosition;
    private int nextSmReceiverWindowLength;
    private long timeOfLastStatusMessageScheduleNs;

    private long lastSmPosition;
    private long lastSmWindowLimit;
    private long lastSmChangeNumber = Aeron.NULL_VALUE;
    private long lastLossChangeNumber = Aeron.NULL_VALUE;

    private volatile long beginLossChange = Aeron.NULL_VALUE;
    private volatile long endLossChange = Aeron.NULL_VALUE;
    private int lossTermId;
    private int lossTermOffset;
    private int lossLength;

    private long timeOfLastStateChangeNs;

    private final long correlationId;
    private final long imageLivenessTimeoutNs;
    private final long untetheredWindowLimitTimeoutNs;
    private final long untetheredRestingTimeoutNs;
    private final int sessionId;
    private final int streamId;
    private final int positionBitsToShift;
    private final int termLengthMask;
    private final int initialTermId;
    private final boolean isReliable;

    private boolean isRebuilding = true;
    private volatile State state = INIT;

    private final NanoClock nanoClock;
    private final CachedNanoClock cachedNanoClock;
    private final ReceiveChannelEndpoint channelEndpoint;
    private final UnsafeBuffer[] termBuffers;
    private final Position hwmPosition;
    private final LossDetector lossDetector;
    private final CongestionControl congestionControl;
    private final ErrorHandler errorHandler;
    private final Position rebuildPosition;
    private final InetSocketAddress sourceAddress;
    private final AtomicCounter heartbeatsReceived;
    private final AtomicCounter statusMessagesSent;
    private final AtomicCounter nakMessagesSent;
    private final AtomicCounter flowControlUnderRuns;
    private final AtomicCounter flowControlOverRuns;
    private final AtomicCounter lossGapFills;
    private final CachedEpochClock cachedEpochClock;
    private final RawLog rawLog;

    public PublicationImage(
        final long correlationId,
        final long imageLivenessTimeoutNs,
        final long untetheredWindowLimitTimeoutNs,
        final long untetheredRestingTimeoutNs,
        final ReceiveChannelEndpoint channelEndpoint,
        final int transportIndex,
        final InetSocketAddress controlAddress,
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final RawLog rawLog,
        final FeedbackDelayGenerator lossFeedbackDelayGenerator,
        final ArrayList<SubscriberPosition> subscriberPositions,
        final Position hwmPosition,
        final Position rebuildPosition,
        final NanoClock nanoClock,
        final CachedNanoClock cachedNanoClock,
        final CachedEpochClock cachedEpochClock,
        final SystemCounters systemCounters,
        final InetSocketAddress sourceAddress,
        final CongestionControl congestionControl,
        final LossReport lossReport,
        final ErrorHandler errorHandler)
    {
        this.correlationId = correlationId;
        this.imageLivenessTimeoutNs = imageLivenessTimeoutNs;
        this.untetheredWindowLimitTimeoutNs = untetheredWindowLimitTimeoutNs;
        this.untetheredRestingTimeoutNs = untetheredRestingTimeoutNs;
        this.channelEndpoint = channelEndpoint;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.rawLog = rawLog;
        this.hwmPosition = hwmPosition;
        this.rebuildPosition = rebuildPosition;
        this.sourceAddress = sourceAddress;
        this.initialTermId = initialTermId;
        this.congestionControl = congestionControl;
        this.errorHandler = errorHandler;
        this.lossReport = lossReport;

        this.nanoClock = nanoClock;
        this.cachedNanoClock = cachedNanoClock;
        this.cachedEpochClock = cachedEpochClock;

        final long nowNs = cachedNanoClock.nanoTime();
        this.timeOfLastStateChangeNs = nowNs;
        this.lastPacketTimestampNs = nowNs;

        this.subscriberPositions = positionArray(subscriberPositions, nowNs);
        this.isReliable = subscriberPositions.get(0).subscription().isReliable();

        heartbeatsReceived = systemCounters.get(HEARTBEATS_RECEIVED);
        statusMessagesSent = systemCounters.get(STATUS_MESSAGES_SENT);
        nakMessagesSent = systemCounters.get(NAK_MESSAGES_SENT);
        flowControlUnderRuns = systemCounters.get(FLOW_CONTROL_UNDER_RUNS);
        flowControlOverRuns = systemCounters.get(FLOW_CONTROL_OVER_RUNS);
        lossGapFills = systemCounters.get(LOSS_GAP_FILLS);

        imageConnections = ArrayUtil.ensureCapacity(imageConnections, transportIndex + 1);
        imageConnections[transportIndex] = new ImageConnection(nowNs, controlAddress);

        termBuffers = rawLog.termBuffers();
        lossDetector = new LossDetector(lossFeedbackDelayGenerator, this);

        final int termLength = rawLog.termLength();
        termLengthMask = termLength - 1;
        positionBitsToShift = LogBufferDescriptor.positionBitsToShift(termLength);

        final long position = computePosition(activeTermId, initialTermOffset, positionBitsToShift, initialTermId);
        nextSmPosition = position;
        nextSmReceiverWindowLength = congestionControl.initialWindowLength();
        lastSmPosition = position;
        lastSmWindowLimit = position + nextSmReceiverWindowLength;
        cleanPosition = position;

        hwmPosition.setOrdered(position);
        rebuildPosition.setOrdered(position);
    }

    /**
     * {@inheritDoc}
     */
    public boolean free()
    {
        return rawLog.free();
    }

    /**
     * {@inheritDoc}
     */
    public void close()
    {
        CloseHelper.close(errorHandler, hwmPosition);
        CloseHelper.close(errorHandler, rebuildPosition);
        CloseHelper.closeAll(errorHandler, subscriberPositions);

        for (int i = 0, size = untetheredSubscriptions.size(); i < size; i++)
        {
            final UntetheredSubscription untetheredSubscription = untetheredSubscriptions.get(i);
            if (UntetheredSubscription.RESTING == untetheredSubscription.state)
            {
                CloseHelper.close(errorHandler, untetheredSubscription.position);
            }
        }

        CloseHelper.close(errorHandler, congestionControl);
        CloseHelper.close(errorHandler, rawLog);
    }

    /**
     * The correlation id assigned by the driver when created.
     *
     * @return the correlation id assigned by the driver when created.
     */
    public long correlationId()
    {
        return correlationId;
    }

    /**
     * The session id of the channel from a publisher.
     *
     * @return session id of the channel from a publisher.
     */
    public int sessionId()
    {
        return sessionId;
    }

    /**
     * The stream id of this image within a channel.
     *
     * @return stream id of this image within a channel.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * Get the string representation of the channel URI.
     *
     * @return the string representation of the channel URI.
     */
    public String channel()
    {
        return channelEndpoint.originalUriString();
    }

    /**
     * {@inheritDoc}
     */
    public void addSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.add(subscriberPositions, subscriberPosition);
        if (!subscriptionLink.isTether())
        {
            untetheredSubscriptions.add(new UntetheredSubscription(
                subscriptionLink, subscriberPosition, timeOfLastStatusMessageScheduleNs));
        }
    }

    /**
     * {@inheritDoc}
     */
    public void removeSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition)
    {
        subscriberPositions = ArrayUtil.remove(subscriberPositions, subscriberPosition);
        subscriberPosition.close();

        if (!subscriptionLink.isTether())
        {
            for (int lastIndex = untetheredSubscriptions.size() - 1, i = lastIndex; i >= 0; i--)
            {
                if (untetheredSubscriptions.get(i).subscriptionLink == subscriptionLink)
                {
                    ArrayListUtil.fastUnorderedRemove(untetheredSubscriptions, i, lastIndex);
                    break;
                }
            }
        }

        if (subscriberPositions.length == 0)
        {
            isRebuilding = false;
        }
    }

    /**
     * Called from the {@link LossDetector} when gap is detected by the {@link DriverConductor} thread.
     * <p>
     * {@inheritDoc}
     */
    public void onGapDetected(final int termId, final int termOffset, final int length)
    {
        final long changeNumber = beginLossChange + 1;

        beginLossChange = changeNumber;

        lossTermId = termId;
        lossTermOffset = termOffset;
        lossLength = length;

        endLossChange = changeNumber;

        if (null != reportEntry)
        {
            reportEntry.recordObservation(length, cachedEpochClock.time());
        }
        else if (null != lossReport)
        {
            final String source = Configuration.sourceIdentity(sourceAddress);
            final long timeMs = cachedEpochClock.time();
            reportEntry = lossReport.createEntry(length, timeMs, sessionId, streamId, channel(), source);

            if (null == reportEntry)
            {
                lossReport = null;
            }
        }
    }

    /**
     * The address of the source associated with the image.
     *
     * @return source address
     */
    InetSocketAddress sourceAddress()
    {
        return sourceAddress;
    }

    /**
     * Return the {@link ReceiveChannelEndpoint} that the image is attached to.
     *
     * @return {@link ReceiveChannelEndpoint} that the image is attached to.
     */
    ReceiveChannelEndpoint channelEndpoint()
    {
        return channelEndpoint;
    }

    /**
     * Remove this image from the {@link DataPacketDispatcher} so it will process no further packets from the network.
     * Called from the {@link Receiver} thread.
     */
    void removeFromDispatcher()
    {
        channelEndpoint.removePublicationImage(this);
    }

    /**
     * Get the {@link RawLog} the back this image.
     *
     * @return the {@link RawLog} the back this image.
     */
    RawLog rawLog()
    {
        return rawLog;
    }

    /**
     * Activate this image from the {@link Receiver}
     */
    void activate()
    {
        state(ACTIVE);
    }

    /**
     * Add a destination to this image so it can merge streams.
     *
     * @param transportIndex from which packets will arrive.
     * @param transport      from which packets will arrive.
     */
    void addDestination(final int transportIndex, final ReceiveDestinationTransport transport)
    {
        imageConnections = ArrayUtil.ensureCapacity(imageConnections, transportIndex + 1);

        if (transport.isMulticast())
        {
            imageConnections[transportIndex] = new ImageConnection(
                cachedNanoClock.nanoTime(), transport.udpChannel().remoteControl());
        }
        else if (transport.hasExplicitControl())
        {
            imageConnections[transportIndex] = new ImageConnection(
                cachedNanoClock.nanoTime(), transport.explicitControlAddress());
        }
    }

    /**
     * Remove a destination to this image once merge is achieved.
     *
     * @param transportIndex from which packets arrive.
     */
    void removeDestination(final int transportIndex)
    {
        imageConnections[transportIndex] = null;
    }

    void addDestinationConnectionIfUnknown(final int transportIndex, final InetSocketAddress remoteAddress)
    {
        trackConnection(transportIndex, remoteAddress, cachedNanoClock.nanoTime());
    }

    /**
     * Called from the {@link DriverConductor} to track the rebuild os stream which is used for loss detection
     * and congestion control.
     *
     * @param nowNs                  current time.
     * @param statusMessageTimeoutNs for sending of Status Messages.
     */
    final void trackRebuild(final long nowNs, final long statusMessageTimeoutNs)
    {
        long minSubscriberPosition = Long.MAX_VALUE;
        long maxSubscriberPosition = Long.MIN_VALUE;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            final long position = subscriberPosition.getVolatile();
            minSubscriberPosition = Math.min(minSubscriberPosition, position);
            maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
        }

        final long rebuildPosition = Math.max(this.rebuildPosition.get(), maxSubscriberPosition);
        final long hwmPosition = this.hwmPosition.getVolatile();

        final long scanOutcome = lossDetector.scan(
            termBuffers[indexByPosition(rebuildPosition, positionBitsToShift)],
            rebuildPosition,
            hwmPosition,
            nowNs,
            termLengthMask,
            positionBitsToShift,
            initialTermId);

        final int rebuildTermOffset = (int)rebuildPosition & termLengthMask;
        final long newRebuildPosition = (rebuildPosition - rebuildTermOffset) + rebuildOffset(scanOutcome);
        this.rebuildPosition.proposeMaxOrdered(newRebuildPosition);

        final long ccOutcome = congestionControl.onTrackRebuild(
            nowNs,
            minSubscriberPosition,
            nextSmPosition,
            hwmPosition,
            rebuildPosition,
            newRebuildPosition,
            lossFound(scanOutcome));

        final int windowLength = CongestionControl.receiverWindowLength(ccOutcome);
        final int threshold = CongestionControl.threshold(windowLength);

        if (CongestionControl.shouldForceStatusMessage(ccOutcome) ||
            ((timeOfLastStatusMessageScheduleNs + statusMessageTimeoutNs) - nowNs < 0) ||
            (minSubscriberPosition > (nextSmPosition + threshold)))
        {
            cleanBufferTo(minSubscriberPosition - (termLengthMask + 1));
            scheduleStatusMessage(nowNs, minSubscriberPosition, windowLength);
        }
    }

    /**
     * Set state to {@link State#INACTIVE} if currently {@link State#ACTIVE}. Set by {@link Receiver}.
     */
    void ifActiveGoInactive()
    {
        if (State.ACTIVE == state)
        {
            isRebuilding = false;
            state(State.INACTIVE);
        }
    }

    /**
     * Is this image actively rebuilding and thus should be checked for loss.
     *
     * @return true if this image actively rebuilding and thus should be checked for loss.
     */
    final boolean isRebuilding()
    {
        return isRebuilding;
    }

    /**
     * Insert frame into term buffer.
     *
     * @param termId         for the data packet to insert into the appropriate term.
     * @param termOffset     for the start of the packet in the term.
     * @param buffer         for the data packet to insert into the appropriate term.
     * @param length         of the data packet.
     * @param transportIndex from which the packet came.
     * @param srcAddress     from which the packet came.
     * @return number of bytes applied as a result of this insertion.
     */
    int insertPacket(
        final int termId,
        final int termOffset,
        final UnsafeBuffer buffer,
        final int length,
        final int transportIndex,
        final InetSocketAddress srcAddress)
    {
        final boolean isHeartbeat = DataHeaderFlyweight.isHeartbeat(buffer, length);
        final long packetPosition = computePosition(termId, termOffset, positionBitsToShift, initialTermId);
        final long proposedPosition = isHeartbeat ? packetPosition : packetPosition + length;

        if (!isFlowControlOverRun(proposedPosition))
        {
            if (!isFlowControlUnderRun(packetPosition))
            {
                final long nowNs = cachedNanoClock.nanoTime();
                lastPacketTimestampNs = nowNs;
                trackConnection(transportIndex, srcAddress, nowNs);

                if (isHeartbeat)
                {
                    if (DataHeaderFlyweight.isEndOfStream(buffer) && !isEndOfStream && allEos(transportIndex))
                    {
                        LogBufferDescriptor.endOfStreamPosition(rawLog.metaData(), proposedPosition);
                        isEndOfStream = true;
                    }

                    heartbeatsReceived.incrementOrdered();
                }
                else
                {
                    final UnsafeBuffer termBuffer = termBuffers[indexByPosition(packetPosition, positionBitsToShift)];
                    TermRebuilder.insert(termBuffer, termOffset, buffer, length);
                }

                hwmPosition.proposeMaxOrdered(proposedPosition);
            }
            else if (packetPosition >= (lastSmPosition - nextSmReceiverWindowLength))
            {
                trackConnection(transportIndex, srcAddress, cachedNanoClock.nanoTime());
            }
        }

        return length;
    }

    /**
     * To be called from the {@link Receiver} to see if a image should be retained.
     *
     * @param nowNs current time to check against for activity.
     * @return true if the image should be retained otherwise false.
     */
    boolean hasActivityAndNotEndOfStream(final long nowNs)
    {
        boolean isActive = true;

        if (((lastPacketTimestampNs + imageLivenessTimeoutNs) - nowNs < 0) ||
            (isEndOfStream && rebuildPosition.getVolatile() >= hwmPosition.get()))
        {
            isActive = false;
        }

        return isActive;
    }

    /**
     * Called from the {@link Receiver} to send any pending Status Messages.
     *
     * @return number of work items processed.
     */
    int sendPendingStatusMessage()
    {
        int workCount = 0;

        if (ACTIVE == state)
        {
            final long changeNumber = endSmChange;

            if (changeNumber != lastSmChangeNumber)
            {
                final long smPosition = nextSmPosition;
                final int receiverWindowLength = nextSmReceiverWindowLength;

                UNSAFE.loadFence();

                if (changeNumber == beginSmChange)
                {
                    final int termId = computeTermIdFromPosition(smPosition, positionBitsToShift, initialTermId);
                    final int termOffset = (int)smPosition & termLengthMask;

                    channelEndpoint.sendStatusMessage(
                        imageConnections, sessionId, streamId, termId, termOffset, receiverWindowLength, (byte)0);

                    statusMessagesSent.incrementOrdered();

                    lastSmPosition = smPosition;
                    lastSmWindowLimit = smPosition + receiverWindowLength;
                    lastSmChangeNumber = changeNumber;

                    updateActiveTransportCount();
                }

                workCount = 1;
            }
        }

        return workCount;
    }

    /**
     * Called from the {@link Receiver} thread to processing any pending loss of packets.
     *
     * @return number of work items processed.
     */
    int processPendingLoss()
    {
        int workCount = 0;
        final long changeNumber = endLossChange;

        if (changeNumber != lastLossChangeNumber)
        {
            final int termId = lossTermId;
            final int termOffset = lossTermOffset;
            final int length = lossLength;

            UNSAFE.loadFence();

            if (changeNumber == beginLossChange)
            {
                if (isReliable)
                {
                    channelEndpoint.sendNakMessage(imageConnections, sessionId, streamId, termId, termOffset, length);
                    nakMessagesSent.incrementOrdered();
                }
                else
                {
                    final UnsafeBuffer termBuffer = termBuffers[indexByTerm(initialTermId, termId)];
                    if (tryFillGap(rawLog.metaData(), termBuffer, termId, termOffset, length))
                    {
                        lossGapFills.incrementOrdered();
                    }
                }

                lastLossChangeNumber = changeNumber;
            }

            workCount = 1;
        }

        return workCount;
    }

    /**
     * Called from the {@link Receiver} thread to check for initiating an RTT measurement.
     *
     * @param nowNs in nanoseconds
     * @return number of work items processed.
     */
    int initiateAnyRttMeasurements(final long nowNs)
    {
        int workCount = 0;

        if (congestionControl.shouldMeasureRtt(nowNs))
        {
            final long preciseTimeNs = nanoClock.nanoTime();

            channelEndpoint.sendRttMeasurement(imageConnections, sessionId, streamId, preciseTimeNs, 0, true);
            congestionControl.onRttMeasurementSent(preciseTimeNs);

            workCount = 1;
        }

        return workCount;
    }

    /**
     * Called from the {@link Receiver} upon receiving an RTT Measurement that is a reply.
     *
     * @param header         of the measurement message.
     * @param transportIndex that the RTT Measurement came in on.
     * @param srcAddress     from the sender requesting the measurement
     */
    void onRttMeasurement(
        final RttMeasurementFlyweight header,
        @SuppressWarnings("unused") final int transportIndex,
        final InetSocketAddress srcAddress)
    {
        final long nowNs = nanoClock.nanoTime();
        final long rttInNs = nowNs - header.echoTimestampNs() - header.receptionDelta();

        congestionControl.onRttMeasurement(nowNs, rttInNs, srcAddress);
    }

    /**
     * Is the image in a state to accept new subscriptions?
     *
     * @return true if accepting new subscriptions.
     */
    boolean isAcceptingSubscriptions()
    {
        return subscriberPositions.length > 0 && (state == ACTIVE || state == INIT);
    }

    /**
     * The position up to which the current stream rebuild is complete for reception.
     *
     * @return the position up to which the current stream rebuild is complete for reception.
     */
    long rebuildPosition()
    {
        return rebuildPosition.get();
    }

    /**
     * {@inheritDoc}
     */
    public void onTimeEvent(final long timeNs, final long timesMs, final DriverConductor conductor)
    {
        switch (state)
        {
            case ACTIVE:
                checkUntetheredSubscriptions(timeNs, conductor);
                break;

            case INACTIVE:
                if (isDrained())
                {
                    state = State.LINGER;
                    timeOfLastStateChangeNs = timeNs;
                    conductor.transitionToLinger(this);
                }
                break;

            case LINGER:
                if (hasNoSubscribers() || ((timeOfLastStateChangeNs + imageLivenessTimeoutNs) - timeNs < 0))
                {
                    state = State.DONE;
                    conductor.cleanupImage(this);
                }
                break;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasReachedEndOfLife()
    {
        return State.DONE == state;
    }

    private boolean isDrained()
    {
        final long rebuildPosition = this.rebuildPosition.get();

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            if (subscriberPosition.getVolatile() < rebuildPosition)
            {
                return false;
            }
        }

        return true;
    }

    private boolean hasNoSubscribers()
    {
        return subscriberPositions.length == 0;
    }

    private boolean isFlowControlUnderRun(final long packetPosition)
    {
        final boolean isFlowControlUnderRun = packetPosition < lastSmPosition;

        if (isFlowControlUnderRun)
        {
            flowControlUnderRuns.incrementOrdered();
        }

        return isFlowControlUnderRun;
    }

    private boolean isFlowControlOverRun(final long proposedPosition)
    {
        final boolean isFlowControlOverRun = proposedPosition > lastSmWindowLimit;

        if (isFlowControlOverRun)
        {
            flowControlOverRuns.incrementOrdered();
        }

        return isFlowControlOverRun;
    }

    private void cleanBufferTo(final long position)
    {
        final long cleanPosition = this.cleanPosition;
        if (position > cleanPosition)
        {
            final int bytesForCleaning = (int)(position - cleanPosition);
            final UnsafeBuffer dirtyTerm = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
            final int termOffset = (int)cleanPosition & termLengthMask;
            final int length = Math.min(bytesForCleaning, dirtyTerm.capacity() - termOffset);

            dirtyTerm.setMemory(termOffset, length - SIZE_OF_LONG, (byte)0);
            dirtyTerm.putLongOrdered(termOffset + (length - SIZE_OF_LONG), 0);
            this.cleanPosition = cleanPosition + length;
        }
    }

    private void trackConnection(final int transportIndex, final InetSocketAddress srcAddress, final long nowNs)
    {
        imageConnections = ArrayUtil.ensureCapacity(imageConnections, transportIndex + 1);
        ImageConnection imageConnection = imageConnections[transportIndex];

        if (null == imageConnection)
        {
            imageConnection = new ImageConnection(nowNs, srcAddress);
            imageConnections[transportIndex] = imageConnection;
        }

        imageConnection.timeOfLastActivityNs = nowNs;
        imageConnection.timeOfLastFrameNs = nowNs;
    }

    private boolean allEos(final int transportIndex)
    {
        imageConnections[transportIndex].isEos = true;

        for (int i = 0, length = imageConnections.length; i < length; i++)
        {
            final ImageConnection imageConnection = imageConnections[i];

            if (null != imageConnection && !imageConnection.isEos)
            {
                return false;
            }
            else if (null == imageConnection && channelEndpoint.hasDestination(i))
            {
                return false;
            }
        }

        return true;
    }

    private void state(final State state)
    {
        timeOfLastStateChangeNs = cachedNanoClock.nanoTime();
        this.state = state;
    }

    private void scheduleStatusMessage(final long nowNs, final long smPosition, final int receiverWindowLength)
    {
        final long changeNumber = beginSmChange + 1;
        beginSmChange = changeNumber;

        nextSmPosition = smPosition;
        nextSmReceiverWindowLength = receiverWindowLength;

        endSmChange = changeNumber;

        timeOfLastStatusMessageScheduleNs = nowNs;
    }

    private void checkUntetheredSubscriptions(final long nowNs, final DriverConductor conductor)
    {
        final ArrayList<UntetheredSubscription> untetheredSubscriptions = this.untetheredSubscriptions;
        final int untetheredSubscriptionsSize = untetheredSubscriptions.size();
        if (0 == untetheredSubscriptionsSize)
        {
            return;
        }

        long maxConsumerPosition = 0;
        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            final long position = subscriberPosition.getVolatile();
            if (position > maxConsumerPosition)
            {
                maxConsumerPosition = position;
            }
        }

        final int windowLength = nextSmReceiverWindowLength;
        final long untetheredWindowLimit = (maxConsumerPosition - windowLength) + (windowLength >> 3);

        for (int lastIndex = untetheredSubscriptionsSize - 1, i = lastIndex; i >= 0; i--)
        {
            final UntetheredSubscription untethered = untetheredSubscriptions.get(i);
            switch (untethered.state)
            {
                case UntetheredSubscription.ACTIVE:
                    if (untethered.position.getVolatile() > untetheredWindowLimit)
                    {
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    else if ((untethered.timeOfLastUpdateNs + untetheredWindowLimitTimeoutNs) - nowNs <= 0)
                    {
                        conductor.notifyUnavailableImageLink(correlationId, untethered.subscriptionLink);
                        untethered.state = UntetheredSubscription.LINGER;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;

                case UntetheredSubscription.LINGER:
                    if ((untethered.timeOfLastUpdateNs + untetheredWindowLimitTimeoutNs) - nowNs <= 0)
                    {
                        subscriberPositions = ArrayUtil.remove(subscriberPositions, untethered.position);
                        untethered.state = UntetheredSubscription.RESTING;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;

                case UntetheredSubscription.RESTING:
                    if ((untethered.timeOfLastUpdateNs + untetheredRestingTimeoutNs) - nowNs <= 0)
                    {
                        subscriberPositions = ArrayUtil.add(subscriberPositions, untethered.position);
                        conductor.notifyAvailableImageLink(
                            correlationId,
                            sessionId,
                            untethered.subscriptionLink,
                            untethered.position.id(),
                            rebuildPosition.get(),
                            rawLog.fileName(),
                            Configuration.sourceIdentity(sourceAddress));
                        untethered.state = UntetheredSubscription.ACTIVE;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;
            }
        }
    }

    private void updateActiveTransportCount()
    {
        final long nowNs = cachedNanoClock.nanoTime();
        int activeTransportCount = 0;

        for (final ImageConnection imageConnection : imageConnections)
        {
            if (null != imageConnection && nowNs < (imageConnection.timeOfLastFrameNs + imageLivenessTimeoutNs))
            {
                activeTransportCount++;
            }
        }

        LogBufferDescriptor.activeTransportCount(rawLog.metaData(), activeTransportCount);
    }

    private ReadablePosition[] positionArray(final ArrayList<SubscriberPosition> subscriberPositions, final long nowNs)
    {
        final int size = subscriberPositions.size();
        final ReadablePosition[] positions = new ReadablePosition[subscriberPositions.size()];

        for (int i = 0; i < size; i++)
        {
            final SubscriberPosition subscriberPosition = subscriberPositions.get(i);
            positions[i] = subscriberPosition.position();

            if (!subscriberPosition.subscription().isTether())
            {
                untetheredSubscriptions.add(new UntetheredSubscription(
                    subscriberPosition.subscription(), subscriberPosition.position(), nowNs));
            }
        }

        return positions;
    }
}
