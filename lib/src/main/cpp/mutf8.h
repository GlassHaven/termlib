/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <cstdint>
#include <cstdlib>
#include <string>

#ifndef CB_TERM_MUTF8_H
#define CB_TERM_MUTF8_H

char* utf8_to_mutf8(const char* utf8_in, size_t len, size_t* out_len);
char* mutf8_to_utf8(const char* mutf8_in, size_t len, size_t* out_len);

/**
 * Decode UTF-8 into UTF-16, substituting U+FFFD for anything malformed.
 *
 * JNI's NewStringUTF aborts the whole process when handed bytes that are not
 * valid modified UTF-8, so it must never see untrusted input. An OSC payload is
 * whatever the remote program chose to emit — from a non-UTF-8 Windows console
 * that need not be valid UTF-8 at all. NewString takes UTF-16 and has no such
 * failure mode, so decoding here removes the abort rather than narrowing the
 * window for it.
 *
 * Lossy on purpose: a terminal that shows a replacement character for a
 * mis-encoded title is behaving correctly; one that kills the app is not.
 *
 * Unlike utf8_to_mutf8 this makes no assumption that the input is well-formed —
 * every multi-byte sequence is length-checked against the end of the buffer
 * before its continuation bytes are read.
 */
std::u16string utf8_to_utf16_lossy(const char* in, size_t len);

#endif //CB_TERM_MUTF8_H
