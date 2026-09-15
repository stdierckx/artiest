package be.thalos.artiest.ref

/**
 * What a reference *is*, which decides both the file beside it and the pane
 * that shows it.
 *
 * Lr12. There is one library and one strip, not two. A learner who has put a
 * photograph of a hand and a scanned bust in front of them is not thinking
 * about which of the two is a mesh; they are thinking about hands. So a model
 * is a reference with a different payload, and everything that organises the
 * library — the tags, the strip, the delete, the practice clock — is untouched.
 *
 * The kind is written into the `.txt` as `kind model`, and only for models. A
 * picture's meta file is therefore byte-for-byte what an earlier build wrote,
 * which is what makes this change need no migration and no version number.
 */
enum class RefKind {
    /** `<id>.jpg`, and the pane draws it. */
    PICTURE,

    /**
     * `<id>.glb`, and the pane renders it.
     *
     * **GLB and not glTF.** A `.gltf` is a JSON file that names its meshes and
     * its textures as separate files beside it; copied into the library on its
     * own it is a reference that shows nothing, and the moment where that goes
     * wrong is hours after the import, in front of a drawing. A `.glb` carries
     * everything in one file, which is the only shape this library can store.
     */
    MODEL;

    /** The extension the payload is written under. */
    val extension: String get() = if (this == MODEL) ".glb" else ".jpg"
}
