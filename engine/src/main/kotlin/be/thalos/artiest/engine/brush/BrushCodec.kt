package be.thalos.artiest.engine.brush

/**
 * A brush as text, and back.
 *
 * **Line-based key/value rather than JSON**, for the reasons `ToolbarCodec`
 * gives and one more. `:engine` has no dependencies by construction and is not
 * going to acquire a JSON library to store fifteen numbers; the format has to
 * be diffable, because a preset is something a person edits and a change to one
 * should read as a change to one; and every value here is a float, an int or an
 * enum name, so there is nothing a structured format would buy.
 *
 * **Floats go through `Float.toString`**, the same discipline `TraceFormat` and
 * `DabGoldenTest` use and for the same reason: `String.format` is
 * locale-sensitive, and under nl-BE a decimal comma turns every number into a
 * parse error on one developer's machine and nowhere else. `Float.toString`
 * round-trips exactly and consults no locale.
 *
 * **Decoding never throws.** An unreadable brush returns null and the caller
 * uses its default; a line it does not understand is skipped, not fatal. That
 * is what lets a preset written by a later build load in an earlier one with
 * the parameters it does understand — the alternative is that adding a brush
 * parameter invalidates every file anyone has saved.
 */
object BrushCodec {

    const val MAGIC = "artiest-brush"
    const val VERSION = 1

    fun encode(brush: Brush): String {
        val b = StringBuilder()
        b.append(MAGIC).append(' ').append(VERSION).append('\n')
        b.append("size ").append(brush.sizeMin).append(' ').append(brush.sizeMax).append('\n')
        b.append("sizeCurve ").append(curveToText(brush.sizeCurve)).append('\n')
        b.append("spacing ").append(brush.spacing).append('\n')
        b.append("isotropic ").append(if (brush.isotropicSpacing) 1 else 0).append('\n')
        b.append("hardness ").append(brush.hardness).append('\n')
        b.append("opacity ").append(brush.opacity).append('\n')
        // Two lines because they are two parameters: `flow` is the ceiling a
        // slider owns and `flowMin` is the floor a preset owns. The floor had
        // no line at all, so a saved pencil came back with a floor of zero and
        // the lightest touch left nothing rather than a faint mark.
        b.append("flowMin ").append(brush.flowOption.min).append('\n')
        b.append("flow ").append(brush.flow).append('\n')
        b.append("stabilization ").append(brush.stabilization).append('\n')
        b.append("antialias ").append(if (brush.antiAlias) 1 else 0).append('\n')
        b.append("erase ").append(if (brush.erase) 1 else 0).append('\n')
        b.append("onset ").append(brush.onsetMillis).append(' ').append(brush.onsetPressure)
            .append('\n')
        val g = brush.grain
        b.append("grain ").append(g.scaleDocPx).append(' ').append(g.strength).append(' ')
            .append(g.cutoffLow).append(' ').append(g.cutoffHigh).append(' ').append(g.seed)
            .append('\n')
        // The two options whose *numbers* are already above, under `size` and
        // `flow`, so only their wiring is written here. Without these lines a
        // saved pencil reloaded as a brush whose tilt drove nothing and whose
        // pressure drove nothing -- visibly, a fat pen -- and the tilt
        // complaint survived a fix that was already in the preset.
        //
        // Wiring only, and not a second copy of min and max, because one
        // parameter is one line: `BrushCodecTest` asserts that changing `flow`
        // moves exactly one line of the file, and a format where it moves two
        // is a format where a diff stops being readable.
        b.append("burnish ").append(brush.burnish).append('\n')
        appendWiring(b, "sizeOpt", brush.size)
        appendWiring(b, "flowOpt", brush.flowOption)
        appendOption(b, "aspect", brush.aspect)
        appendOption(b, "rotation", brush.rotation)
        appendOption(b, "scatter", brush.scatter)
        appendOption(b, "sizeJitter", brush.sizeJitter)
        return b.toString()
    }

