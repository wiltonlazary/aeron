package io.aeron.test;

import io.aeron.CommonContext;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.errors.ErrorConsumer;
import org.agrona.concurrent.errors.ErrorLogReader;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ErrorReportTestUtil
{
    public static void waitForErrorToOccur(
        final String aeronDirectoryName,
        final Matcher<String> matcher,
        final IdleStrategy retryIdle) throws IOException
    {
        final File cncFile = CommonContext.newCncFile(aeronDirectoryName);
        assertTrue(cncFile.exists());

        MappedByteBuffer cncByteBuffer = null;

        try (
            RandomAccessFile file = new RandomAccessFile(cncFile, "r");
            FileChannel channel = file.getChannel())
        {
            cncByteBuffer = channel.map(READ_ONLY, 0, channel.size());
            final AtomicBuffer errorLogBuffer = CommonContext.errorLogBuffer(cncByteBuffer);

            final MatcherErrorConsumer errorConsumer = new MatcherErrorConsumer(matcher);

            ErrorLogReader.read(errorLogBuffer, errorConsumer);
            if (errorConsumer.hasMatched())
            {
                return;
            }

            if (null == retryIdle)
            {
                fail(errorConsumer.toString());
            }

            while (!errorConsumer.hasMatched())
            {
                errorConsumer.reset();
                ErrorLogReader.read(errorLogBuffer, errorConsumer);

                Tests.wait(retryIdle, errorConsumer::toString);
            }
        }
        finally
        {
            IoUtil.unmap(cncByteBuffer);
        }
    }

    public static void checkErrorOccurred(
        final String aeronDirectoryName,
        final Matcher<String> matcher) throws IOException
    {
        waitForErrorToOccur(aeronDirectoryName, matcher, null);
    }

    private static class MatcherErrorConsumer implements ErrorConsumer
    {
        private final Matcher<String> matcher;
        private final List<String> encodedExceptions = new ArrayList<>();
        private boolean hasMatched = false;

        MatcherErrorConsumer(final Matcher<String> matcher)
        {
            this.matcher = matcher;
        }

        public void accept(
            final int observationCount,
            final long firstObservationTimestamp,
            final long lastObservationTimestamp,
            final String encodedException)
        {
            hasMatched = matcher.matches(encodedException);
            encodedExceptions.add(encodedException);
        }

        public void reset()
        {
            encodedExceptions.clear();
        }

        public boolean hasMatched()
        {
            return hasMatched;
        }

        public String toString()
        {
            final StringDescription description = new StringDescription();
            final String lineSeparator = System.getProperty("line.separator");

            description.appendText("Unable to match: ");
            matcher.describeTo(description);
            description.appendText(", against the following errors:");
            encodedExceptions.forEach(
                (encodedException) ->
                {
                    description.appendText(lineSeparator).appendText("  ");
                    description.appendText(encodedException);
                });
            description.appendText(lineSeparator);

            return description.toString();
        }
    }
}
