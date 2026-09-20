package io.github.p1neapplexpress.openflux.data

/** Transports of the bundled OpenFlux binary; [cliName] is the `--transport` value. */
enum class TransportType(val cliName: String) {
    yandex("yandex"),
    vyandex("vyandex"),
    max("oneme"),
    cupsonline("cupsonline"),
    mailru("mailru");

    /** MAX is configured with a token and a user id, every other transport with `--url`. */
    val usesUrl: Boolean get() = this != max

    companion object {
        fun from(raw: String): TransportType =
            entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.cliName.equals(raw, ignoreCase = true)
            } ?: yandex
    }
}
