package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import be.thalos.artiest.engine.brush.BrushCodec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Not a test: a contact sheet, run by hand.
 *
 * `docs/brushes-plan.md` Wb4 is a **judgement pass** — somebody looks at a
 * picture and says whether the imported brushes read as themselves — and this
 * is the picture. Krita's own preview of a preset sits on the left, our
 * renderer's swatch of the converted brush on the right, and the question the
 * sheet asks is whether the two are the same brush.
 *
 * It lives in the test source set because that is where Robolectric's NATIVE
 * graphics are, and it does nothing unless `artiest.sheet.in` is set, so a
 * plain `./gradlew test` skips it.
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*KritaSheetTool*' \
 *       -Dartiest.sheet.in=<converted dir> \
 *       -Dartiest.sheet.krita=<preview dir> \
 *       -Dartiest.sheet.tips=<tips dir> \
 *       -Dartiest.sheet.out=<sheet.png>
 *
 * Wb6 is the same pass over the brushes that stamp a picture, and `sheet.tips`
 * is the whole of what it adds: without it a tipped brush has no tip, falls
 * back to the procedural nib, and the sheet quietly judges the wrong thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KritaSheetTool {

    @Test
    fun sheet() {
        val inDir = System.getProperty("artiest.sheet.in") ?: return
        val kritaDir = System.getProperty("artiest.sheet.krita")
        val out = System.getProperty("artiest.sheet.out") ?: return
        System.getProperty("artiest.sheet.tips")?.let {
            println("TIPS ${Tips.loadDirectory(File(it))} loaded")
        }

        val files = File(inDir).listFiles { f: File -> f.name.endsWith(".brush") }
            ?.sortedBy { it.name } ?: return

        val rowH = 150
        val labelW = 330
        val kritaW = 150
        val ourW = 560
        val width = labelW + kritaW + ourW + 40
        val sheet = Bitmap.createBitmap(width, rowH * files.size + 20, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 120)
            textSize = 20f
        }
        val paper = Paint().apply { color = Color.rgb(0xE6, 0xE3, 0xDC) }
        val blit = Paint().apply { isFilterBitmap = true }

        var made = 0
        for ((i, file) in files.withIndex()) {
            val entry = BrushCodec.decodeFile(file.readText()) ?: continue
            val top = 10 + i * rowH
            canvas.drawText(entry.label, 12f, (top + 44).toFloat(), text)
            val brush = entry.create()
            canvas.drawText(
                "size ${brush.sizeMax.toInt()}  hard ${"%.2f".format(brush.hardness)}  " +
                    "op ${"%.2f".format(brush.opacity)}  flow ${"%.2f".format(brush.flow)}",
                12f, (top + 76).toFloat(), small,
            )
            canvas.drawText(
                "aspect ${"%.2f".format(brush.aspect.max)}  " +
                    "sensors ${brush.size.inputCount}/${brush.flowOption.inputCount}" +
                    (brush.tip?.let { "  tip $it" } ?: ""),
                12f, (top + 104).toFloat(), small,
            )

            // Krita's own preview of the preset: the `.kpp` is a PNG and the
            // picture in it is the control this is judged against.
            val preview = kritaDir?.let { File(it, file.name.removeSuffix(".brush") + ".png") }
            if (preview != null && preview.isFile) {
                BitmapFactory.decodeFile(preview.path)?.let { src ->
                    val box = Rect(labelW, top + 6, labelW + kritaW - 10, top + rowH - 12)
                    canvas.drawRect(box, paper)
                    canvas.drawBitmap(src, Rect(0, 0, src.width, src.height), box, blit)
                    src.recycle()
                }
            }

            val x = labelW + kritaW
            val swatch = BrushSwatch.render(brush, ourW, rowH - 18, Color.BLACK)
            canvas.drawRect(
                Rect(x, top + 6, x + ourW, top + rowH - 12), paper,
            )
            canvas.drawBitmap(swatch, x.toFloat(), (top + 6).toFloat(), null)
            swatch.recycle()
            made++
        }

        File(out).parentFile?.mkdirs()
        FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHEET $made rows -> $out")
    }
}
