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

import io.aeron.CommonContext;
import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.LogBufferUnblocker;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import java.util.ArrayList;

import static io.aeron.driver.status.SystemCounterDescriptor.UNBLOCKED_PUBLICATIONS;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * Encapsulation of a stream used directly between publishers and subscribers for IPC over shared memory.
 */
public final class IpcPublication implements DriverManagedResource, Subscribable
{
    enum State
    {
        ACTIVE, INACTIVE, LINGER
    }

    private static final ReadablePosition[] EMPTY_POSITIONS = new ReadablePosition[0];

    private final long registrationId;
    private final long unblockTimeoutNs;
    private final long untetheredWindowLimitTimeoutNs;
    private final long untetheredRestingTimeoutNs;
    private final long tag;
    private final int sessionId;
    private final int streamId;
    private final int tripGain;
    private final int termBufferLength;
    private final int termWindowLength;
    private final int positionBitsToShift;
    private final int initialTermId;
    private final ErrorHandler errorHandler;
    private long tripLimit;
    private long consumerPosition;
    private long lastConsumerPosition;
    private long timeOfLastConsumerPositionUpdateNs;
    private long cleanPosition;
    private int refCount = 0;
    private boolean reachedEndOfLife = false;
    private final boolean isExclusive;
    private State state = State.ACTIVE;
    private final UnsafeBuffer[] termBuffers;
    private final ArrayList<UntetheredSubscription> untetheredSubscriptions = new ArrayList<>();
    private ReadablePosition[] subscriberPositions = EMPTY_POSITIONS;
    private final Position publisherPos;
    private final Position publisherLimit;
    private final UnsafeBuffer metaDataBuffer;
    private final RawLog rawLog;
    private final AtomicCounter unblockedPublications;

    public IpcPublication(
        final long registrationId,
        final long tag,
        final int sessionId,
        final int streamId,
        final Position publisherPos,
        final Position publisherLimit,
        final RawLog rawLog,
        final int termWindowLength,
        final long unblockTimeoutNs,
        final long untetheredWindowLimitTimeoutNs,
        final long untetheredRestingTimeoutNs,
        final long nowNs,
        final SystemCounters systemCounters,
        final boolean isExclusive,
        final ErrorHandler errorHandler)
    {
        this.registrationId = registrationId;
        this.tag = tag;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.isExclusive = isExclusive;
        this.termBuffers = rawLog.termBuffers();
        this.initialTermId = initialTermId(rawLog.metaData());
        this.errorHandler = errorHandler;

        final int termLength = rawLog.termLength();
        this.termBufferLength = termLength;
        this.positionBitsToShift = LogBufferDescriptor.positionBitsToShift(termLength);
        this.termWindowLength = termWindowLength;
        this.tripGain = termWindowLength >> 3;
        this.publisherPos = publisherPos;
        this.publisherLimit = publisherLimit;
        this.rawLog = rawLog;
        this.unblockTimeoutNs = unblockTimeoutNs;
        this.untetheredWindowLimitTimeoutNs = untetheredWindowLimitTimeoutNs;
        this.untetheredRestingTimeoutNs = untetheredRestingTimeoutNs;
        this.unblockedPublications = systemCounters.get(UNBLOCKED_PUBLICATIONS);
        this.metaDataBuffer = rawLog.metaData();

        consumerPosition = producerPosition();
        lastConsumerPosition = consumerPosition;
        cleanPosition = consumerPosition;
        timeOfLastConsumerPositionUpdateNs = nowNs;
    }

    public int sessionId()
    {
        return sessionId;
    }

    public int streamId()
    {
        return streamId;
    }

    public long registrationId()
    {
        return registrationId;
    }

    public long tag()
    {
        return tag;
    }

    public boolean isExclusive()
    {
        return isExclusive;
    }

    public RawLog rawLog()
    {
        return rawLog;
    }

    public int publisherLimitId()
    {
        return publisherLimit.id();
    }

    public int termBufferLength()
    {
        return termBufferLength;
    }

    public int mtuLength()
    {
        return LogBufferDescriptor.mtuLength(metaDataBuffer);
    }

    public boolean free()
    {
        return rawLog.free();
    }

    public void close()
    {
        CloseHelper.close(errorHandler, publisherPos);
        CloseHelper.close(errorHandler, publisherLimit);
        CloseHelper.closeAll(errorHandler, subscriberPositions);

        for (int i = 0, size = untetheredSubscriptions.size(); i < size; i++)
        {
            final UntetheredSubscription untetheredSubscription = untetheredSubscriptions.get(i);
            if (UntetheredSubscription.RESTING == untetheredSubscription.state)
            {
                CloseHelper.close(errorHandler, untetheredSubscription.position);
            }
        }

        CloseHelper.close(errorHandler, rawLog);
    }

