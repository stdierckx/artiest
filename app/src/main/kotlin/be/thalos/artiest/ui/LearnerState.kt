package be.thalos.artiest.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import be.thalos.artiest.card.Card
import be.thalos.artiest.card.CardFiles
import be.thalos.artiest.model.ModelStage
import be.thalos.artiest.ref.RefFiles
import be.thalos.artiest.ref.RefImport
import be.thalos.artiest.ref.RefKind
import be.thalos.artiest.ref.RefModelImport
import be.thalos.artiest.ref.RefPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the learner's features keep, in one object.
 *
 * ## Why this is a class and not twenty lines in the screen
 *
 * It **was** twenty lines in the screen, and the tablet showed what that cost:
 *
 * ```
 * Method exceeds compiler instruction limit: 16819 in ... CanvasScreen
 * ```
 *
 * ART refuses to compile a method over 10 000 code units — `kHugeMethodThreshold`
 * — and runs it interpreted instead. `CanvasScreen` recomposes whenever anything
 * on the glass changes, so an interpreted one is an app that hangs, which is
 * exactly how it was reported. Counting the bytecode said where the weight was:
 * **170 `remember` sites**, each costing its own group, its own slot read and
 * its own comparison.
 *
 * A holder collapses a run of them into one. Twenty-odd pieces of state, five
 * effects and a handful of lambdas became `remember { LearnerState(...) }` plus
 * the effects that drive it — and the effects are here rather than there for
 * the same reason.
 *
 * ## What it is not
 *
 * Not a view model and not a controller. It holds state and reads files; it
 * knows nothing about the document, the render thread or the drawing, and the
 * two actions that *do* touch those — keeping a card, practising one — stay in
 * the screen where the document is. The rule is the one this repository already
 * follows for `DockStore` and `BrushStore`: a holder holds, and the screen
 * decides.
 */
@Stable
class LearnerState(context: Context) {

    /** Lr3. The pictures, on the tablet's own storage. */
    val refFiles = RefFiles(File(context.filesDir, "references"))

    /** Lr7. The deck, one directory along and the same shape. */
    val cardFiles = CardFiles(File(context.filesDir, "cards"))

    var pictures by mutableStateOf(emptyList<RefPicture>())
    var selected by mutableStateOf<String?>(null)

    /**
     * The one being looked at, full size, and a small one each for the strip.
     *
     * Both are read off the disk on a background dispatcher; a panel that
     * decoded forty photographs on the main thread would be a panel that opens
     * in its own time.
     */
    var bitmap by mutableStateOf<Bitmap?>(null)
    var thumbs by mutableStateOf(emptyMap<String, Bitmap>())

    /**
     * Lr12. The selected reference when it is a model, as the bytes of its
     * `.glb`, and null when it is a picture or still being read.
     *
     * Held rather than streamed to the renderer, for one reason that is worth
     * the megabytes: turning clay on and off reloads the model, and reloading
     * from a field is one frame where reloading from the disk is a read on
     * another thread and a pane that blinks. One model at a time, dropped the
     * moment a different reference is selected.
     */
    var modelBytes by mutableStateOf<ByteArray?>(null)

    /** Whether the selected model still has no face for the strip. */
    var posterWanted by mutableStateOf(false)

    /**
     * The 3D renderer, which is deliberately owned here and not by the pane.
     *
     * Constructing it is free — it holds no engine until the first model asks
     * for one — and it living here is what keeps a loaded mesh alive across the
     * panel being rearranged, fixated or reopened. See [ModelStage].
     */
    val stage = ModelStage(context)

    /** Whether the library has finished reading itself. See the share. */
    var loaded by mutableStateOf(false)

    var cards by mutableStateOf(emptyList<Card>())
    var cardThumbs by mutableStateOf(emptyMap<String, Bitmap>())
    var cardTags by mutableStateOf(emptyList<String>())
    var cardTag by mutableStateOf<String?>(null)
    var openCardId by mutableStateOf<String?>(null)
    var openCardBitmap by mutableStateOf<Bitmap?>(null)

    /** The card being looked at, or null. */
    val openCard: Card? get() = cards.firstOrNull { it.id == openCardId }

    /** Lr9. The session, as the panel sees it. */
    var practice by mutableStateOf(PracticeState())

