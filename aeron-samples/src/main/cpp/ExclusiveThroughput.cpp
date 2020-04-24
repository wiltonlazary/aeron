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

#include <cstdio>
#include <csignal>
#include <thread>

#define __STDC_FORMAT_MACROS
#include <cinttypes>

#include "util/CommandOptionParser.h"
#include "concurrent/BusySpinIdleStrategy.h"
#include "Aeron.h"
#include "Configuration.h"
#include "RateReporter.h"
#include "FragmentAssembler.h"


using namespace aeron::util;
using namespace aeron;

std::atomic<bool> running (true);

void sigIntHandler(int param)
{
    running = false;
}

static const char optHelp     = 'h';
static const char optPrefix   = 'p';
static const char optChannel  = 'c';
static const char optStreamId = 's';
static const char optMessages = 'm';
static const char optLinger   = 'l';
static const char optLength   = 'L';
static const char optProgress = 'P';
static const char optFrags    = 'f';

struct Settings
{
    std::string dirPrefix = "";
    std::string channel = samples::configuration::DEFAULT_CHANNEL;
    std::int32_t streamId = samples::configuration::DEFAULT_STREAM_ID;
    long numberOfMessages = samples::configuration::DEFAULT_NUMBER_OF_MESSAGES;
    int messageLength = samples::configuration::DEFAULT_MESSAGE_LENGTH;
    int lingerTimeoutMs = samples::configuration::DEFAULT_LINGER_TIMEOUT_MS;
    int fragmentCountLimit = samples::configuration::DEFAULT_FRAGMENT_COUNT_LIMIT;
    bool progress = samples::configuration::DEFAULT_PUBLICATION_RATE_PROGRESS;
};

Settings parseCmdLine(CommandOptionParser& cp, int argc, char** argv)
{
    cp.parse(argc, argv);
    if (cp.getOption(optHelp).isPresent())
    {
        cp.displayOptionsHelp(std::cout);
        exit(0);
    }

    Settings s;

    s.dirPrefix = cp.getOption(optPrefix).getParam(0, s.dirPrefix);
    s.channel = cp.getOption(optChannel).getParam(0, s.channel);
    s.streamId = cp.getOption(optStreamId).getParamAsInt(0, 1, INT32_MAX, s.streamId);
    s.numberOfMessages = cp.getOption(optMessages).getParamAsLong(0, 0, LONG_MAX, s.numberOfMessages);
    s.messageLength = cp.getOption(optLength).getParamAsInt(0, sizeof(std::int64_t), INT32_MAX, s.messageLength);
    s.lingerTimeoutMs = cp.getOption(optLinger).getParamAsInt(0, 0, 60 * 60 * 1000, s.lingerTimeoutMs);
    s.fragmentCountLimit = cp.getOption(optFrags).getParamAsInt(0, 1, INT32_MAX, s.fragmentCountLimit);
    s.progress = cp.getOption(optProgress).isPresent();

    return s;
}

std::atomic<bool> printingActive;

void printRate(double messagesPerSec, double bytesPerSec, long totalFragments, long totalBytes)
{
    if (printingActive)
    {
        std::printf(
            "%.04g msgs/sec, %.04g bytes/sec, totals %ld messages %ld MB payloads\n",
            messagesPerSec, bytesPerSec, totalFragments, totalBytes / (1024 * 1024));
    }
}

fragment_handler_t rateReporterHandler(RateReporter& rateReporter)
{
    return
        [&rateReporter](AtomicBuffer&, util::index_t, util::index_t length, Header&)
        {
            rateReporter.onMessage(1, length);
        };
}

inline bool isRunning()
{
    return std::atomic_load_explicit(&running, std::memory_order_relaxed);
}

