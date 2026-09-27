/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
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
package org.connectbot.terminal

import androidx.compose.ui.geometry.Offset

/**
 * Touch-sampling helpers ported from upstream (6209b978 "finger wobble and
 * edge handling") into the fork's own gesture pipeline. Upstream wires these
 * through its SelectionTouch-based handler; here they wrap the fork's
 * awaitEachGesture paths instead, so the pure functions are kept verbatim and
 * the fork's own autoscroll / magnifier models are left alone.
 */

/** Preserve the grabbed cell, but map the remaining finger travel to both viewport edges. */
internal fun selectionHandleDragPosition(position: Offset, down: Offset, anchor: Offset, width: Float, height: Float, inset: Float): Offset {
    fun axis(value: Float, start: Float, target: Float, length: Float): Float {
        if (length <= 0f) return 0f
        val origin = start.coerceIn(0f, length)
        val margin = minOf(inset, length / 4f)
        // Leave travel on either side even when the handle was grabbed inside the edge band.
        val left = minOf(margin, origin / 2f)
        val right = maxOf(length - margin, (length + origin) / 2f)
        val destination = target.coerceIn(0f, length)
        return when {
            value == origin -> destination
            value < origin -> if (origin == left) 0f else destination * ((value - left) / (origin - left)).coerceIn(0f, 1f)
            else -> if (right == origin) length else destination + (length - destination) * ((value - origin) / (right - origin)).coerceIn(0f, 1f)
        }
    }
    return Offset(axis(position.x, down.x, anchor.x, width), axis(position.y, down.y, anchor.y, height))
}

/** Makes the outermost cells reachable without putting the center of a finger on the bezel. */
internal fun edgeReachPosition(position: Offset, width: Float, height: Float, inset: Float, transition: Float): Offset = Offset(
    edgeReachAxis(position.x, width, inset, transition),
    edgeReachAxis(position.y, height, inset, transition),
)

internal fun edgeReachAxis(value: Float, length: Float, inset: Float, transition: Float): Float {
    if (length <= 0f) return 0f
    val zone = minOf(transition, length / 2f)
    val margin = minOf(inset, zone / 2f)
    val x = value.coerceIn(0f, length)
    if (zone <= 0f || margin <= 0f) return x
    return when {
        x <= margin -> 0f
        x < zone -> (x - margin) * zone / (zone - margin)
        x <= length - zone -> x
        x < length - margin -> length - edgeReachAxis(length - x, length, inset, transition)
        else -> length
    }
}

/** Stores distinct selection states so a brief touch wobble can be undone at lift. */
internal class SelectionReleaseFilter<T>(private val capacity: Int = 5) {
    private data class Sample<T>(val value: T, val timeMillis: Long)
    private val samples = ArrayDeque<Sample<T>>()

    fun record(value: T, timeMillis: Long) {
        if (samples.lastOrNull()?.value == value) return
        samples.addLast(Sample(value, timeMillis))
        if (samples.size > capacity) samples.removeFirst()
    }

    fun resultAtRelease(timeMillis: Long): T? {
        if (samples.isEmpty()) return null
        val history = samples.toList()
        val older = history.indexOfLast { timeMillis - it.timeMillis >= 150L }
        return if (older in 0 until history.lastIndex && timeMillis - history[older].timeMillis > 350L) {
            history[older].value
        } else {
            history.last().value
        }
    }
}