    /** The brush [text] describes, or null if it is not a brush at all. */
    fun decode(text: String?): Brush? {
        if (text == null) return null
        val lines = text.split('\n')
        if (lines.isEmpty()) return null
        val head = lines[0].trim().split(' ')
        if (head.size < 2 || head[0] != MAGIC) return null
        if (head[1].toIntOrNull() == null) return null
        val brush = Brush()
        // Options are cleared once up front rather than per line: a file that
        // names `aspect.drive` twice means two sensors, and clearing on the
        // first would silently keep only the last.
        for (o in listOf(
            brush.size, brush.flowOption,
            brush.aspect, brush.rotation, brush.scatter, brush.sizeJitter,
        )) {
            o.clearInputs()
        }
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ')
            applyLine(brush, parts)
        }
        return brush
    }

    private fun applyLine(brush: Brush, p: List<String>) {
        when (p[0]) {
            "size" -> if (p.size >= 3) {
                f(p[1])?.let { brush.sizeMin = it }
                f(p[2])?.let { brush.sizeMax = it }
            }
            "sizeCurve" -> curveFromText(p, 1)?.let { brush.sizeCurve = it }
            "spacing" -> f(p, 1)?.let { brush.spacing = it }
            "isotropic" -> brush.isotropicSpacing = p.getOrNull(1) == "1"
            "hardness" -> f(p, 1)?.let { brush.hardness = it }
            "opacity" -> f(p, 1)?.let { brush.opacity = it }
            "flow" -> f(p, 1)?.let { brush.flow = it }
            "flowMin" -> f(p, 1)?.let { brush.flowOption.min = it }
            "burnish" -> f(p, 1)?.let { brush.burnish = it }
            "stabilization" -> f(p, 1)?.let { brush.stabilization = it }
            "antialias" -> brush.antiAlias = p.getOrNull(1) != "0"
            "erase" -> brush.erase = p.getOrNull(1) == "1"
            "onset" -> if (p.size >= 3) {
                f(p[1])?.let { brush.onsetMillis = it }
                f(p[2])?.let { brush.onsetPressure = it }
            }
            "grain" -> if (p.size >= 6) {
                val scale = f(p[1]) ?: return
                val strength = f(p[2]) ?: return
                val lo = f(p[3]) ?: return
                val hi = f(p[4]) ?: return
                val seed = p[5].toIntOrNull() ?: return
                // GrainSpec's own `require`s reject an impossible combination,
                // and a file is exactly where an impossible one comes from.
                // Refusing the line leaves the default rather than the exception.
                runCatching { GrainSpec(scale, strength, lo, hi, seed) }
                    .getOrNull()?.let { brush.grain = it }
            }
            else -> optionLine(brush, p)
        }
    }

    private fun optionLine(brush: Brush, p: List<String>) {
        val name = p[0].substringBefore('.')
        val option = when (name) {
            "sizeOpt" -> brush.size
            "flowOpt" -> brush.flowOption
            "aspect" -> brush.aspect
            "rotation" -> brush.rotation
            "scatter" -> brush.scatter
            "sizeJitter" -> brush.sizeJitter
            else -> return
        }
        if (p[0].endsWith(".combine")) {
            CurveOption.Combine.entries.firstOrNull { it.name == p.getOrNull(1) }?.let {
                option.combine = it
            }
            return
        }
        if (p[0].endsWith(".drive")) {
            if (p.size < 2) return
            val sensor = Sensor.entries.firstOrNull { it.name == p[1] } ?: return
            val curve = curveFromText(p, 2) ?: ResponseCurve.LINEAR
            option.drive(sensor, curve)
            return
        }
        if (p.size >= 3) {
            f(p[1])?.let { option.min = it }
            f(p[2])?.let { option.max = it }
        }
        if (p.size >= 4) {
            CurveOption.Combine.entries.firstOrNull { it.name == p[3] }?.let { option.combine = it }
        }
    }

    /** [appendOption] without the min/max line. See [encode]. */
    private fun appendWiring(b: StringBuilder, name: String, o: CurveOption) {
        b.append(name).append(".combine ").append(o.combine.name).append('\n')
        appendDrives(b, name, o)
    }

    private fun appendOption(b: StringBuilder, name: String, o: CurveOption) {
        b.append(name).append(' ').append(o.min).append(' ').append(o.max).append(' ')
            .append(o.combine.name).append('\n')
        appendDrives(b, name, o)
    }

    private fun appendDrives(b: StringBuilder, name: String, o: CurveOption) {
        for (i in 0 until o.inputCount) {
            b.append(name).append(".drive ").append(o.sensorAt(i).name).append(' ')
                .append(curveToText(o.curveAt(i))).append('\n')
        }
    }

    private fun curveToText(c: ResponseCurve): String {
        if (c.isPower) return "pow ${c.power}"
        val b = StringBuilder("points")
        for (i in 0 until c.pointCount) {
            b.append(' ').append(c.pointX(i)).append(' ').append(c.pointY(i))
        }
        return b.toString()
    }

    private fun curveFromText(p: List<String>, from: Int): ResponseCurve? {
        if (p.size <= from) return null
        return when (p[from]) {
            "pow" -> f(p, from + 1)?.let { runCatching { ResponseCurve.power(it) }.getOrNull() }
            "points" -> {
                val n = (p.size - from - 1) / 2
                if (n < 1) return null
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                for (i in 0 until n) {
                    xs[i] = f(p[from + 1 + i * 2]) ?: return null
                    ys[i] = f(p[from + 2 + i * 2]) ?: return null
                }
                runCatching { ResponseCurve.ofPoints(xs, ys) }.getOrNull()
            }
            else -> null
        }
    }

    private fun f(s: String): Float? = s.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun f(p: List<String>, i: Int): Float? = p.getOrNull(i)?.let { f(it) }
}