    /**
     * How long each pose gets, which pictures, and when this one started.
     *
     * **Plain fields and not state.** Nothing draws them: the panel is handed
     * [practice], and these three are what the clock reads to decide when the
     * page turns. A `mutableStateOf` here would recompose the screen three
     * times at the start of every pose for no picture on the glass.
     */
    var lengthMs = 60_000L
    var order = emptyList<String>()
    var startedAt = 0L

    /**
     * How the reference picture sits in its pane.
     *
     * Held here rather than inside the pane because the pane is a panel, and a
     * panel leaves the composition every time the bars are rearranged. See
     * [PaneView], which is where the tablet report that caused this is written
     * down.
     */
    val pane = PaneView()

    /** Lr5. Two ways of looking; see `InkSurfaceView.mirrored`. */
    var flipView by mutableStateOf(false)
    var greyView by mutableStateOf(false)

    /** Lr1. Whether the pen is picking a colour, and whether it stays picking. */
    var pickingColour by mutableStateOf(false)
    var pickLayerOnly by mutableStateOf(false)

    /** Set by a long press on the picker. A field: nothing draws it. */
    var pickHeld = false

    // ---- reading the disk ---------------------------------------------------

    suspend fun readLibrary() {
        val list = withContext(Dispatchers.IO) { refFiles.list() }
        pictures = list
        selected = list.firstOrNull()?.id
        loaded = true
    }

    /**
     * The strip. Rebuilt when the list changes and not per picture, because a
     * map rebuilt per picture is a recomposition per picture.
     */
    suspend fun readThumbs() {
        thumbs = withContext(Dispatchers.IO) {
            pictures.mapNotNull { p -> refFiles.loadSmall(p.id, THUMB_PX)?.let { p.id to it } }
                .toMap()
        }
    }

    /**
     * Read whatever the selection is, and let go of whatever it is not.
     *
     * The two halves are exclusive on purpose: a pane showing a model must not
     * also be holding the last photograph's pixels, and the field that is not
     * in use is cleared *before* the read rather than after, so there is no
     * moment where both are set and the panel has to guess.
     */
    suspend fun readSelected() {
        val id = selected
        val entry = pictures.firstOrNull { it.id == id }
        if (id == null || entry == null) {
            bitmap = null
            modelBytes = null
            posterWanted = false
            return
        }
        if (entry.kind == RefKind.MODEL) {
            bitmap = null
            posterWanted = withContext(Dispatchers.IO) { !refFiles.hasPoster(id) }
            modelBytes = withContext(Dispatchers.IO) { refFiles.loadModel(id) }
        } else {
            modelBytes = null
            posterWanted = false
            bitmap = withContext(Dispatchers.IO) { refFiles.load(id) }
        }
    }

    suspend fun readDeck() {
        val list = withContext(Dispatchers.IO) { cardFiles.list() }
        cards = list
        cardTags = tagsOf(list)
    }

    suspend fun readCardThumbs() {
        cardThumbs = withContext(Dispatchers.IO) {
            cards.mapNotNull { c -> cardFiles.loadSmall(c.id, CARD_THUMB_PX)?.let { c.id to it } }
                .toMap()
        }
    }

    suspend fun readOpenCard() {
        val id = openCardId
        openCardBitmap = if (id == null) null else withContext(Dispatchers.IO) { cardFiles.load(id) }
    }

    // ---- changing it --------------------------------------------------------

    /**
     * Put a picture in the library and show it. Answers what went wrong, or
     * null.
     */
    suspend fun addPicture(context: Context, uri: Uri, label: String = ""): String? =
        when (val r = RefImport.add(context, refFiles, uri, label)) {
            is RefImport.Result.Added -> {
                pictures = listOf(r.picture) + pictures
                selected = r.picture.id
                null
            }
            is RefImport.Result.Failed -> r.reason
        }

    /** Lr12. Put a model in the library and show it. Answers what went wrong. */
    suspend fun addModel(context: Context, uri: Uri): String? =
        when (val r = RefModelImport.add(context, refFiles, uri)) {
            is RefModelImport.Result.Added -> {
                pictures = listOf(r.model) + pictures
                selected = r.model.id
                null
            }
            is RefModelImport.Result.Failed -> r.reason
        }

