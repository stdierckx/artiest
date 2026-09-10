package be.thalos.artiest.ui

import java.io.File

/**
 * Writes `docs/catalogue.json`. Run by `./gradlew :app:catalogueJson`.
 *
 * It lives in the test source set rather than in `main` for one reason: it must
 * not be in the APK. A `main` function that writes a file to a path given on
 * the command line is not something to ship on a tablet, and the test classpath
 * is the one place in this module that already runs on a plain JVM.
 */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: "docs/catalogue.json")
    target.parentFile?.mkdirs()
    target.writeText(ToolCatalogue.json())
    println("wrote ${target.absolutePath} (${ToolItem.entries.size} tools, v${ToolCatalogue.VERSION})")
}
