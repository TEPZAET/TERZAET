package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.p1neapplexpress.openflux.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelRepository(context: Context) {
    private val app = context.applicationContext
    private val plain = app.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)
    private val prefs: SharedPreferences = createEncryptedPreferences()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (prefs !== plain && !prefs.contains(Constants.PREF_TUNNELS_KEY)) {
            plain.getString(Constants.PREF_TUNNELS_KEY, null)?.let { tunnels ->
                prefs.edit {
                    putString(Constants.PREF_TUNNELS_KEY, tunnels)
                    putLong(Constants.PREF_SELECTED_TUNNEL_ID, plain.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L))
                }
                plain.edit {
                    remove(Constants.PREF_TUNNELS_KEY)
                    remove(Constants.PREF_SELECTED_TUNNEL_ID)
                }
            }
        }
    }

    private fun createEncryptedPreferences(): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "terzaet_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { plain }

    fun load(): List<Tunnel> {
        val raw = prefs.getString(Constants.PREF_TUNNELS_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Tunnel>>(raw) }.getOrElse { emptyList() }
    }

    fun save(tunnels: List<Tunnel>) {
        prefs.edit { putString(Constants.PREF_TUNNELS_KEY, json.encodeToString(tunnels)) }
    }

    fun getSelectedId(): Long? {
        val value = prefs.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L)
        return value.takeUnless { it == -1L }
    }

    fun setSelectedId(id: Long) {
        prefs.edit { putLong(Constants.PREF_SELECTED_TUNNEL_ID, id) }
    }

    fun getSelected(): Tunnel? {
        val tunnels = load()
        if (tunnels.isEmpty()) return null
        return tunnels.firstOrNull { it.id == getSelectedId() } ?: tunnels.first()
    }
}