    /**
     * Keep the first frame the pane rendered of [id] as its face in the strip.
     *
     * The flag goes down first and unconditionally. A failed write is a poster
     * that will be made again the next time this model is opened, and asking
     * the pane for another one in the same second would be asking it to fail
     * again at sixty frames a second.
     */
    suspend fun keepPoster(id: String, shot: Bitmap) {
        posterWanted = false
        val written = withContext(Dispatchers.IO) { refFiles.setPoster(id, shot) }
        if (written) readThumbs()
    }

    /** Give the renderer back. Called when the screen goes; see [rememberLearner]. */
    fun closeModels() {
        modelBytes = null
        stage.shutdown()
    }

    /**
     * Forget a picture.
     *
     * The list first, so the panel never draws a row whose file has gone. The
     * delete is the slow half and it cannot fail in a way the user could act on.
     */
    suspend fun removePicture(id: String) {
        pictures = pictures.filterNot { it.id == id }
        if (selected == id) selected = pictures.firstOrNull()?.id
        withContext(Dispatchers.IO) { refFiles.delete(id) }
    }

    /** A card has been written. Put it at the front. */
    fun kept(card: Card) {
        cards = listOf(card) + cards
        cardTags = tagsOf(cards)
    }

    suspend fun removeCard(card: Card) {
        cards = cards.filterNot { it.id == card.id }
        if (openCardId == card.id) openCardId = null
        withContext(Dispatchers.IO) { cardFiles.delete(card.id) }
    }

    /** Lr9. Begin a session over everything in the library. */
    fun startPractice(ms: Long) {
        val ids = pictures.map { it.id }
        if (ids.isEmpty()) return
        lengthMs = ms
        order = ids
        startedAt = System.currentTimeMillis()
        selected = ids.first()
        practice = PracticeState(running = true, index = 0, total = ids.size, startedAt = startedAt)
    }

    /** Stop, keeping whatever pages turned so there is something to look at. */
    fun stopPractice() {
        practice = practice.copy(running = false, finished = practice.drawn.isNotEmpty())
    }

    /**
     * Let the contact sheet go.
     *
     * Released here rather than left to the collector: twenty pages is tens of
     * megabytes and nothing else is holding them.
     */
    fun clearPractice() {
        for ((_, bmp) in practice.drawn) bmp?.recycle()
        practice = PracticeState()
    }

    private fun tagsOf(list: List<Card>): List<String> =
        list.flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    companion object {
        /**
         * How big a strip thumbnail is decoded at, in pixels.
         *
         * An upper bound handed to `inSampleSize`, which rounds down to a power
         * of two, so what comes back is between this and half of it. Twice the
         * 62dp the strip draws them at, because a thumbnail decoded at exactly
         * its drawn size is soft on a 230 dpi panel.
         */
        const val THUMB_PX = 256

        /** See [THUMB_PX]; a card's row draws it at 54dp. */
        const val CARD_THUMB_PX = 192
    }
}

/**
 * The holder, and the five effects that keep it in step with the disk.
 *
 * The effects are here rather than in the screen for the holder's own reason:
 * each `LaunchedEffect` in `CanvasScreen` was a `remember` site in a method ART
 * had already refused to compile.
 */
@Composable
fun rememberLearner(context: Context): LearnerState {
    val learner = remember(context) { LearnerState(context) }
    LaunchedEffect(learner) { learner.readLibrary() }
    LaunchedEffect(learner.pictures) { learner.readThumbs() }
    // A different picture gets the pane back: whole, square, the right way
    // round and in colour. The rule the pane's own `remember(selected)` used to
    // encode, kept now that the state outlives the panel.
    LaunchedEffect(learner.selected) {
        learner.pane.reset()
        learner.readSelected()
    }
    LaunchedEffect(learner) { learner.readDeck() }
    LaunchedEffect(learner.cards) { learner.readCardThumbs() }
    LaunchedEffect(learner.openCardId) { learner.readOpenCard() }
    // The one thing here that has to be given back rather than collected: a
    // Filament engine holds native memory and a GL context, and this screen is
    // not the only thing on this tablet using the GPU.
    DisposableEffect(learner) { onDispose { learner.closeModels() } }
    return learner
}
