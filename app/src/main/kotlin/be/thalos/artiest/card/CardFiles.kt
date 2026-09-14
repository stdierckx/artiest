package be.thalos.artiest.card

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The deck, as a directory of pictures and one small text file each.
 *
 * Lr7, and it is `RefFiles` again in every decision that is not new — two files
 * per card and no index, nothing throws, an id is a slug and a slug is a safe
 * file name. Those arguments are made there and are not repeated.
 *
 * Two things differ.
 *
 * **PNG, not JPEG.** A reference is a photograph and a card is a *drawing*: ink
 * on paper is exactly the content JPEG's blocks ruin, and a card is the thing
 * this program is for. The pictures are also smaller for it, because line art
 * compresses.
 *
 * **A card is one thing you can hand to somebody.** That is the whole reason
 * the format is a pair of small files rather than a row in a database: a
 * tutorial is a card somebody else made, and the way people already pass things
 * around is files.
 */
class CardFiles(private val dir: File) {

    /** Every card, newest first. */
    fun list(): List<Card> = runCatching {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(META) }
            ?: return emptyList()
        files.take(MAX_CARDS).mapNotNull { read(it) }.sortedByDescending { it.addedMs }
    }.getOrDefault(emptyList())

    /** Every tag in use, once each, alphabetically. */
    fun tags(): List<String> =
        list().flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * Keep [bitmap] as a card. Null if nothing could be written, and in that
     * case nothing is left behind.
     */
    fun add(
        bitmap: Bitmap,
        title: String,
        note: String,
        tags: List<String>,
        fromProject: String,
    ): Card? {
        val id = newId()
        val image = fileFor(id)
        val ok = runCatching {
            dir.mkdirs()
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }.getOrDefault(false)
        if (!ok || image.length() == 0L) {
            image.delete()
            return null
        }
        val card = Card(
            id = id,
            title = title.take(MAX_TITLE),
            note = note.take(MAX_NOTE),
            tags = tags.take(MAX_TAGS).map { it.take(MAX_TAG) },
            addedMs = System.currentTimeMillis(),
            fromProject = fromProject.take(MAX_TITLE),
            widthPx = bitmap.width,
            heightPx = bitmap.height,
        )
        if (!write(card)) {
            image.delete()
            return null
        }
        return card
    }

    fun load(id: String): Bitmap? = runCatching {
        val file = fileFor(id)
        if (!file.isFile) return null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    /** A small one, for the shelf. See `RefFiles.loadSmall`. */
    fun loadSmall(id: String, maxSide: Int): Bitmap? = runCatching {
        val file = fileFor(id)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun update(card: Card): Boolean = write(card)

    fun delete(id: String): Boolean = runCatching {
        if (!isSlug(id)) return false
        fileFor(id).delete()
        metaFor(id).delete()
        true
    }.getOrDefault(false)

    fun bytes(): Long = runCatching {
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }.getOrDefault(0L)

    private fun write(card: Card): Boolean = runCatching {
        if (!isSlug(card.id)) return false
        dir.mkdirs()
        metaFor(card.id).writeText(encode(card))
        true
    }.getOrDefault(false)

    private fun read(file: File): Card? = runCatching {
        if (file.length() > MAX_META_BYTES) return null
        val id = file.name.removeSuffix(META)
        if (!isSlug(id)) return null
        if (!fileFor(id).isFile) return null
        decode(id, file.readText())
    }.getOrNull()

    private fun fileFor(id: String) = File(dir, id + IMAGE)

    private fun metaFor(id: String) = File(dir, id + META)

    /** `key value` a line, and an unknown key is skipped. See `RefFiles`. */
    private fun encode(c: Card): String = buildString {
        append("added ").append(c.addedMs).append(LF)
        append("size ").append(c.widthPx).append(' ').append(c.heightPx).append(LF)
        if (c.title.isNotEmpty()) append("title ").append(oneLine(c.title)).append(LF)
        if (c.note.isNotEmpty()) append("note ").append(oneLine(c.note)).append(LF)
        if (c.fromProject.isNotEmpty()) append("from ").append(oneLine(c.fromProject)).append(LF)
        for (tag in c.tags) append("tag ").append(oneLine(tag)).append(LF)
    }

    private fun decode(id: String, text: String): Card {
        var added = 0L
        var w = 0
        var h = 0
        var title = ""
        var note = ""
        var from = ""
        val tags = ArrayList<String>()
        for (line in text.lineSequence()) {
            val at = line.indexOf(' ')
            if (at <= 0) continue
            val value = line.substring(at + 1)
            when (line.substring(0, at)) {
                "added" -> added = value.trim().toLongOrNull() ?: 0L
                "size" -> {
                    val parts = value.trim().split(' ')
                    w = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    h = parts.getOrNull(1)?.toIntOrNull() ?: 0
                }
                "title" -> title = value.take(MAX_TITLE)
                "note" -> note = value.take(MAX_NOTE)
                "from" -> from = value.take(MAX_TITLE)
                "tag" -> if (tags.size < MAX_TAGS) tags += value.take(MAX_TAG)
            }
        }
        return Card(id, title, note, tags, added, from, w, h)
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    private fun newId(): String {
        val stamp = System.currentTimeMillis()
        var id = "c$stamp"
        var n = 0
        while (metaFor(id).exists() || fileFor(id).exists()) {
            n++
            id = "c$stamp-$n"
        }
        return id
    }

    private fun isSlug(s: String) = s.isNotEmpty() && s.length <= 40 && s.all {
        it.isLetterOrDigit() || it == '-'
    }

    companion object {
        const val IMAGE = ".png"
        const val META = ".txt"

        /** A deck, not an archive. */
        const val MAX_CARDS = 300

        const val MAX_TITLE = 60

        /**
         * One sentence, and the number is the rule rather than a buffer size.
         * See [Card]: a note that can be an essay is a note-taking app.
         */
        const val MAX_NOTE = 240

        const val MAX_TAG = 24
        const val MAX_TAGS = 8

        private const val LF = '\n'
        private const val MAX_META_BYTES = 8192L
    }
}
