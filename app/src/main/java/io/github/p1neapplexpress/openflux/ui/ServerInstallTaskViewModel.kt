package io.github.p1neapplexpress.openflux.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.MailDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class InstallRequest(
    val name: String,
    val host: String,
    val user: String,
    val port: Int,
    val password: String,
    val document: String,
    val encryptionKey: String,
    val installMail: Boolean,
    val installHysteria: Boolean,
) {
    fun validConnection() = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && port in 1..65535 && password.isNotEmpty()
    fun validDeployment() = validConnection() && (installMail || installHysteria) && (!installMail || MailDocument.canonical(document) != null)
}

internal data class InstalledServer(val transport: String, val fingerprint: String, val hysteriaUri: String?) {
    val transportLabel: String get() = when (transport) {
        "mailru" -> "Mail Документы"
        "vyandex" -> "новый Яндекс Документ"
        else -> "классический Яндекс Документ"
    }
}

internal sealed interface InstallTaskState {
    data object Idle : InstallTaskState
    data class Running(val percent: Int, val message: String) : InstallTaskState
    data class Complete(val request: InstallRequest, val result: Result<InstalledServer>) : InstallTaskState
}

internal class ServerInstallTaskViewModel : ViewModel() {
    var stepIndex = 0
    var pendingRequest: InstallRequest? = null
    var readyTunnel: Tunnel? = null

    private val mutableState = MutableStateFlow<InstallTaskState>(InstallTaskState.Idle)
    val state = mutableState.asStateFlow()

    fun start(request: InstallRequest, work: ((Int, String) -> Unit) -> Result<InstalledServer>): Boolean {
        if (mutableState.value != InstallTaskState.Idle) return false
        mutableState.value = InstallTaskState.Running(2, "Подключение к серверу…")
        viewModelScope.launch(Dispatchers.IO) {
            var shownPercent = 2
            val result = runCatching {
                work { percent, message ->
                    shownPercent = maxOf(shownPercent, if (percent >= 100) 92 else percent.coerceIn(2, 99))
                    mutableState.value = InstallTaskState.Running(shownPercent, message)
                }.getOrThrow()
            }
            mutableState.value = InstallTaskState.Complete(request, result)
        }
        return true
    }

    fun clearResult() {
        if (mutableState.value is InstallTaskState.Complete) mutableState.value = InstallTaskState.Idle
    }
}