int main(int argc, char **argv)
{
    CommandOptionParser cp;
    cp.addOption(CommandOption(optHelp,     0, 0, "                Displays help information."));
    cp.addOption(CommandOption(optProgress, 0, 0, "                Print rate progress while sending."));
    cp.addOption(CommandOption(optPrefix,   1, 1, "dir             Prefix directory for aeron driver."));
    cp.addOption(CommandOption(optChannel,  1, 1, "channel         Channel."));
    cp.addOption(CommandOption(optStreamId, 1, 1, "streamId        Stream ID."));
    cp.addOption(CommandOption(optMessages, 1, 1, "number          Number of Messages."));
    cp.addOption(CommandOption(optLength,   1, 1, "length          Length of Messages."));
    cp.addOption(CommandOption(optLinger,   1, 1, "milliseconds    Linger timeout in milliseconds."));
    cp.addOption(CommandOption(optFrags,    1, 1, "limit           Fragment Count Limit."));

    signal(SIGINT, sigIntHandler);

    try
    {
        Settings settings = parseCmdLine(cp, argc, argv);

        std::cout << "Subscribing to channel " << settings.channel << " on Stream ID " << settings.streamId << std::endl;

        std::cout << "Streaming " << toStringWithCommas(settings.numberOfMessages) << " messages of payload length "
            << settings.messageLength << " bytes to "
            << settings.channel << " on stream ID "
            << settings.streamId << std::endl;

        aeron::Context context;

        if (!settings.dirPrefix.empty())
        {
            context.aeronDir(settings.dirPrefix);
        }

        context.newPublicationHandler(
            [](const std::string& channel, std::int32_t streamId, std::int32_t sessionId, std::int64_t correlationId)
            {
                std::cout << "Publication: " << channel << " " << correlationId << ":" << streamId << ":" << sessionId << std::endl;
            });

        context.newSubscriptionHandler(
            [](const std::string& channel, std::int32_t streamId, std::int64_t correlationId)
            {
                std::cout << "Subscription: " << channel << " " << correlationId << ":" << streamId << std::endl;
            });

        context.availableImageHandler(
            [](Image &image)
            {
                std::cout << "Available image correlationId=" << image.correlationId() << " sessionId=" << image.sessionId();
                std::cout << " at position=" << image.position() << " from " << image.sourceIdentity() << std::endl;
            });

        context.unavailableImageHandler(
            [](Image &image)
            {
                std::cout << "Unavailable image on correlationId=" << image.correlationId() << " sessionId=" << image.sessionId();
                std::cout << " at position=" << image.position() << std::endl;
            });

        Aeron aeron(context);

        std::int64_t subscriptionId = aeron.addSubscription(settings.channel, settings.streamId);
        std::int64_t publicationId = aeron.addExclusivePublication(settings.channel, settings.streamId);

        std::shared_ptr<Subscription> subscription = aeron.findSubscription(subscriptionId);
        while (!subscription)
        {
            std::this_thread::yield();
            subscription = aeron.findSubscription(subscriptionId);
        }

        std::shared_ptr<ExclusivePublication> publication = aeron.findExclusivePublication(publicationId);
        while (!publication)
        {
            std::this_thread::yield();
            publication = aeron.findExclusivePublication(publicationId);
        }

        RateReporter rateReporter(std::chrono::seconds(1), printRate);
        FragmentAssembler fragmentAssembler(rateReporterHandler(rateReporter));
        auto handler = fragmentAssembler.handler();

        std::shared_ptr<std::thread> rateReporterThread;

        ExclusivePublication *publicationPtr = publication.get();
        Subscription *subscriptionPtr = subscription.get();

        if (settings.progress)
        {
            rateReporterThread = std::make_shared<std::thread>([&rateReporter](){ rateReporter.run(); });
        }

        std::uint64_t failedPolls = 0;
        std::uint64_t successfulPolls = 0;

        std::thread pollThread(
            [&]()
            {
                while (!subscriptionPtr->isConnected())
                {
                    std::this_thread::yield();
                }

                std::shared_ptr<Image> imageSharedPtr = subscriptionPtr->imageByIndex(0);
                Image& image = *imageSharedPtr;
                BusySpinIdleStrategy idleStrategy;
                
                while (isRunning())
                {
                    int fragments = image.poll(handler, settings.fragmentCountLimit);
                    if (0 == fragments)
                    {
                        ++failedPolls;
                    }
                    else
                    {
                        ++successfulPolls;
                    }
                    
                    idleStrategy.idle(fragments);
                }
            });

        do
        {
            BufferClaim bufferClaim;
            std::uint64_t backPressureCount = 0;

            printingActive = true;

            if (nullptr == rateReporterThread)
            {
                rateReporter.reset();
            }

            for (long i = 0; i < settings.numberOfMessages && isRunning(); i++)
            {
                while (publicationPtr->tryClaim(settings.messageLength, bufferClaim) < 0L)
                {
                    ++backPressureCount;
                    if (!isRunning())
                    {
                        break;
                    }
                }

                bufferClaim.buffer().putInt64(bufferClaim.offset(), i);
                bufferClaim.commit();
            }

            if (nullptr == rateReporterThread)
            {
                rateReporter.report();
            }

            std::cout << "Done streaming." << std::endl;
            std::cout << "Publication back pressure ratio ";
            std::cout << ((double)backPressureCount / settings.numberOfMessages) << std::endl;

            std::cout << "Subscription failure ratio ";
            std::cout << ((double)failedPolls / (failedPolls + successfulPolls)) << std::endl;

            if (isRunning() && settings.lingerTimeoutMs > 0)
            {
                std::cout << "Lingering for " << settings.lingerTimeoutMs << " milliseconds." << std::endl;
                std::this_thread::sleep_for(std::chrono::milliseconds(settings.lingerTimeoutMs));
            }

            printingActive = false;
        }
        while (isRunning() && continuationBarrier("Execute again?"));

        running = false;
        rateReporter.halt();

        pollThread.join();

        if (nullptr != rateReporterThread)
        {
            rateReporterThread->join();
        }
    }
    catch (const CommandOptionException& e)
    {
        std::cerr << "ERROR: " << e.what() << std::endl << std::endl;
        cp.displayOptionsHelp(std::cerr);
        return -1;
    }
    catch (const SourcedException& e)
    {
        std::cerr << "FAILED: " << e.what() << " : " << e.where() << std::endl;
        return -1;
    }
    catch (const std::exception& e)
    {
        std::cerr << "FAILED: " << e.what() << " : " << std::endl;
        return -1;
    }

    return 0;
}
