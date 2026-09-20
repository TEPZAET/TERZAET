package io.github.p1neapplexpress.openflux.data

/** Shared secret passed to OpenFlux via `--encryption-key-file`. */
object EncryptionKey {

    /** OpenFlux rejects shorter secrets (transport/encrypted.go). */
    const val MIN_BYTES = 16

    /** OpenFlux reads the key file through strings.TrimSpace. */
    fun normalize(raw: String): String = raw.trim()

    fun isValid(raw: String): Boolean =
        normalize(raw).toByteArray(Charsets.UTF_8).size >= MIN_BYTES
}
