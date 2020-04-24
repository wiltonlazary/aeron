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

#ifndef AERON_DLOPEN_H
#define AERON_DLOPEN_H

#include <util/aeron_platform.h>

#if defined(AERON_COMPILER_GCC)

#include <dlfcn.h>
#include <stddef.h>

#define aeron_dlsym dlsym
#define aeron_dlopen(x) dlopen(x, RTLD_LAZY)
#define aeron_dlerror dlerror

const char *aeron_dlinfo(const void *addr, char *buffer, size_t max_buffer_length);

#elif defined(AERON_COMPILER_MSVC) && defined(AERON_CPU_X64)

#include <WinSock2.h>
#include <windows.h>

#define RTLD_DEFAULT ((HMODULE)-123)
#define RTLD_NEXT ((HMODULE)-124)

void* aeron_dlsym(HMODULE module, LPCSTR name);
HMODULE aeron_dlopen(LPCSTR filename);
char* aeron_dlerror();
const char *aeron_dlinfo(const void* addr, char* buffer, size_t max_buffer_length);

#else
#error Unsupported platform!
#endif

#endif //AERON_DLOPEN_H
