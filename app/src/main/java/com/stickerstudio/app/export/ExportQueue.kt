package com.stickerstudio.app.export

import com.stickerstudio.app.core.JsonParseException
import com.stickerstudio.app.core.MiniJson
import com.stickerstudio.app.core.asArray
import com.stickerstudio.app.core.asObject
import com.stickerstudio.app.core.optBool
import com.stickerstudio.app.core.optString

/**
 * The packs the user ticked, in the order they were ticked (not the order of the list or the
 * database). Un-ticking and ticking again puts a pack at the end. Pure Kotlin, unit tested.
 */
data class SelectionOrder(val ids: List<String> = emptyList()) {
    fun contains(id: String) = id in ids

    /** 1-based position of [id], 0 when it is not selected. */
    fun position(id: String): Int = ids.indexOf(id) + 1

    fun toggle(id: String): SelectionOrder = if (id in ids) remove(id) else copy(ids = ids + id)

    fun remove(id: String): SelectionOrder = copy(ids = ids - id)

    /** Moves the item at [from] to [to] (drag and drop). Out-of-range moves are ignored. */
    fun move(from: Int, to: Int): SelectionOrder {
        if (from !in ids.indices || to !in ids.indices || from == to) return this
        val list = ids.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(ids = list)
    }

    /** Keeps the current order and appends every other pack of [all] in list order. */
    fun selectAll(all: List<String>): SelectionOrder = copy(ids = ids + all.filter { it !in ids })

    fun clear() = SelectionOrder()

    /** Drops packs that no longer exist. */
    fun retain(valid: Set<String>): SelectionOrder = copy(ids = ids.filter { it in valid })
}

enum class ItemStatus { PENDING, IN_PROGRESS, ADDED, FAILED, SKIPPED }

enum class FailReason { NONE, INVALID, MISSING, NOT_INSTALLED, NOT_CONFIRMED, REJECTED, LAUNCH_FAILED }

enum class RunState { IDLE, RUNNING, PAUSED, CANCELLED, FINISHED }

data class ExportItem(
    val packId: String,
    val name: String,
    val status: ItemStatus = ItemStatus.PENDING,
    val reason: FailReason = FailReason.NONE,
    /** Extra text for the user (first validation problem, WhatsApp's own error...). */
    val detail: String = "",
    /** Was the pack already in WhatsApp when we opened it? Needed to tell a real confirmation from an old one. */
    val addedBefore: Boolean = false,
)

/**
 * One export run: the ordered packs and what happened to each. Every transition returns a new
 * value. A pack only becomes [ItemStatus.ADDED] through [confirm], which callers invoke only
 * when WhatsApp itself said so (see [ExportRules.isConfirmed]).
 */
data class ExportSession(val items: List<ExportItem> = emptyList(), val state: RunState = RunState.IDLE) {

    val total: Int get() = items.size
    val addedCount: Int get() = items.count { it.status == ItemStatus.ADDED }
    val failedCount: Int get() = items.count { it.status == ItemStatus.FAILED }
    val skippedCount: Int get() = items.count { it.status == ItemStatus.SKIPPED }
    val remainingCount: Int get() = items.count { it.status == ItemStatus.PENDING || it.status == ItemStatus.IN_PROGRESS }
    /** Packs that are settled one way or the other. */
    val doneCount: Int get() = total - remainingCount

    val inProgressIndex: Int? get() = items.indexOfFirst { it.status == ItemStatus.IN_PROGRESS }.takeIf { it >= 0 }
    val nextPendingIndex: Int? get() = items.indexOfFirst { it.status == ItemStatus.PENDING }.takeIf { it >= 0 }
    val isActive: Boolean get() = state == RunState.RUNNING || state == RunState.PAUSED || state == RunState.CANCELLED

    private fun set(i: Int, f: (ExportItem) -> ExportItem): ExportSession =
        if (i !in items.indices) this else copy(items = items.mapIndexed { n, it -> if (n == i) f(it) else it })

    /** Finishes the run when nothing is left to do. */
    private fun settle(): ExportSession =
        if (state == RunState.RUNNING && remainingCount == 0) copy(state = RunState.FINISHED) else this

