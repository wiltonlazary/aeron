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

#if defined(__linux__)
#define _BSD_SOURCE
#define _GNU_SOURCE
#endif

#include <time.h>
#include <stdio.h>
#include <inttypes.h>
#include <errno.h>
#include <string.h>

#include "util/aeron_strutil.h"
#include "aeron_windows.h"

void aeron_format_date(char *str, size_t count, int64_t timestamp)
{
    char time_buffer[80];
    char msec_buffer[8];
    char tz_buffer[8];
    struct tm time;
    time_t just_seconds = timestamp / 1000;
    int64_t msec_after_sec = timestamp % 1000;

    localtime_r(&just_seconds, &time);

    strftime(time_buffer, sizeof(time_buffer) - 1, "%Y-%m-%d %H:%M:%S.", &time);
    snprintf(msec_buffer, sizeof(msec_buffer) - 1, "%03" PRId64, msec_after_sec);
    strftime(tz_buffer, sizeof(tz_buffer) - 1, "%z", &time);

    snprintf(str, count, "%s%s%s", time_buffer, msec_buffer, tz_buffer);
}

void aeron_format_to_hex(char *str, size_t str_length, uint8_t *data, size_t data_len)
{
    static char table[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    size_t j = 0;

    for (size_t i = 0; i < data_len && j < str_length; i++)
    {
        char c_high = table[(data[i] >> 4) & 0x0F];
        char c_low = table[data[i] & 0x0F];

        str[j++] = c_high;
        str[j++] = c_low;
    }

    str[j] = '\0';
}

int aeron_tokenise(char *input, const char delimiter, const int max_tokens, char **tokens)
{
    if (NULL == input)
    {
        return -EINVAL;
    }

    const size_t len = strlen(input);

    if (INT32_MAX < len)
    {
        return -EINVAL;
    }

    if (0 == len)
    {
        return 0;
    }

    int num_tokens = 0;

    for (int i = (int)len; --i != -1;)
    {
        if (delimiter == input[i])
        {
            input[i] = '\0';
        }

        if (0 == i && '\0' != input[i])
        {
            if (max_tokens <= num_tokens)
            {
                num_tokens = -ERANGE;
                break;
            }

            tokens[num_tokens] = &input[i];
            num_tokens++;
        }
        else if ('\0' == input[i] && '\0' != input[i + 1])
        {
            if (max_tokens <= num_tokens)
            {
                num_tokens = -ERANGE;
                break;
            }

            tokens[num_tokens] = &input[i + 1];
            num_tokens++;
        }
    }

    return num_tokens;
}

extern uint64_t aeron_fnv_64a_buf(uint8_t *buf, size_t len);
