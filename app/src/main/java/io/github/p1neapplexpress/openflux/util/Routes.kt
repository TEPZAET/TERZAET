package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.VpnService
import io.github.p1neapplexpress.openflux.R

object Routes {
    fun addRoutes(context: Context, builder: VpnService.Builder, name: String?) {
        val routes: Array<String> = if (Constants.ROUTE_CHN == name) {
            context.resources.getStringArray(R.array.simple_route)
        } else {
            arrayOf("0.0.0.0/0")
        }

        for (r in routes) {
            val parts = r.split("/")
            if (parts.size != 2) continue
            val network = parts[0]
            if (network.startsWith("127")) continue
            val prefix = parts[1].toIntOrNull() ?: continue
            if (prefix !in 0..32) continue
            builder.addRoute(network, prefix)
        }
    }
}