    fun markInProgress(i: Int, addedBefore: Boolean): ExportSession =
        set(i) { it.copy(status = ItemStatus.IN_PROGRESS, reason = FailReason.NONE, detail = "", addedBefore = addedBefore) }

    /** The pack is confirmed to be in WhatsApp. */
    fun confirm(i: Int): ExportSession = set(i) { it.copy(status = ItemStatus.ADDED, reason = FailReason.NONE, detail = "") }.settle()

    /** A pack that could not be added. [stop] pauses the run so the user can retry or skip it. */
    fun fail(i: Int, reason: FailReason, detail: String = "", stop: Boolean = true): ExportSession {
        val next = set(i) { it.copy(status = ItemStatus.FAILED, reason = reason, detail = detail) }
        return if (stop && next.state == RunState.RUNNING) next.copy(state = RunState.PAUSED) else next.settle()
    }

    fun skip(i: Int): ExportSession = set(i) { it.copy(status = ItemStatus.SKIPPED, reason = FailReason.NONE, detail = "") }.settle()

    fun retry(i: Int): ExportSession {
        val next = set(i) { it.copy(status = ItemStatus.PENDING, reason = FailReason.NONE, detail = "") }
        return if (next.state == RunState.FINISHED || next.state == RunState.PAUSED) next.copy(state = RunState.RUNNING) else next
    }

    fun pause(): ExportSession = if (state == RunState.RUNNING) copy(state = RunState.PAUSED) else this
    fun cancel(): ExportSession = if (isActive) copy(state = RunState.CANCELLED) else this

    /** Continues from the first pack that is not settled yet. */
    fun resume(): ExportSession =
        if (state == RunState.PAUSED || state == RunState.CANCELLED) copy(state = RunState.RUNNING).settle() else this

    /** After the app was killed: never continue by itself. */
    fun afterRestart(): ExportSession = if (state == RunState.RUNNING) copy(state = RunState.PAUSED) else this

    companion object {
        /** Starts a run in the given order (packs found invalid are failed right after, with stop = false). */
        fun start(items: List<ExportItem>): ExportSession = ExportSession(items, RunState.RUNNING).let {
            if (it.items.isEmpty()) it.copy(state = RunState.IDLE) else it.settle()
        }
    }
}

object ExportRules {
    /**
     * WhatsApp's answer counts as confirmation only if it returned OK, or if the pack was not in
     * WhatsApp before and is now. Merely opening WhatsApp, or a pack that was already there, is not.
     */
    fun isConfirmed(resultOk: Boolean, addedBefore: Boolean, addedNow: Boolean): Boolean =
        resultOk || (!addedBefore && addedNow)
}

/** Saves / restores the selection order and the run, so leaving the screen or the app loses nothing. */
object ExportStateJson {

    fun encode(order: SelectionOrder, session: ExportSession): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "v" to 1,
            "order" to order.ids,
            "state" to session.state.name,
            "items" to session.items.map {
                linkedMapOf<String, Any?>(
                    "id" to it.packId, "name" to it.name, "status" to it.status.name,
                    "reason" to it.reason.name, "detail" to it.detail, "before" to it.addedBefore,
                )
            },
        )
    )

    /** Returns null for missing or damaged data; a run that was active is restored as paused. */
    fun decode(json: String?): Pair<SelectionOrder, ExportSession>? {
        if (json.isNullOrBlank()) return null
        val root = try {
            MiniJson.parse(json).asObject()
        } catch (e: JsonParseException) {
            null
        } ?: return null
        val order = SelectionOrder(root["order"].asArray().orEmpty().mapNotNull { it as? String }.distinct())
        val items = root["items"].asArray().orEmpty().mapNotNull { e ->
            val m = e.asObject() ?: return@mapNotNull null
            ExportItem(
                packId = m.optString("id") ?: return@mapNotNull null,
                name = m.optString("name") ?: "",
                status = enumOr(m.optString("status"), ItemStatus.PENDING),
                reason = enumOr(m.optString("reason"), FailReason.NONE),
                detail = m.optString("detail") ?: "",
                addedBefore = m.optBool("before", false),
            )
        }
        val state = enumOr(root.optString("state"), RunState.IDLE)
        val session = if (items.isEmpty()) ExportSession() else ExportSession(items, state).afterRestart()
        return order to session
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
