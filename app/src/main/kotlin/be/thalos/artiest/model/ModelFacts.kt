package be.thalos.artiest.model

import org.json.JSONObject

/**
 * What can be known about a model from its bytes, without a renderer.
 *
 * Lr12. One question so far, and it is asked before the model is opened, which
 * is why it is here and not on `ModelStage`: the pane has to know what the
 * model is wearing in order to decide what to dress it in.
 */

/**
 * Whether this model brings a surface of its own worth looking at.
 *
 * True when the glTF carries at least one image — a photographic scan, the
 * colour of real marble and real dust, and the thing a reference of that kind
 * is worth having. False when it does not, and that covers both of the ways a
 * model arrives bare: no materials at all, which glTF says is plain white, and
 * the single flat near-white material a mesh-only format like STL gets given
 * when it is converted, which comes to the same thing on screen.
 *
 * Both of those are unusable as references — see `stone.mat` for why white is
 * the one value form does not show in — so the pane opens them in stone.
 *
 * Anything that cannot be read is answered *true*: not knowing is not a reason
 * to paint over a scan that may well have its own surface. The same goes for a
 * JSON chunk too big to be worth parsing on the way to opening a panel, which
 * in practice means a scene of thousands of nodes and not anything in this
 * library.
 */
fun wearsItsOwnSurface(glb: ByteArray): Boolean {
    val json = jsonChunk(glb) ?: return true
    return runCatching {
        JSONObject(json).optJSONArray("images")?.length() ?: 0
    }.getOrDefault(1) > 0
}

/**
 * The text of a GLB's first chunk, if it is the JSON one.
 *
 * The container is a 12-byte header and then chunks, each a length, a
 * four-character type and that many bytes. glTF 2 requires the JSON chunk to be
 * the first, so there is no need to walk the rest.
 */
private fun jsonChunk(glb: ByteArray): String? {
    if (glb.size < HEADER + CHUNK_HEADER) return null
    val length = le32(glb, HEADER)
    if (le32(glb, HEADER + 4) != JSON_CHUNK) return null
    if (length <= 0 || length > MAX_JSON) return null
    if (HEADER + CHUNK_HEADER + length > glb.size) return null
    return String(glb, HEADER + CHUNK_HEADER, length, Charsets.UTF_8)
}

private fun le32(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)

/** Magic, version, total length. */
private const val HEADER = 12

/** Length and type. */
private const val CHUNK_HEADER = 8

/** `JSON`, little-endian. */
private const val JSON_CHUNK = 0x4E4F534A

/**
 * Four megabytes of JSON is tens of thousands of nodes. Past this the answer is
 * *it has its own surface*, because the cost of finding out is worse than being
 * wrong about a model this library does not hold.
 */
private const val MAX_JSON = 4 * 1024 * 1024
