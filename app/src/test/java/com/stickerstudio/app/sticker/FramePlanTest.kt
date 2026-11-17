package com.stickerstudio.app.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlanTest {
    private fun frames(n: Int, ms: Int = 100) = FramePlan.uniform(1000, ms, n)

    @Test
    fun uniformStartsAtTheGivenTimeAndStepsByTheFrameLength() {
        val f = frames(4, 80)
        assertEquals(listOf(1000L, 1080L, 1160L, 1240L), f.map { it.sourceMs })
        assertEquals(320L, FramePlan.totalMs(f))
    }

    @Test
    fun deleteKeepsAtLeastTwoFrames() {
        val f = frames(3)
        val two = FramePlan.delete(f, 1)
        assertEquals(2, two.size)
        assertSame(two, FramePlan.delete(two, 0))
    }

    @Test
    fun duplicateInsertsACopyAfterTheFrame() {
        val f = frames(3)
        val d = FramePlan.duplicate(f, 0)
        assertEquals(4, d.size)
        assertEquals(d[0], d[1])
    }

    @Test
    fun duplicateIsRefusedAtTheTenSecondLimit() {
        val f = frames(10, 1000)             // exactly 10 s
        assertSame(f, FramePlan.duplicate(f, 0))
    }

    @Test
    fun moveReordersWithoutLosingFrames() {
        val f = frames(4)
        val m = FramePlan.move(f, 0, 2)
        assertEquals(listOf(f[1], f[2], f[0], f[3]), m)
        assertSame(f, FramePlan.move(f, 0, -1))
    }

    @Test
    fun durationStaysInRangeAndInsideTheTotal() {
        val f = frames(2, 100)
        assertEquals(FramePlan.MIN_FRAME_MS, FramePlan.withDuration(f, 0, 1)[0].durationMs)
        assertEquals(FramePlan.MAX_FRAME_MS, FramePlan.withDuration(f, 0, 99_999)[0].durationMs)
        val full = frames(9, 1000) + TimelineFrame(0, 500)       // 9.5 s
        assertEquals(1000, FramePlan.withDuration(full, 9, 5000)[9].durationMs)   // only 1 s of room
    }

    @Test
    fun speedScalesAllDurations() {
        val f = frames(3, 100)
        assertEquals(listOf(50, 50, 50), FramePlan.scaleSpeed(f, 2f).map { it.durationMs })
        assertEquals(listOf(200, 200, 200), FramePlan.scaleSpeed(f, 0.5f).map { it.durationMs })
    }

    @Test
    fun clampTotalDropsTheTailButKeepsTwoFrames() {
        val f = frames(5, 1000)
        assertEquals(3, FramePlan.clampTotal(f, 3000).size)
        assertEquals(2, FramePlan.clampTotal(f, 10).size)
    }

    @Test
    fun thinMergesFramesAndKeepsTheTotalLength() {
        val f = frames(10, 40)            // 400 ms
        val t = FramePlan.thin(f, 100)
        assertTrue(t.size < f.size)
        assertEquals(FramePlan.totalMs(f), FramePlan.totalMs(t))
        assertEquals(f.first().sourceMs, t.first().sourceMs)
    }

    @Test
    fun editedTimelineSurvivesJsonAndOldSettingsStillOpen() {
        val settings = AnimatedSettings(
            startMs = 500, durationMs = 2000, fps = 10, caption = "hi",
            frames = listOf(TimelineFrame(500, 100), TimelineFrame(600, 150), TimelineFrame(500, 100)),
        )
        val back = ProjectJson.decodeAnimated(ProjectJson.encode(settings))!!
        assertEquals(settings, back)

        val old = ProjectJson.decodeAnimated("""{"v":1,"startMs":0,"durationMs":3000,"fps":12}""")!!
        assertEquals(null, old.frames)
    }
}
