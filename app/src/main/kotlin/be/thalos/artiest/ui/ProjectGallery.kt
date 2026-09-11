package be.thalos.artiest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.thalos.artiest.project.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The drawings you have, as pictures of themselves.
 *
 * ## Why it takes the whole screen
 *
 * Because the only thing a list of drawings is for is recognising one, and a
 * drawing is recognised by looking at it. A menu of names would be this
 * feature's version of asking somebody to remember what `untitled-4` was, which
 * is the state of affairs every drawing app on a tablet has already decided
 * against. So: cards, big enough to tell a nose from a landscape, and the name
 * underneath where it belongs — as a label on a picture rather than as the
 * thing you have to read.
 *
 * It opens over everything and closes again. It is not a mode: nothing about
 * the document changes while it is open, and dismissing it puts you back
 * exactly where you were.
 *
 * ## What it offers
 *
 * New, open, rename, duplicate, delete — the same five the workspace menu grew,
 * with the same shapes, because they are the same five actions on the same kind
 * of thing and a user who has learned one should not have to learn the other.
 * Delete asks with the name in the question, for the reason it does there and
 * more so here: this is the one button in the app that destroys work.
 *
 * ## The thumbnails are read here and not held
 *
 * One decode per card, on [Dispatchers.IO], through `produceState` so it follows
 * the card in and out of the list. A gallery of twenty drawings is twenty 8 KB
 * pictures; holding them in a cache that outlives the screen would be holding a
 * copy of every drawing the user has ever made for the sake of a list they open
 * once a session.
 */
@Composable
internal fun ProjectGallery(
    entries: List<ProjectFiles.Entry>,
    currentId: String,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** Write this drawing out as an `.ora` that Krita and GIMP can open. */
    onExport: (String) -> Unit,
    /** Take one in. The picker is the caller's; this only asks for it. */
    onImport: () -> Unit,
    /** What the last export or import said, or empty. */
    note: String,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ProjectFiles.Entry?>(null) }
    var copying by remember { mutableStateOf<ProjectFiles.Entry?>(null) }
    var deleting by remember { mutableStateOf<ProjectFiles.Entry?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            // Opaque, and it consumes the pointer. A gallery you can draw
            // through is a gallery that puts a stroke on the drawing you were
            // about to leave.
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) {}
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    "Drawings",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "  ${entries.size}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.isNotEmpty()) {
                    Text(
                        "   $note",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.weight(1f))
                GalleryButton(ToolIcons.import_, "Open an .ora", onImport)
                Box(Modifier.size(10.dp))
                GalleryButton(ToolIcons.plus, "New drawing", onNew)
                Box(Modifier.size(10.dp))
                GalleryButton(ToolIcons.close, "Back to the drawing", onDismiss)
            }

            if (entries.isEmpty()) {
                Text(
                    "nothing yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = CARD_MIN),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ProjectCard(
                        entry = entry,
                        current = entry.id == currentId,
                        onOpen = { onOpen(entry.id) },
                        onRename = { renaming = entry },
                        onDuplicate = { copying = entry },
                        onDelete = { deleting = entry },
                        onExport = { onExport(entry.id) },
                    )
                }
            }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename this drawing",
            confirm = "Rename",
            initial = entry.name,
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; onRename(entry.id, it) },
        )
    }

    copying?.let { entry ->
        NameDialog(
            title = "Copy this drawing as",
            confirm = "Copy",
            initial = Workspace.copyName(entry.name, entries.map { it.name }),
            onDismiss = { copying = null },
            onConfirm = { copying = null; onDuplicate(entry.id, it) },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${entry.name}?", fontSize = 16.sp) },
            text = {
                Text(
                    "Every layer of it goes. There is no way back from this one.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(entry.id) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep it") }
            },
        )
    }
}

/** One drawing: its picture, its name, and when it was last touched. */
@Composable
private fun ProjectCard(
    entry: ProjectFiles.Entry,
    current: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                // The page's own shape, near enough. A card whose picture is a
                // different shape from the paper reads as a different drawing.
                .aspectRatio(1.53f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainer)
                .border(
                    if (current) 2.dp else 1.dp,
                    if (current) scheme.primary else scheme.outlineVariant,
                    RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onOpen),
        ) {
            val picture = thumbnailOf(entry.thumbnail)
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(1.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 13.sp,
                    color = scheme.onSurface,
                )
                Text(
                    when {
                        entry.modified <= 0L -> "not saved yet"
                        else -> WHEN.format(Date(entry.modified))
                    },
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Box {
                GalleryButton(ToolIcons.grip, "More", { menu = true }, size = 26.dp, glyph = 12.dp)
                if (menu) {
                    DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename…", fontSize = 13.sp) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy…", fontSize = 13.sp) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            // It is the drawing that is exported, not the card,
                            // so this opens it first if it is not already open.
                            // See MainActivity: the merged image the format
                            // requires is a picture of the live document.
                            text = { Text("Export as .ora…", fontSize = 13.sp) },
                            onClick = { menu = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete…", fontSize = 13.sp) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One thumbnail, decoded off the main thread, or null while it is being read
 * and for a project that has none.
 *
 * Keyed on the file *and its length*, so that a card whose drawing has been
 * saved again picks up the new picture rather than the one it decoded when the
 * gallery opened.
 */
@Composable
private fun thumbnailOf(file: File?): ImageBitmap? {
    if (file == null) return null
    var picture by remember(file.path, file.length()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path, file.length()) {
        picture = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return picture
}

@Composable
private fun GalleryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    glyph: androidx.compose.ui.unit.Dp = 17.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, label, Modifier.size(glyph), MaterialTheme.colorScheme.onSurface)
    }
}

/** Wide enough for a drawing to be recognisable on this tablet, narrow enough for four. */
private val CARD_MIN = 200.dp

/** The day and the time. A year is noise on a tablet nobody has had for one. */
private val WHEN = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
