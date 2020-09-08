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
#ifndef AERON_DESTINATION_MESSAGE_FLYWEIGHT_H
#define AERON_DESTINATION_MESSAGE_FLYWEIGHT_H

#include <cstdint>
#include <string>
#include <cstddef>
#include "CorrelatedMessageFlyweight.h"

namespace aeron { namespace command
{

#pragma pack(push)
#pragma pack(4)
struct DestinationMessageDefn
{
    CorrelatedMessageDefn correlatedMessage;
    std::int64_t registrationId;
    std::int32_t channelLength;
    std::int8_t channelData[1];
};
#pragma pack(pop)

class DestinationMessageFlyweight : public CorrelatedMessageFlyweight
{
public:
    typedef DestinationMessageFlyweight this_t;

    inline DestinationMessageFlyweight(concurrent::AtomicBuffer &buffer, util::index_t offset) :
        CorrelatedMessageFlyweight(buffer, offset),
        m_struct(overlayStruct<DestinationMessageDefn>(0))
    {
    }

    inline std::int64_t registrationId() const
    {
        return m_struct.registrationId;
    }

    inline DestinationMessageFlyweight &registrationId(std::int64_t value)
    {
        m_struct.registrationId = value;
        return *this;
    }

    inline std::string channel() const
    {
        return stringGet(static_cast<util::index_t>(offsetof(DestinationMessageDefn, channelLength)));
    }

    inline this_t &channel(const std::string &value)
    {
        stringPut(static_cast<util::index_t>(offsetof(DestinationMessageDefn, channelLength)), value);
        return *this;
    }

    inline util::index_t length() const
    {
        return static_cast<util::index_t>(offsetof(DestinationMessageDefn, channelData) + m_struct.channelLength);
    }

private:
    DestinationMessageDefn &m_struct;
};

}}

#endif