    public void addSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition)
    {
        LogBufferDescriptor.isConnected(metaDataBuffer, true);
        subscriberPositions = ArrayUtil.add(subscriberPositions, subscriberPosition);

        if (!subscriptionLink.isTether())
        {
            untetheredSubscriptions.add(new UntetheredSubscription(
                subscriptionLink, subscriberPosition, timeOfLastConsumerPositionUpdateNs));
        }
    }

    public void removeSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition subscriberPosition)
    {
        consumerPosition = Math.max(consumerPosition, subscriberPosition.getVolatile());
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
            LogBufferDescriptor.isConnected(metaDataBuffer, false);
        }
    }

    public void onTimeEvent(final long timeNs, final long timeMs, final DriverConductor conductor)
    {
        switch (state)
        {
            case ACTIVE:
            {
                checkUntetheredSubscriptions(timeNs, conductor);
                final long producerPosition = producerPosition();
                publisherPos.setOrdered(producerPosition);
                if (!isExclusive)
                {
                    checkForBlockedPublisher(producerPosition, timeNs);
                }
                break;
            }

            case INACTIVE:
            {
                final long producerPosition = producerPosition();
                publisherPos.setOrdered(producerPosition);
                if (isDrained(producerPosition))
                {
                    state = State.LINGER;
                    conductor.transitionToLinger(this);
                }
                else if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, consumerPosition, termBufferLength))
                {
                    unblockedPublications.incrementOrdered();
                }
                break;
            }

            case LINGER:
                reachedEndOfLife = true;
                conductor.cleanupIpcPublication(this);
                break;
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    public void incRef()
    {
        ++refCount;
    }

    public void decRef()
    {
        if (0 == --refCount)
        {
            state = State.INACTIVE;
            final long producerPosition = producerPosition();
            publisherLimit.setOrdered(producerPosition);
            LogBufferDescriptor.endOfStreamPosition(metaDataBuffer, producerPosition);
        }
    }

    int updatePublisherLimit()
    {
        int workCount = 0;
        long minSubscriberPosition = Long.MAX_VALUE;
        long maxSubscriberPosition = consumerPosition;

        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            final long position = subscriberPosition.getVolatile();
            minSubscriberPosition = Math.min(minSubscriberPosition, position);
            maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
        }

        if (subscriberPositions.length > 0)
        {
            if (maxSubscriberPosition > consumerPosition)
            {
                consumerPosition = maxSubscriberPosition;
            }

            final long proposedLimit = minSubscriberPosition + termWindowLength;
            if (proposedLimit > tripLimit)
            {
                cleanBufferTo(minSubscriberPosition);
                publisherLimit.setOrdered(proposedLimit);
                tripLimit = proposedLimit + tripGain;

                workCount = 1;
            }
        }
        else if (publisherLimit.get() > consumerPosition)
        {
            tripLimit = consumerPosition;
            publisherLimit.setOrdered(consumerPosition);
            cleanBufferTo(consumerPosition);
        }

        return workCount;
    }

    long joinPosition()
    {
        return consumerPosition;
    }

    long producerPosition()
    {
        final long rawTail = rawTailVolatile(metaDataBuffer);
        final int termOffset = termOffset(rawTail, termBufferLength);

        return computePosition(termId(rawTail), termOffset, positionBitsToShift, initialTermId);
    }

    long consumerPosition()
    {
        return consumerPosition;
    }

    State state()
    {
        return state;
    }

    private void checkUntetheredSubscriptions(final long nowNs, final DriverConductor conductor)
    {
        final ArrayList<UntetheredSubscription> untetheredSubscriptions = this.untetheredSubscriptions;
        final int untetheredSubscriptionsSize = untetheredSubscriptions.size();
        if (0 == untetheredSubscriptionsSize)
        {
            return;
        }

        final long untetheredWindowLimit = (consumerPosition - termWindowLength) + (termWindowLength >> 3);

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
                        conductor.notifyUnavailableImageLink(registrationId, untethered.subscriptionLink);
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
                            registrationId,
                            sessionId,
                            untethered.subscriptionLink,
                            untethered.position.id(),
                            consumerPosition,
                            rawLog.fileName(),
                            CommonContext.IPC_CHANNEL);
                        LogBufferDescriptor.isConnected(metaDataBuffer, true);
                        untethered.state = UntetheredSubscription.ACTIVE;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;
            }
        }
    }

    private boolean isDrained(final long producerPosition)
    {
        for (final ReadablePosition subscriberPosition : subscriberPositions)
        {
            if (subscriberPosition.getVolatile() < producerPosition)
            {
                return false;
            }
        }

        return true;
    }

    private void checkForBlockedPublisher(final long producerPosition, final long timeNs)
    {
        final long consumerPosition = this.consumerPosition;

        if (consumerPosition == lastConsumerPosition && isPossiblyBlocked(producerPosition, consumerPosition))
        {
            if ((timeOfLastConsumerPositionUpdateNs + unblockTimeoutNs) - timeNs < 0)
            {
                if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, consumerPosition, termBufferLength))
                {
                    unblockedPublications.incrementOrdered();
                }
            }
        }
        else
        {
            timeOfLastConsumerPositionUpdateNs = timeNs;
            lastConsumerPosition = consumerPosition;
        }
    }

    private boolean isPossiblyBlocked(final long producerPosition, final long consumerPosition)
    {
        final int producerTermCount = activeTermCount(metaDataBuffer);
        final int expectedTermCount = (int)(consumerPosition >> positionBitsToShift);

        if (producerTermCount != expectedTermCount)
        {
            return true;
        }

        return producerPosition > consumerPosition;
    }

    private void cleanBufferTo(final long position)
    {
        final long cleanPosition = this.cleanPosition;
        if (position > cleanPosition)
        {
            final UnsafeBuffer dirtyTerm = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
            final int bytesForCleaning = (int)(position - cleanPosition);
            final int bufferCapacity = termBufferLength;
            final int termOffset = (int)cleanPosition & (bufferCapacity - 1);
            final int length = Math.min(bytesForCleaning, bufferCapacity - termOffset);

            dirtyTerm.setMemory(termOffset + SIZE_OF_LONG, length - SIZE_OF_LONG, (byte)0);
            dirtyTerm.putLongOrdered(termOffset, 0);
            this.cleanPosition = cleanPosition + length;
        }
    }
}
