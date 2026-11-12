package com.comicreader.app.evaluation

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.comicreader.app.data.repository.ComicRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.UUID
import org.json.JSONObject
import java.io.File

/** Developer-only entry point. Never calls reader progress, indexing or navigation APIs. */
@AndroidEntryPoint
class BubbleEvaluationActivity : ComponentActivity() {
    @Inject lateinit var repository: ComicRepository
    private lateinit var store: EvaluationStore
    private lateinit var root: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var status: TextView
    private lateinit var canvas: EvaluationCanvas
    private lateinit var regions: Spinner
    private var page: EvaluationPage? = null
    private var draft = EvaluationDraft()
    private var selected = -1
    private var busy = false
    private var attemptStatus = "not captured"
    private var restore: Bundle? = null
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A cancelled picker leaves the library, annotations and current page untouched.
        if (uri != null) work("Importing selected comic into Anagno Evaluation…") {
            val imported = EvaluationComicImport.importSelected(this, repository, uri)
            status.text = "Imported ${imported?.title} into Anagno Evaluation. Use Choose comic/page. Import does not grant evaluation rights."
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) work("Exporting explicit ZIP…") {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { store.export(it) }
                    ?: error("Document provider did not open the destination")
            }
            status.text = "ZIP exported to selected document. No upload was performed."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Anagno Evaluation"
        restore = savedInstanceState
        store = EvaluationStore(applicationContext)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
        status = TextView(this).apply { text = "Local pilot • at most 10 distinct pages. Importing a comic does not grant evaluation permission. Drafts may remain unreviewed. Export requires rights and human review for every page." }
        root.addView(status)
        val scroll = ScrollView(this)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(controls)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(minOf(255, resources.configuration.screenHeightDp / 3))))
        row {
            button("Import CBZ/CBR/PDF") {
                if (canSwitchPage()) importDocument.launch(arrayOf("*/*"))
            }
        }
        row {
            button("Choose comic/page") { chooseComic() }
            button("Reopen draft") { reopen() }
            button("Export ZIP") { confirmExport() }
        }
        row {
            button("Navigate") { canvas.mode = EvaluationCanvas.Mode.NAVIGATE }
            button("Polygon") { canvas.mode = EvaluationCanvas.Mode.POLYGON }
            button("Rectangle") { canvas.mode = EvaluationCanvas.Mode.RECTANGLE }
            button("Fit") { canvas.fit() }
            button("Native inspect") { canvas.inspectNative() }
        }
        label("Draw in source pixels. Polygon: tap vertices. Rectangle: drag. Navigate: pinch zoom / drag. Native inspect loads original viewport pixels; Fit shows the sampled overview. Inspection does not verify annotations. Commit each body or tail contour; subtraction cuts holes.")
        regions = Spinner(this)
        controls.addView(regions)
        regions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected = position - 1; canvas.selected = selected }
        }
        row {
            button("Add body/tail") { commit(false) }
            button("Subtract contour") { commit(true) }
            button("Undo vertex") { canvas.undoPoint() }
            button("Cancel contour") { canvas.cancelContour() }
        }
        row {
            button("Remove contour") {
                val region = draft.regions.getOrNull(selected) ?: return@button
                update(draft.copy(regions = draft.regions.toMutableList().apply {
                    if (region.contours.size <= 1) removeAt(selected)
                    else set(selected, region.copy(contours = region.contours.dropLast(1), verified = false))
                }, issues = if (region.contours.size <= 1) draft.issues - "missed:${region.id}" else draft.issues, reviewed = false))
            }
            button("Remove instance") {
                val region = draft.regions.getOrNull(selected) ?: return@button
                update(draft.copy(regions = draft.regions.filterNot { it.id == region.id },
                    issues = draft.issues - "missed:${region.id}", reviewed = false))
            }
            button("Verify instance") {
                val region = draft.regions.getOrNull(selected) ?: return@button
                update(draft.copy(regions = draft.regions.map { if (it.id == region.id) it.copy(verified = !it.verified) else it }, reviewed = false))
            }
        }
        row {
            button("Mark missed") {
                val region = draft.regions.getOrNull(selected) ?: return@button
                val key = "missed:${region.id}"
                update(draft.copy(issues = if (key in draft.issues) draft.issues - key else draft.issues + (key to "missed"), reviewed = false))
            }
            button("Prediction issue") { predictionIssue() }
            button("Rights / page review") { review() }
        }
        canvas = EvaluationCanvas(this)
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        savedInstanceState?.getString("sample")?.let { sample -> work("Reopening draft…") {
            val existing = withContext(Dispatchers.IO) { store.listPages().firstOrNull { it.sampleId == sample } }
            if (existing != null) showPage(existing)
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("sample", page?.sampleId)
        outState.putInt("selected", selected)
        outState.putFloatArray("pending", canvas.pending.flatMap { listOf(it.x, it.y) }.toFloatArray())
    }

    override fun onDestroy() {
        canvas.release()
        super.onDestroy()
    }

    private fun chooseComic() {
        if (!canSwitchPage()) return
        work("Loading library…") {
        val comics = withContext(Dispatchers.IO) {
            // first() reads the library without opening a comic or changing reading state.
            repository.observeLibrary().first()
        }
        if (comics.isEmpty()) { status.text = "Evaluation library is empty. Use Import CBZ/CBR/PDF to select an original file and grant this app read access. The original Anagno library and grants are separate; import does not grant evaluation rights."; return@work }
        AlertDialog.Builder(this).setTitle("Choose comic (read-only)")
            .setItems(comics.map { it.title }.toTypedArray()) { _, index ->
                work("Reading page references…") pageReferences@{
                    val comic = comics[index]
                    val refs = withContext(Dispatchers.IO) {
                        EvaluationComicImport.requireReadable(this@BubbleEvaluationActivity, Uri.parse(comic.uri))
                        repository.getPageRefs(comic)
                    }
                    if (refs.isEmpty()) { status.text = "Comic has no pages"; return@pageReferences }
                    val input = EditText(this@BubbleEvaluationActivity).apply { inputType = 2; hint = "Page 1–${refs.size}" }
                    AlertDialog.Builder(this@BubbleEvaluationActivity).setTitle("Page number")
                        .setView(input).setNegativeButton("Cancel", null)
                        .setPositiveButton("Capture locally") { _, _ ->
                            val number = input.text.toString().toIntOrNull()
                            if (number == null || number !in 1..refs.size) status.text = "Enter a valid page number."
                            else work("Running CURRENT detector locally; initial priming may take time…") {
                                val captured = withContext(Dispatchers.IO) {
                                    EvaluationComicImport.requireReadable(this@BubbleEvaluationActivity, Uri.parse(comic.uri))
                                    val path = repository.loadPage(comic, refs[number - 1])
                                    store.capture(comic, number - 1, refs[number - 1], path)
                                }
                                showPage(captured)
                            }
                        }.show()
                }
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun reopen() {
        if (!canSwitchPage()) return
        work("Loading local pilot…") {
        val pages = withContext(Dispatchers.IO) { store.listPages() }
        val statuses = withContext(Dispatchers.IO) { pages.map { captureStatus(it) } }
        if (pages.isEmpty()) { status.text = "No local drafts yet."; return@work }
        AlertDialog.Builder(this).setTitle("Local pilot (${pages.size}/10)")
            .setItems(pages.mapIndexed { index, value -> "${value.sampleId.take(21)} • ${statuses[index]}" }.toTypedArray()) { _, index -> work("Opening…") { showPage(pages[index]) } }
            .setNegativeButton("Cancel", null).show()
        }
    }

    private suspend fun showPage(value: EvaluationPage) {
        val loaded = withContext(Dispatchers.IO) { store.loadDraft(value) }
        val capturedStatus = withContext(Dispatchers.IO) { captureStatus(value) }
        val image = withContext(Dispatchers.Default) { EvaluationCanvas.load(value) }
        page = value
        draft = loaded
        attemptStatus = capturedStatus
        selected = -1
        canvas.setPage(value, image)
        refresh()
        restore?.takeIf { it.getString("sample") == value.sampleId }?.let { state ->
            regions.setSelection((state.getInt("selected", -1) + 1).coerceIn(0, draft.regions.size))
            canvas.pending = state.getFloatArray("pending")?.toList()?.chunked(2)?.filter { it.size == 2 }
                ?.map { EvaluationPoint(it[0], it[1]) } ?: emptyList()
        }
        restore = null
    }

    private fun commit(subtract: Boolean) {
        if (page == null || busy) return
        val points = canvas.contour()
        if (points.size < 3) { status.text = "Draw at least three polygon vertices or a nonzero rectangle."; return }
        val old = draft.regions.getOrNull(selected)
        if (subtract && old == null) { status.text = "Select an existing instance before subtracting."; return }
        val changed = if (old == null) draft.regions + EvaluationRegion("r-${UUID.randomUUID()}", listOf(EvaluationContour(points, subtract)))
            else draft.regions.map { if (it.id == old.id) it.copy(contours = it.contours + EvaluationContour(points, subtract), verified = false) else it }
        update(draft.copy(regions = changed, reviewed = false)) { canvas.cancelContour() }
    }

    private fun update(value: EvaluationDraft, afterSave: () -> Unit = {}) {
        val current = page ?: return
        if (busy) return
        work("Saving local draft…") {
            withContext(Dispatchers.IO) { store.save(current, value) }
            draft = value
            afterSave()
            refresh()
        }
    }

    private fun refresh() {
        regions.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("New instance") + draft.regions.map { "${it.id.take(10)} • ${if (it.verified) "verified" else "unverified"}${if ("missed:${it.id}" in draft.issues) " • missed" else ""}" })
        selected = selected.coerceIn(-1, draft.regions.lastIndex)
        regions.setSelection(selected + 1)
        canvas.annotations = draft.regions
        status.text = "CAPTURE: $attemptStatus\n${page?.sampleId?.take(21)} • ${draft.regions.size} GT instances • ${page?.predictions?.size} actual predictions • ${if (draft.reviewed) "page reviewed" else "unreviewed draft"}. Cyan = prediction mask; yellow = unverified annotation; green = verified; red = subtraction. Issues never create GT. Timing: warm direct detector calls after priming; UI latency and peak memory unmeasured."
    }

    private fun predictionIssue() {
        val predictions = page?.predictions ?: return
        if (predictions.isEmpty()) { status.text = "No predictions. Annotate missed instances manually; failed attempts remain in local evidence."; return }
        AlertDialog.Builder(this).setTitle("Prediction (cyan mask)")
            .setItems(predictions.map { "${it.id}: ${draft.issues[it.id] ?: "unmarked"}" }.toTypedArray()) { _, index ->
                val id = predictions[index].id
                AlertDialog.Builder(this).setTitle(id).setItems(arrayOf("False positive", "Incorrect boundary", "Clear mark")) { _, choice ->
                    update(draft.copy(issues = if (choice == 2) draft.issues - id else draft.issues + (id to if (choice == 0) "false_positive" else "incorrect_boundary"), reviewed = false))
                }.show()
            }.show()
    }

    private fun review() {
        if (page == null) return
        if (!canSwitchPage()) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun field(hintText: String, value: String) = EditText(this).apply { hint = hintText; setText(value); form.addView(this) }
        val reviewer = field("Human reviewer", draft.reviewer)
        val holder = field("Rights holder", draft.rightsHolder)
        val permission = field("Permission/license evidence for evaluation (not import)", draft.permission)
        val rights = CheckBox(this).apply { text = "I confirm permission to use and export this page for evaluation. Import alone is not consent."; form.addView(this) }
        val complete = CheckBox(this).apply { text = "I reviewed the ENTIRE page, including bodies, tails, missed bubbles and negative areas. All GT instances are verified (zero allowed)."; form.addView(this) }
        AlertDialog.Builder(this).setTitle("Human review / evaluation rights").setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setNeutralButton("Save unreviewed draft") { _, _ ->
                update(draft.copy(reviewer = reviewer.text.toString(), rightsHolder = holder.text.toString(), permission = permission.text.toString(), reviewed = false))
            }.setPositiveButton("Confirm reviewed") { _, _ ->
                if (!rights.isChecked || !complete.isChecked || reviewer.text.isBlank() || holder.text.isBlank() || permission.text.isBlank() || draft.regions.any { !it.verified }) {
                    status.text = "Review incomplete: explicitly confirm rights and whole-page completeness, fill reviewer/rights/permission, and verify each instance. Draft retained."
                } else update(draft.copy(reviewer = reviewer.text.toString(), rightsHolder = holder.text.toString(), permission = permission.text.toString(), reviewed = true))
            }.show()
    }

    private fun confirmExport() {
        if (canvas.pending.isNotEmpty()) { status.text = "Commit or cancel the pending contour before export."; return }
        AlertDialog.Builder(this).setTitle("Export original pages and evaluation evidence?")
            .setMessage("This explicitly writes a ZIP to your chosen document provider, which may be cloud-backed. All pilot pages must have evaluation rights, a named reviewer, complete page review and verified instances. No automatic uploads occur.")
            .setNegativeButton("Cancel", null).setPositiveButton("Choose ZIP destination") { _, _ -> exportDocument.launch("anagno-evaluation-pilot.zip") }.show()
    }

    private fun canSwitchPage(): Boolean {
        if (canvas.pending.isEmpty()) return true
        status.text = "Commit or cancel the pending contour before changing pages."
        return false
    }

    private fun captureStatus(value: EvaluationPage): String {
        val attempt = File(value.directory, "attempt.json")
        if (!attempt.isFile) return "INCOMPLETE / interrupted — retained evidence"
        return try {
            val record = JSONObject(attempt.readText())
            val status = record.optString("status", "incomplete")
            if (status == "success") "success" else "FAILED: $status • ${record.optString("failure_kind", "inspect local evidence")}"
        } catch (_: Exception) { "INVALID / unreadable attempt evidence" }
    }

    private fun work(message: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status.text = message
        setEnabled(controls, false)
        if (::canvas.isInitialized) canvas.isEnabled = false
        lifecycleScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = "${error.message ?: error.javaClass.simpleName}. Local evidence is retained; use Reopen draft to inspect attempts." }
            finally { busy = false; setEnabled(controls, true); canvas.isEnabled = true }
        }
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setEnabled(view.getChildAt(i), enabled)
    }
    private fun label(value: String) { controls.addView(TextView(this).apply { text = value }) }
    private fun row(block: LinearLayout.() -> Unit) { controls.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; block() }) }
    private fun LinearLayout.button(text: String, action: () -> Unit) { addView(Button(this@BubbleEvaluationActivity).apply { this.text = text; textSize = 11f; setOnClickListener { if (!busy) action() } }, LinearLayout.LayoutParams(0, -2, 1f)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
