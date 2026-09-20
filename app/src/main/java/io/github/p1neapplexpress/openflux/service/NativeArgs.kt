package io.github.p1neapplexpress.openflux.service

/** Builds the OpenFlux client command line from a stored tunnel payload. */
object NativeArgs {

    // Flags the app always sets itself; copies inside a stored payload are dropped.
    private val OWNED_WITH_VALUE = setOf("role", "r", "inbound", "i", "socks5", "s", "encryption-key-file")
    private val OWNED_BOOLEAN = setOf("client", "exit-node", "tun", "socks5-mode")

    private val SECRET_VALUES = setOf("maxToken")

    fun build(payload: List<String>, socksAddress: String, keyFile: String?): List<String> = buildList {
        add("--role"); add("client")
        add("--inbound"); add("socks5")
        add("--socks5"); add(socksAddress)
        if (keyFile != null) {
            add("--encryption-key-file"); add(keyFile)
        }

        var i = 0
        while (i < payload.size) {
            val arg = payload[i]
            val name = flagName(arg)
            i += when {
                name in OWNED_BOOLEAN -> 1
                name in OWNED_WITH_VALUE -> if ('=' in arg) 1 else 2
                else -> {
                    add(arg)
                    1
                }
            }
        }
    }

    /** Copy of [args] that is safe to show in the log view. */
    fun redact(args: List<String>): List<String> = args.mapIndexed { i, arg ->
        val name = flagName(arg)
        when {
            name in SECRET_VALUES && '=' in arg -> arg.substringBefore('=') + "=***"
            flagName(args.getOrNull(i - 1)) in SECRET_VALUES -> "***"
            else -> arg
        }
    }

    private fun flagName(arg: String?): String? =
        arg?.takeIf { it.startsWith("-") }?.trimStart('-')?.substringBefore('=')
}
