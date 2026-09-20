package io.github.p1neapplexpress.openflux

import android.app.Application
import io.github.p1neapplexpress.openflux.util.Logx

class OpenFluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(BuildConfig.DEBUG)
    }
}
