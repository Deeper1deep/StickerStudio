package com.stickerstudio.app.sticker

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One entry of the animated sticker's timeline: which moment of the source clip to show and for
 * how long. The timeline only stores references, so a long clip never has to be held in memory.
 */
data class TimelineFrame(val sourceMs: Long, val durationMs: Int)

/** Pure list operations behind the frame editor (covered by unit tests). */
object FramePlan {

    const val MIN_FRAME_MS = 20
    const val MAX_FRAME_MS = 2000
    const val MAX_TOTAL_MS = 10_000
    const val MIN_FRAMES = 2
    const val MAX_FRAMES = 300

    fun uniform(startMs: Long, frameMs: Int, count: Int): List<TimelineFrame> =
        List(count.coerceIn(MIN_FRAMES, MAX_FRAMES)) { TimelineFrame(startMs + it.toLong() * frameMs, frameMs) }

    fun totalMs(frames: List<TimelineFrame>): Long = frames.sumOf { it.durationMs.toLong() }

    fun delete(frames: List<TimelineFrame>, index: Int): List<TimelineFrame> {
        if (index !in frames.indices || frames.size <= MIN_FRAMES) return frames
        return frames.toMutableList().apply { removeAt(index) }
    }

    /** Inserts a copy right after [index]; refused when it would exceed the frame or 10 s limit. */
    fun duplicate(frames: List<TimelineFrame>, index: Int): List<TimelineFrame> {
        val f = frames.getOrNull(index) ?: return frames
        if (frames.size >= MAX_FRAMES || totalMs(frames) + f.durationMs > MAX_TOTAL_MS) return frames
        return frames.toMutableList().apply { add(index + 1, f) }
    }

    /** Moves a frame by [delta] places (negative = earlier). */
    fun move(frames: List<TimelineFrame>, index: Int, delta: Int): List<TimelineFrame> {
        val to = index + delta
        if (index !in frames.indices || to !in frames.indices || delta == 0) return frames
        return frames.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** New length for one frame, kept inside the allowed range and the 10 s total. */
    fun withDuration(frames: List<TimelineFrame>, index: Int, ms: Int): List<TimelineFrame> {
        val f = frames.getOrNull(index) ?: return frames
        val others = totalMs(frames) - f.durationMs
        val room = (MAX_TOTAL_MS - others).toInt()
        val value = ms.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS).coerceAtMost(max(room, MIN_FRAME_MS))
        return frames.toMutableList().apply { set(index, f.copy(durationMs = value)) }
    }

    /** Speeds up (>1) or slows down (<1) the whole animation. */
    fun scaleSpeed(frames: List<TimelineFrame>, factor: Float): List<TimelineFrame> {
        if (factor <= 0f) return frames
        val scaled = frames.map { it.copy(durationMs = (it.durationMs / factor).roundToInt().coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)) }
        return clampTotal(scaled, MAX_TOTAL_MS)
    }

    /** Drops frames from the end until the animation fits [maxMs] (always keeps the minimum). */
    fun clampTotal(frames: List<TimelineFrame>, maxMs: Int): List<TimelineFrame> {
        var sum = 0L
        val out = ArrayList<TimelineFrame>(frames.size)
        for (f in frames) {
            if (out.size >= MIN_FRAMES && sum + f.durationMs > maxMs) break
            out += f
            sum += f.durationMs
        }
        return out
    }

    /**
     * Fewer, longer frames (used by the size-reduction retries): frames are merged until each
     * output frame lasts at least [minStepMs]. The total length stays the same.
     */
    fun thin(frames: List<TimelineFrame>, minStepMs: Int): List<TimelineFrame> {
        if (minStepMs <= 0 || frames.size <= MIN_FRAMES) return frames
        val out = ArrayList<TimelineFrame>()
        var head: TimelineFrame? = null
        var acc = 0
        for (f in frames) {
            if (head == null) { head = f; acc = f.durationMs } else acc += f.durationMs
            if (acc >= minStepMs) {
                out += TimelineFrame(head.sourceMs, acc)
                head = null
                acc = 0
            }
        }
        if (head != null) {
            if (out.isEmpty()) out += TimelineFrame(head.sourceMs, acc)
            else out[out.lastIndex] = out.last().copy(durationMs = out.last().durationMs + acc)
        }
        return if (out.size >= MIN_FRAMES) out else frames
    }
}
