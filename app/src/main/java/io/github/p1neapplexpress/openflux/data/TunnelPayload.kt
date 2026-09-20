package io.github.p1neapplexpress.openflux.data

/**
 * Converts between the add/edit form and the OpenFlux command-line arguments
 * stored in [Tunnel.transportConnPayload].
 */
object TunnelPayload {

    data class Form(
        val transport: TransportType,
        val url: String = "",
        val maxToken: String = "",
        val maxUid: String = "",
        val legacyCodec: Boolean = false,
        val debug: Boolean = false,
    )

    /** Returns null when a field the transport needs is empty. */
    fun build(form: Form): List<String>? {
        val transportArgs = if (form.transport.usesUrl) {
            if (form.url.isEmpty()) return null
            listOf("--url", form.url)
        } else {
            if (form.maxToken.isEmpty() || form.maxUid.isEmpty()) return null
            listOf("--maxToken", form.maxToken, "--maxUid", form.maxUid)
        }
        return buildList {
            add("--role"); add("client")
            add("--transport"); add(form.transport.cliName)
            addAll(transportArgs)
            if (form.legacyCodec) {
                add("--codec"); add("legacy")
            }
            if (form.debug) add("--debug")
        }
    }

    fun parse(transportType: String, payload: List<String>): Form = Form(
        transport = TransportType.from(transportType),
        url = value(payload, "url"),
        maxToken = value(payload, "maxToken"),
        maxUid = value(payload, "maxUid"),
        legacyCodec = value(payload, "codec") == "legacy" || hasFlag(payload, "legacy"),
        debug = hasFlag(payload, "debug"),
    )

    /** Value of `--name value` or `--name=value` (one or two dashes); empty when absent. */
    fun value(payload: List<String>, name: String): String {
        payload.forEachIndexed { i, arg ->
            if (!arg.startsWith("-")) return@forEachIndexed
            val flag = arg.trimStart('-')
            if (flag == name) return payload.getOrNull(i + 1).orEmpty()
            if (flag.startsWith("$name=")) return flag.substringAfter('=')
        }
        return ""
    }

    private fun hasFlag(payload: List<String>, name: String): Boolean =
        payload.any { it.startsWith("-") && it.trimStart('-') == name }
}
