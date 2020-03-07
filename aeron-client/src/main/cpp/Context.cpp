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

#define _DISABLE_EXTENDED_ALIGNED_STORAGE
#include "Aeron.h"
#include "CncFileDescriptor.h"

using namespace aeron;
using namespace aeron::util;

void Context::requestDriverTermination(
    const std::string& directory, const std::uint8_t *tokenBuffer, std::size_t tokenLength)
{
    const std::string cncFilename = directory + AERON_PATH_SEP + CncFileDescriptor::CNC_FILE;

    if (MemoryMappedFile::getFileSize(cncFilename.c_str()) > 0)
    {
        MemoryMappedFile::ptr_t cncFile = MemoryMappedFile::mapExisting(cncFilename.c_str());

        const std::int32_t cncVersion = CncFileDescriptor::cncVersionVolatile(cncFile);

        if (semanticVersionMajor(cncVersion) != semanticVersionMajor(CncFileDescriptor::CNC_VERSION))
        {
            throw AeronException("Aeron CnC version does not match:"
                " app=" + semanticVersionToString(CncFileDescriptor::CNC_VERSION) +
                " file=" + semanticVersionToString(cncVersion),
                SOURCEINFO);
        }

        AtomicBuffer toDriverBuffer(CncFileDescriptor::createToDriverBuffer(cncFile));
        ManyToOneRingBuffer ringBuffer(toDriverBuffer);
        DriverProxy driverProxy(ringBuffer);

        driverProxy.terminateDriver(tokenBuffer, tokenLength);
    }
}
