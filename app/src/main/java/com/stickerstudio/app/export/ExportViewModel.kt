package com.stickerstudio.app.export

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stickerstudio.app.R
import com.stickerstudio.app.container
import com.stickerstudio.app.data.PackEntity
import com.stickerstudio.app.ui.issueMessage
import com.stickerstudio.app.whatsapp.WaSyncStore
import com.stickerstudio.app.whatsapp.WhatsAppIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen shows for one pack of the list. */
data class ExportPackRow(val pack: PackEntity, val count: Int)

/** A request to open WhatsApp for the pack at [index]; the screen launches it once and calls [ExportViewModel.onLaunched]. */
data class LaunchRequest(val index: Int, val intent: Intent, val nonce: Long = System.nanoTime())

data class ExportNotice(val res: Int, val arg: Int = 0, val nonce: Long = System.nanoTime())

data class ExportUi(
    val rows: List<ExportPackRow> = emptyList(),
    val loaded: Boolean = false,
    val order: SelectionOrder = SelectionOrder(),
    val session: ExportSession = ExportSession(),
    val chooseTarget: List<String>? = null,
    val notice: ExportNotice? = null,
)

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.container.repository
    private val prefs = app.getSharedPreferences("export_state", 0)

    private val order = MutableStateFlow(SelectionOrder())
    private val session = MutableStateFlow(ExportSession())
    private val chooseTarget = MutableStateFlow<List<String>?>(null)
    private val notice = MutableStateFlow<ExportNotice?>(null)
    private val _launch = MutableStateFlow<LaunchRequest?>(null)
    val launch: StateFlow<LaunchRequest?> = _launch.asStateFlow()

    private var target: String? = null
    private var advanceJob: Job? = null

    private val rows = combine(repo.observePacks(), repo.observeCounts()) { packs, counts ->
        packs.map { p -> ExportPackRow(p, counts.firstOrNull { it.packId == p.id }?.count ?: 0) }
    }

    val ui: StateFlow<ExportUi> = combine(rows, order, session, chooseTarget, notice) { r, o, s, c, n ->
        ExportUi(r, true, o, s, c, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUi())

    init {
        // Restore what the user had: the selection order and, if the app was closed mid-export, the paused run.
        ExportStateJson.decode(prefs.getString(KEY, null))?.let { (o, s) ->
            order.value = o
            session.value = s
            if (s.isActive) notice.value = ExportNotice(R.string.export_restored)
        }
        viewModelScope.launch {
            reconcileRestored()
            // A pack that is deleted while it is selected leaves the list (the run, if any, marks it as missing).
            repo.observePacks().collect { packs ->
                if (session.value.isActive) return@collect
                val valid = packs.map { it.id }.toSet()
                val removed = order.value.ids.count { it !in valid }
                if (removed > 0) {
                    order.update { it.retain(valid) }
                    notice.value = ExportNotice(R.string.export_removed_notice, removed)
                    persist()
                }
            }
        }
    }

    /** After a restart a pack may have been "in progress": only WhatsApp's own answer can confirm it. */
    private suspend fun reconcileRestored() {
        val s = session.value
        val i = s.inProgressIndex ?: return
        val item = s.items[i]
        val addedNow = isAddedAnywhere(item.packId)
        session.value = if (ExportRules.isConfirmed(false, item.addedBefore, addedNow)) {
            repo.findPack(item.packId)?.let { WaSyncStore.record(getApplication(), it.id, it.imageDataVersion) }
            s.confirm(i)
        } else {
            s.fail(i, FailReason.NOT_CONFIRMED, stop = false)
        }
        persist()
    }

    private suspend fun isAddedAnywhere(packId: String): Boolean = withContext(Dispatchers.IO) {
        WhatsAppIntegration.installedTargets(getApplication()).any { WhatsAppIntegration.isPackAdded(getApplication(), it, packId) }
    }

    private fun persist() {
        prefs.edit().putString(KEY, ExportStateJson.encode(order.value, session.value)).apply()
    }

    fun consumeNotice() { notice.value = null }

    // ------------------------------------------------------------------ selection

    private fun editable() = !session.value.isActive

    fun toggle(id: String) { if (editable()) { order.update { it.toggle(id) }; persist() } }
    fun remove(id: String) { if (editable()) { order.update { it.remove(id) }; persist() } }
    fun selectAll() { if (editable()) { order.update { it.selectAll(ui.value.rows.map { r -> r.pack.id }) }; persist() } }
    fun clearSelection() { if (editable()) { order.update { it.clear() }; persist() } }

    /** Drag and drop in the "export order" list, by pack id. */
    fun move(fromId: String, toId: String) {
        if (!editable()) return
        order.update { o -> o.move(o.ids.indexOf(fromId), o.ids.indexOf(toId)) }
        persist()
    }

    // ------------------------------------------------------------------ run

    fun start() {
        if (session.value.isActive || order.value.ids.isEmpty()) return
        val ids = order.value.ids   // the final order is fixed here, before anything starts
        viewModelScope.launch {
            if (WhatsAppIntegration.installedTargets(getApplication()).isEmpty()) {
                notice.value = ExportNotice(R.string.export_wa_missing)
                return@launch
            }
            val items = ArrayList<ExportItem>()
            val invalid = HashMap<Int, Pair<FailReason, String>>()
            for ((i, id) in ids.withIndex()) {
                val pack = repo.findPack(id)
                if (pack == null) {
                    items += ExportItem(id, "")
                    invalid[i] = FailReason.MISSING to ""
                    continue
                }
                items += ExportItem(id, pack.name)
                val issues = WhatsAppIntegration.validate(repo, pack)
                if (issues.isNotEmpty()) invalid[i] = FailReason.INVALID to issueMessage(getApplication(), issues.first())
            }
            var s = ExportSession.start(items)
            for ((i, f) in invalid) s = s.fail(i, f.first, f.second, stop = false)
            session.value = s
            persist()
            advance()
        }
    }

    fun chooseTarget(packageName: String) {
        target = packageName
        chooseTarget.value = null
        advance()
    }

    fun dismissTargetChoice() {
        chooseTarget.value = null
        pause()
    }

    /** Opens WhatsApp for the next pack, if the run is going and nothing is waiting for WhatsApp. */
    private fun advance() {
        if (advanceJob?.isActive == true) return
        advanceJob = viewModelScope.launch {
            while (true) {
                val s = session.value
                if (s.state != RunState.RUNNING || s.inProgressIndex != null || _launch.value != null) return@launch
                val i = s.nextPendingIndex ?: return@launch
                val item = s.items[i]
                val pack = repo.findPack(item.packId)
                if (pack == null) {
                    session.value = s.fail(i, FailReason.MISSING, stop = false)
                    persist()
                    continue
                }
                val issues = WhatsAppIntegration.validate(repo, pack)
                if (issues.isNotEmpty()) {
                    session.value = s.fail(i, FailReason.INVALID, issueMessage(getApplication(), issues.first()), stop = false)
                    persist()
                    continue
                }
                val targets = WhatsAppIntegration.installedTargets(getApplication())
                if (targets.isEmpty()) {
                    session.value = s.fail(i, FailReason.NOT_INSTALLED)
                    persist()
                    return@launch
                }
                val chosen = target?.takeIf { it in targets } ?: targets.singleOrNull()
                if (chosen == null) {
                    chooseTarget.value = targets
                    return@launch
                }
                target = chosen
                val before = withContext(Dispatchers.IO) { WhatsAppIntegration.isPackAdded(getApplication(), chosen, pack.id) }
                session.value = s.markInProgress(i, before)
                persist()
                _launch.value = LaunchRequest(i, WhatsAppIntegration.enableIntent(getApplication(), pack, chosen))
                return@launch
            }
        }
    }

    /** The screen has started WhatsApp for the current request. */
    fun onLaunched() { _launch.value = null }

    fun onLaunchFailed() {
        _launch.value = null
        val i = session.value.inProgressIndex ?: return
        session.value = session.value.fail(i, FailReason.LAUNCH_FAILED)
        persist()
    }

    /** WhatsApp's answer. Only OK (or a pack that really is in WhatsApp now and was not before) counts as added. */
    fun onResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            val s = session.value
            val i = s.inProgressIndex ?: return@launch
            val item = s.items[i]
            val addedNow = isAddedAnywhere(item.packId)
            if (ExportRules.isConfirmed(resultCode == Activity.RESULT_OK, item.addedBefore, addedNow)) {
                repo.findPack(item.packId)?.let { WaSyncStore.record(getApplication(), it.id, it.imageDataVersion) }
                session.value = s.confirm(i)
            } else {
                val error = data?.getStringExtra("validation_error")
                session.value = s.fail(i, if (error != null) FailReason.REJECTED else FailReason.NOT_CONFIRMED, error.orEmpty())
            }
            persist()
            if (session.value.state == RunState.RUNNING) {
                delay(800)   // a short beat so the user sees the pack turn green
                advance()
            }
        }
    }

    fun pause() { session.update { it.pause() }; persist() }
    fun resume() {
        session.update { it.resume() }
        persist()
        advance()
    }
    fun cancel() { session.update { it.cancel() }; persist() }
    fun retry(i: Int) { session.update { it.retry(i) }; persist(); advance() }
    fun skip(i: Int) { session.update { it.skip(i) }; persist() }

    /** Leaves the run screen; the selection and its order stay as they were. */
    fun finishRun() {
        session.value = ExportSession()
        persist()
    }

    private companion object {
        const val KEY = "state"
    }
}
