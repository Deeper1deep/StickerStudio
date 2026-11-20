package com.stickerstudio.app.sticker

import com.stickerstudio.app.export.ExportItem
import com.stickerstudio.app.export.ExportRules
import com.stickerstudio.app.export.ExportSession
import com.stickerstudio.app.export.ExportStateJson
import com.stickerstudio.app.export.FailReason
import com.stickerstudio.app.export.ItemStatus
import com.stickerstudio.app.export.RunState
import com.stickerstudio.app.export.SelectionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQueueTest {

    private fun session(vararg ids: String) = ExportSession.start(ids.map { ExportItem(it, "pack $it") })

    // ---- selection order

    @Test fun selectionKeepsTheOrderOfTicking() {
        // list order is cars, anime, memes, animals; the user ticks cars, anime, animals
        val s = SelectionOrder().toggle("cars").toggle("anime").toggle("animals")
        assertEquals(listOf("cars", "anime", "animals"), s.ids)
        assertEquals(3, s.position("animals"))
        assertEquals(0, s.position("memes"))
    }

    @Test fun untickAndTickAgainGoesToTheEnd() {
        val s = SelectionOrder().toggle("a").toggle("b").toggle("c").toggle("a")
        assertEquals(listOf("b", "c"), s.ids)
        assertEquals(listOf("b", "c", "a"), s.toggle("a").ids)
    }

    @Test fun dragMovesAndRenumbers() {
        val s = SelectionOrder(listOf("a", "b", "c")).move(0, 2)
        assertEquals(listOf("b", "c", "a"), s.ids)
        assertEquals(1, s.position("b"))
        assertEquals(s, s.move(5, 0))
        assertEquals(s, s.move(1, 1))
    }

    @Test fun removeOnlyAffectsTheList() {
        assertEquals(listOf("a", "c"), SelectionOrder(listOf("a", "b", "c")).remove("b").ids)
    }

    @Test fun selectAllKeepsExistingOrderThenAppends() {
        val s = SelectionOrder(listOf("c", "a")).selectAll(listOf("a", "b", "c", "d"))
        assertEquals(listOf("c", "a", "b", "d"), s.ids)
        assertTrue(s.clear().ids.isEmpty())
    }

    @Test fun deletedPacksAreDroppedFromTheSelection() {
        assertEquals(listOf("a", "c"), SelectionOrder(listOf("a", "b", "c")).retain(setOf("a", "c")).ids)
    }

    // ---- run

    @Test fun confirmedPacksFinishTheRunInOrder() {
        var s = session("a", "b")
        assertEquals(0, s.nextPendingIndex)
        s = s.markInProgress(0, false).confirm(0)
        assertEquals(1, s.nextPendingIndex)
        assertEquals(RunState.RUNNING, s.state)
        s = s.markInProgress(1, false).confirm(1)
        assertEquals(RunState.FINISHED, s.state)
        assertEquals(2, s.addedCount)
        assertEquals(0, s.remainingCount)
    }

    @Test fun launchingWhatsAppIsNotSuccess() {
        val s = session("a").markInProgress(0, false)
        assertEquals(ItemStatus.IN_PROGRESS, s.items[0].status)
        assertEquals(0, s.addedCount)
        assertEquals(1, s.remainingCount)
    }

    @Test fun failurePausesAndAllowsRetryOrSkip() {
        var s = session("a", "b").markInProgress(0, false).fail(0, FailReason.NOT_CONFIRMED)
        assertEquals(RunState.PAUSED, s.state)
        assertEquals(1, s.failedCount)
        // retry
        val retried = s.retry(0)
        assertEquals(RunState.RUNNING, retried.state)
        assertEquals(0, retried.nextPendingIndex)
        // skip and continue with the next pack
        s = s.skip(0).resume()
        assertEquals(RunState.RUNNING, s.state)
        assertEquals(1, s.nextPendingIndex)
        assertEquals(1, s.skippedCount)
    }

    @Test fun invalidPackBeforeStartDoesNotStopTheOthers() {
        var s = session("a", "b")
        s = s.fail(0, FailReason.INVALID, "too few stickers", stop = false)
        assertEquals(RunState.RUNNING, s.state)
        assertEquals(1, s.nextPendingIndex)
    }

    @Test fun lastPackFailingWithoutStopFinishes() {
        val s = session("a").fail(0, FailReason.MISSING, stop = false)
        assertEquals(RunState.FINISHED, s.state)
    }

    @Test fun cancelKeepsEverythingAndResumeContinuesWhereItStopped() {
        var s = session("a", "b", "c").markInProgress(0, false).confirm(0).cancel()
        assertEquals(RunState.CANCELLED, s.state)
        assertEquals(listOf("a", "b", "c"), s.items.map { it.packId })
        assertEquals(1, s.addedCount)
        s = s.resume()
        assertEquals(RunState.RUNNING, s.state)
        assertEquals(1, s.nextPendingIndex)
    }

    @Test fun emptyRunDoesNotStart() {
        assertEquals(RunState.IDLE, ExportSession.start(emptyList()).state)
    }

    @Test fun pauseOnlyAffectsARunningSession() {
        assertEquals(RunState.IDLE, ExportSession().pause().state)
        assertEquals(RunState.PAUSED, session("a").pause().state)
    }

    // ---- confirmation rules

    @Test fun confirmationRules() {
        assertTrue(ExportRules.isConfirmed(resultOk = true, addedBefore = true, addedNow = true))
        assertTrue(ExportRules.isConfirmed(false, addedBefore = false, addedNow = true))
        assertFalse(ExportRules.isConfirmed(false, addedBefore = true, addedNow = true))   // old addition, user backed out
        assertFalse(ExportRules.isConfirmed(false, addedBefore = false, addedNow = false))
    }

    // ---- persistence

    @Test fun stateSurvivesJsonAndNeverAutoContinues() {
        val s = session("a", "b", "c").markInProgress(0, true).confirm(0).markInProgress(1, false)
        val order = SelectionOrder(listOf("a", "b", "c"))
        val (o2, s2) = ExportStateJson.decode(ExportStateJson.encode(order, s))!!
        assertEquals(order, o2)
        assertEquals(RunState.PAUSED, s2.state)                       // a running session is restored as paused
        assertEquals(ItemStatus.IN_PROGRESS, s2.items[1].status)      // the caller verifies it with WhatsApp
        assertEquals(ItemStatus.ADDED, s2.items[0].status)
        assertTrue(s2.items[0].addedBefore)
    }

    @Test fun damagedDataIsIgnored() {
        assertNull(ExportStateJson.decode(null))
        assertNull(ExportStateJson.decode("{not json"))
        val (o, s) = ExportStateJson.decode("""{"order":["x","x","y"]}""")!!
        assertEquals(listOf("x", "y"), o.ids)
        assertEquals(RunState.IDLE, s.state)
    }
}
