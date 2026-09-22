package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelPayload
import io.github.p1neapplexpress.openflux.data.ServerRelease
import io.github.p1neapplexpress.openflux.event.AppEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import io.github.p1neapplexpress.openflux.data.EncryptionKey
import io.github.p1neapplexpress.openflux.data.ConnectionMode
import kotlin.random.Random
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URL

class ServerInstallFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var spinner: ProgressBar
    private lateinit var statusContainer: LinearLayout
    private lateinit var button: MaterialButton
    private lateinit var wizardStep: TextView
    private var stepIndex = 0
    private var pendingRequest: InstallRequest? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_server_install, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        status = view.findViewById(R.id.installStatus)
        progress = view.findViewById(R.id.installProgress)
        spinner = view.findViewById(R.id.installSpinner)
        statusContainer = view.findViewById(R.id.installStatusContainer)
        button = view.findViewById(R.id.installButton)
        wizardStep = view.findViewById(R.id.wizardStep)
        setWizardStep(0)
        val name = view.findViewById<EditText>(R.id.serverName)
        val host = view.findViewById<EditText>(R.id.serverHost)
        val user = view.findViewById<EditText>(R.id.serverUser)
        val port = view.findViewById<EditText>(R.id.serverPort)
        val password = view.findViewById<EditText>(R.id.serverPassword)
        val document = view.findViewById<EditText>(R.id.serverDocument)
        val encryption = view.findViewById<EditText>(R.id.serverEncryption)
        val hysteriaSwitch = view.findViewById<MaterialSwitch>(R.id.serverHysteriaSwitch)
        val encryptionSwitch = view.findViewById<MaterialSwitch>(R.id.serverEncryptionSwitch)
        val encryptionContainer = view.findViewById<View>(R.id.serverEncryptionContainer)
        val copyKey = view.findViewById<View>(R.id.copyEncryptionKey)
        view.findViewById<TextInputLayout>(R.id.serverDocumentContainer).setEndIconOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Как подготовить документ")
                .setMessage("1. Откройте Яндекс Документы и создайте пустой документ.\n\n2. Нажмите «Поделиться». Включите доступ «По ссылке» и выберите «Может редактировать» — это обязательно.\n\n3. Скопируйте ссылку на документ и вставьте её сюда. После установки не удаляйте документ и не меняйте ему доступ.\n\nНе добавляйте личные данные: TERZAET использует документ только как транспорт. Если Яндекс покажет CAPTCHA, создайте новый документ или смените IP VDS.")
                .setPositiveButton("Понятно", null)
                .show()
        }

        encryptionSwitch.setOnCheckedChangeListener { _, enabled ->
            encryptionContainer.isVisible = enabled
            copyKey.isVisible = enabled
            if (enabled && encryption.text.isNullOrBlank()) encryption.setText(generateKey())
            if (!enabled) encryption.text.clear()
        }
        copyKey.setOnClickListener {
            val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("TERZAET key", encryption.text.toString()))
            status.text = "Ключ скопирован"
        }

        button.setOnClickListener {
            val request = InstallRequest(
                name = name.text.toString().trim(),
                host = host.text.toString().trim(),
                user = user.text.toString().trim(),
                port = port.text.toString().toIntOrNull() ?: 22,
                password = password.text.toString(),
                document = document.text.toString().trim(),
                encryptionKey = encryption.text.toString().takeIf { encryptionSwitch.isChecked }.orEmpty(),
                installHysteria = hysteriaSwitch.isChecked,
            )
            if (!encryptionSwitch.isChecked) encryption.text.clear()
            if (stepIndex == 3) {
                requireActivity().findViewById<ViewPager2>(R.id.view_pager)?.setCurrentItem(0, true)
                return@setOnClickListener
            }
            if (stepIndex == 1) {
                setWizardStep(2)
                return@setOnClickListener
            }
            if (stepIndex == 2) {
                pendingRequest?.let(::install)
                return@setOnClickListener
            }
            if (!request.valid()) {
                status.text = "Заполните название, IP, логин, пароль и ссылку на документ. Ключ можно оставить пустым."
                return@setOnClickListener
            }
            if (request.encryptionKey.isNotBlank() && !EncryptionKey.isValid(request.encryptionKey)) {
                status.text = "Ключ шифрования должен содержать минимум 16 символов или оставьте поле пустым."
                return@setOnClickListener
            }
            password.text.clear()
            pendingRequest = request
            inspectThenInstall(request)
        }
    }

    private fun install(request: InstallRequest) {
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        progress.progress = 2
        statusContainer.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_glass_field)
        status.text = "Подключение к серверу…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runInstaller(request) { percent, message ->
                    progress.post { progress.animate().cancel(); progress.setProgress(percent, true) }
                    status.post { status.text = "$percent% · $message" }
                }
            }
            progress.visibility = View.GONE
            spinner.visibility = View.GONE
            button.isEnabled = true
            result.onSuccess { installed ->
                val transport = if (installed.transport == "vyandex") TransportType.vyandex else TransportType.yandex
                val payload = TunnelPayload.build(TunnelPayload.Form(transport = transport, url = request.document))
                if (payload == null) {
                    status.text = "[CFG-601] Не удалось создать конфигурацию"
                    return@onSuccess
                }
                val tunnel = Tunnel(
                    id = Random(System.currentTimeMillis()).nextLong(),
                    name = request.name,
                    transportType = transport.name,
                    transportConnPayload = payload,
                    encryptionKey = request.encryptionKey.takeIf { it.isNotBlank() }?.let(EncryptionKey::normalize),
                    adminHost = request.host,
                    adminUser = request.user,
                    adminPort = request.port,
                    serverRevision = ServerRelease.REVISION,
                    hysteriaUri = installed.hysteriaUri,
                    connectionMode = if (installed.hysteriaUri != null) ConnectionMode.auto.name else ConnectionMode.yandex.name,
                    autoFallback = installed.hysteriaUri != null,
                )
                vm.addTunnel(tunnel)
                vm.selectTunnel(tunnel)
                statusContainer.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_success)
                status.text = if (installed.hysteriaUri != null) {
                    "100% · Сервер установлен\n${installed.transportLabel} + Hysteria 2"
                } else {
                    "100% · Сервер установлен\n${installed.transportLabel}"
                }
                setWizardStep(3)
                status.text = "Сервер готов\nНажмите «Протестировать подключение»"
            }.onFailure { error ->
                statusContainer.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_error)
                status.text = friendlyError(error)
            }
        }
    }

    private fun inspectThenInstall(request: InstallRequest) {
        button.isEnabled = false
        spinner.visibility = View.VISIBLE
        status.text = "Проверка VDS…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    verifyDocument(request.document)
                    val check = preflight(request).getOrThrow()
                    val found = detectExisting(request).getOrThrow()
                    check to found
                }
            }
            spinner.visibility = View.GONE
            button.isEnabled = true
            result.onFailure {
                statusContainer.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_error)
                status.text = friendlyError(it)
            }.onSuccess { (check, found) ->
                status.text = "Аудит VDS завершён\n${check.os} · CPU ${check.cpu} · RAM ${check.ram} · диск ${check.freeGb} ГБ\nDocker ${if (check.dockerReady) "готов" else "будет установлен"} · HTTPS доступен"
                if (!found.terzaet && !found.openFlux) {
                    pendingRequest = request
                    status.text = "Аудит завершён · конфликтов не найдено\nПроверьте протоколы на следующем шаге"
                    setWizardStep(1)
                    return@onSuccess
                }
                val labels = buildList {
                    if (found.terzaet) add("TERZAET: контейнер и /opt/terzaet")
                    if (found.openFlux) add("OpenFlux: контейнер, служба и /opt/fluxglass")
                }
                val checked = BooleanArray(labels.size) { true }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("На VDS найдена предыдущая установка")
                    .setMultiChoiceItems(labels.toTypedArray(), checked) { _, index, value -> checked[index] = value }
                    .setNeutralButton("Оставить и продолжить") { _, _ ->
                        pendingRequest = request
                        setWizardStep(1)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("Удалить выбранное") { _, _ ->
                        var cursor = 0
                        val removeTerzaet = found.terzaet && checked[cursor++]
                        val removeOpenFlux = found.openFlux && checked[cursor]
                        removeSelectedThenInstall(request, removeTerzaet, removeOpenFlux)
                    }
                    .show()
            }
        }
    }

    private fun verifyDocument(value: String) {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Range", "bytes=0-1024")
        val code = connection.responseCode
        val finalUrl = connection.url.toString().lowercase()
        connection.disconnect()
        if (code !in 200..399 || "passport.yandex" in finalUrl || "auth" in finalUrl) error("DOCUMENT_PRIVATE")
    }

    private fun preflight(request: InstallRequest): Result<Preflight> = runCatching {
        val output = runSshCommand(
            request,
            "d=0; p=0; h=0; " +
                "command -v docker >/dev/null 2>&1 && d=1 || true; " +
                "(command -v apt-get >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1 || command -v apk >/dev/null 2>&1) && p=1 || true; " +
                "f=\$(df -Pk / | awk 'NR==2 {print \$4}'); " +
                "(curl -fsI --max-time 12 https://disk.yandex.ru >/dev/null 2>&1 || wget -q --spider -T 12 https://disk.yandex.ru >/dev/null 2>&1) && h=1 || true; " +
                "cpu=\$(nproc 2>/dev/null || echo '?'); ram=\$(awk '/MemTotal/ {printf \"%.1fG\", \$2/1048576}' /proc/meminfo 2>/dev/null || echo '?'); os=\$(. /etc/os-release 2>/dev/null && printf '%s' \"\$PRETTY_NAME\" || uname -s); printf 'DOCKER=%s PACKAGE=%s FREE=%s HTTPS=%s CPU=%s RAM=%s OS=%s' \"\$d\" \"\$p\" \"\$f\" \"\$h\" \"\$cpu\" \"\$ram\" \"\$os\""
        )
        val docker = output.contains("DOCKER=1")
        val packageManager = output.contains("PACKAGE=1")
        val https = output.contains("HTTPS=1")
        val freeKb = Regex("FREE=(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (!docker && !packageManager) error("PREFLIGHT_DOCKER")
        if (freeKb < 1_048_576L) error("PREFLIGHT_SPACE")
        if (!https) error("PREFLIGHT_HTTPS")
        val cpu = Regex("CPU=([^ ]+)").find(output)?.groupValues?.get(1) ?: "?"
        val ram = Regex("RAM=([^ ]+)").find(output)?.groupValues?.get(1) ?: "?"
        val os = Regex("OS=(.+)$").find(output)?.groupValues?.get(1)?.trim() ?: "Linux"
        Preflight(docker, freeKb / 1_048_576L, cpu, ram, os)
    }

    private fun removeSelectedThenInstall(request: InstallRequest, terzaet: Boolean, openFlux: Boolean) {
        if (!terzaet && !openFlux) {
            install(request)
            return
        }
        button.isEnabled = false
        spinner.visibility = View.VISIBLE
        status.text = "Удаление выбранных компонентов…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { removeDetected(request, terzaet, openFlux) }
            spinner.visibility = View.GONE
            button.isEnabled = true
            result.onSuccess {
                pendingRequest = request
                status.text = "Аудит завершён · старые компоненты удалены\nПроверьте протоколы на следующем шаге"
                setWizardStep(1)
            }.onFailure {
                statusContainer.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_status_error)
                status.text = friendlyError(it)
            }
        }
    }

    private fun setWizardStep(index: Int) {
        stepIndex = index.coerceIn(0, 3)
        val labels = arrayOf("Шаг 1 из 4 · Данные подключения", "Шаг 2 из 4 · Диагностика VDS", "Шаг 3 из 4 · Протоколы", "Шаг 4 из 4 · Сервер готов")
        wizardStep.text = labels[stepIndex]
        button.text = when (stepIndex) {
            0 -> "Далее: проверить сервер"
            1 -> "Далее: выбрать протоколы"
            2 -> "Развернуть и настроить"
            else -> "Протестировать подключение"
        }
    }

    private fun detectExisting(request: InstallRequest): Result<DetectedInstall> = runCatching {
        val output = runSshCommand(
            request,
            "t=0; o=0; " +
                "docker inspect terzaet-yandex >/dev/null 2>&1 && t=1 || true; " +
                "docker inspect terzaet-hysteria >/dev/null 2>&1 && t=1 || true; " +
                "[ -d /opt/terzaet ] && t=1; " +
                "[ -d /opt/terzaet-hysteria ] && t=1; " +
                "docker inspect fluxglass-yandex >/dev/null 2>&1 && o=1 || true; " +
                "[ -d /opt/fluxglass ] && o=1; " +
                "systemctl cat openflux-yandex.service >/dev/null 2>&1 && o=1 || true; " +
                "[ -x /usr/local/bin/openflux ] && o=1; " +
                "printf 'TERZAET=%s OPENFLUX=%s' \"\$t\" \"\$o\""
        )
        DetectedInstall(output.contains("TERZAET=1"), output.contains("OPENFLUX=1"))
    }

    private fun removeDetected(request: InstallRequest, terzaet: Boolean, openFlux: Boolean): Result<Unit> = runCatching {
        val commands = mutableListOf<String>()
        if (terzaet) commands += "docker rm -f terzaet-yandex terzaet-hysteria >/dev/null 2>&1 || true; systemctl disable --now terzaet-hysteria.service >/dev/null 2>&1 || true; rm -f /etc/systemd/system/terzaet-hysteria.service; systemctl daemon-reload >/dev/null 2>&1 || true; rm -rf /opt/terzaet /opt/terzaet-hysteria"
        if (openFlux) commands += "docker rm -f fluxglass-yandex >/dev/null 2>&1 || true; systemctl disable --now openflux-yandex.service >/dev/null 2>&1 || true; rm -f /etc/systemd/system/openflux-yandex.service /usr/local/bin/openflux; rm -rf /opt/fluxglass; systemctl daemon-reload >/dev/null 2>&1 || true"
        runSshCommand(request, commands.joinToString("; "))
    }

    private fun runSshCommand(request: InstallRequest, command: String): String {
        val jsch = JSch()
        val knownHosts = File(requireContext().filesDir, "ssh_known_hosts")
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val session = jsch.getSession(request.user, request.host, request.port)
        session.setPassword(request.password)
        session.userInfo = FirstUseInfo(request.password)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.connect(15_000)
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand(command)
        val output = channel.inputStream
        channel.connect(15_000)
        val response = output.bufferedReader().readText()
        while (!channel.isClosed) Thread.sleep(100L)
        val code = channel.exitStatus
        channel.disconnect()
        session.disconnect()
        if (code != 0) error("Команда VDS завершилась с кодом $code")
        return response
    }

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun runInstaller(
        request: InstallRequest,
        onProgress: (Int, String) -> Unit,
    ): Result<InstalledServer> = runCatching {
        val jsch = JSch()
        val knownHosts = File(requireContext().filesDir, "ssh_known_hosts")
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val session = jsch.getSession(request.user, request.host, request.port)
        session.setPassword(request.password)
        session.userInfo = FirstUseInfo(request.password)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.serverAliveInterval = 15_000
        session.connect(15_000)
        onProgress(8, "SSH-соединение установлено")
        val fingerprint = session.hostKey.getFingerPrint(jsch)
        val encodedUrl = Base64.encodeToString(request.document.toByteArray(), Base64.NO_WRAP)
        val encodedKey = Base64.encodeToString(request.encryptionKey.toByteArray(), Base64.NO_WRAP)
        val encodedHost = Base64.encodeToString(request.host.toByteArray(), Base64.NO_WRAP)
        val command = "export TERZAET_INSTALL_DIR=/opt/terzaet; " +
            "export TERZAET_DOC_URL=\$(printf %s '$encodedUrl' | base64 -d); " +
            "export TERZAET_ENCRYPTION_KEY=\$(printf %s '$encodedKey' | base64 -d); " +
            "export TERZAET_HY_HOST=\$(printf %s '$encodedHost' | base64 -d); " +
            "rm -f /tmp/terzaet-install.sh /tmp/terzaet-hysteria-install.sh; " +
            "curl -fsSL https://raw.githubusercontent.com/TEPZAET/TERZAET/main/server/scripts/install-terzaet.sh -o /tmp/terzaet-install.sh && " +
            "sh /tmp/terzaet-install.sh 2>&1" +
            if (request.installHysteria) {
                " && printf 'PROGRESS=93|Настраиваем Hysteria 2\\n' && " +
                    "curl -fsSL https://raw.githubusercontent.com/TEPZAET/TERZAET/feature/hysteria2-fallback/server/scripts/install-hysteria2.sh -o /tmp/terzaet-hysteria-install.sh && " +
                    "sh /tmp/terzaet-hysteria-install.sh 2>&1"
            } else ""
        val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        channel.setCommand(command)
        val errors = ByteArrayOutputStream()
        channel.setErrStream(errors)
        val output = channel.inputStream
        channel.connect(15_000)
        val text = StringBuilder()
        val pending = StringBuilder()
        val startedAt = System.currentTimeMillis()
        var lastOutputAt = startedAt
        while (!channel.isClosed || output.available() > 0) {
            val available = output.available()
            if (available > 0) {
                val bytes = ByteArray(minOf(available, 8192))
                val count = output.read(bytes)
                if (count > 0) {
                    lastOutputAt = System.currentTimeMillis()
                    pending.append(String(bytes, 0, count, Charsets.UTF_8))
                    var newline = pending.indexOf("\n")
                    while (newline >= 0) {
                        val line = pending.substring(0, newline).trimEnd('\r')
                        pending.delete(0, newline + 1)
                        text.appendLine(line)
                        Regex("^PROGRESS=(\\d{1,3})\\|(.+)$").find(line)?.let { match ->
                            onProgress(match.groupValues[1].toInt().coerceIn(0, 100), match.groupValues[2])
                        }
                        newline = pending.indexOf("\n")
                    }
                }
            } else {
                val now = System.currentTimeMillis()
                if (now - lastOutputAt > 180_000L) {
                    channel.disconnect()
                    session.disconnect()
                    error("INSTALL_STALLED")
                }
                if (now - startedAt > 1_200_000L) {
                    channel.disconnect()
                    session.disconnect()
                    error("INSTALL_TIMEOUT")
                }
                Thread.sleep(200L)
            }
        }
        if (pending.isNotEmpty()) text.appendLine(pending.toString())
        val exitCode = channel.exitStatus
        channel.disconnect()
        session.disconnect()
        val outputText = text.toString()
        if (exitCode != 0 || !outputText.lineSequence().any { it == "OK" }) {
            val detail = errors.toString().lineSequence().lastOrNull { it.isNotBlank() }
                ?: outputText.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "Сервер вернул код $exitCode"
            error(detail)
        }
        val transport = Regex("(?m)^TRANSPORT=(yandex|vyandex)$").find(outputText)?.groupValues?.get(1)
            ?: error("Не удалось определить режим документа")
        val hysteriaUri = Regex("(?m)^HY2_URI=(hysteria2://\\S+)$").find(outputText)?.groupValues?.get(1)
        if (request.installHysteria && hysteriaUri == null) error("Не удалось получить конфигурацию Hysteria 2")
        InstalledServer(transport, fingerprint, hysteriaUri)
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            "auth fail" in lower || "authentication" in lower ->
                "[SSH-702] Не удалось войти\nПроверьте логин, пароль и разрешён ли вход root по SSH."
            "install_stalled" in lower ->
                "[SRV-803] Установка перестала отвечать\nТри минуты от сервера не было данных. Проверьте интернет VDS и свободное место, затем повторите."
            "install_timeout" in lower ->
                "[SRV-804] Превышено время установки\nУстановка не завершилась за 20 минут и была остановлена."
            "timeout" in lower || "timed out" in lower ->
                "[SSH-703] Сервер не ответил вовремя\nПроверьте IP, SSH-порт и доступность VDS."
            "hostkey" in lower || "host key" in lower ->
                "[SSH-704] Ключ сервера изменился\nСоединение остановлено для защиты. Проверьте, не переустанавливали ли VDS."
            "preflight_docker" in lower ->
                "[VDS-710] Docker недоступен\nНа сервере нет Docker и поддерживаемого менеджера пакетов."
            "preflight_space" in lower ->
                "[VDS-711] Недостаточно места\nОсвободите минимум 1 ГБ на системном диске VDS."
            "preflight_https" in lower ->
                "[VDS-712] Закрыт исходящий HTTPS\nРазрешите серверу подключения через порт 443."
            "docker" in lower ->
                "[SRV-801] Docker не удалось подготовить\nСвободите место на диске и проверьте доступ VDS к интернету.\n$raw"
            "document" in lower || "yandex" in lower ->
                "[DOC-802] Документ Яндекса недоступен\nОткройте доступ по ссылке и повторите установку.\n$raw"
            "hysteria" in lower || "hy2" in lower ->
                "[HY2-805] Hysteria 2 не удалось настроить\nПроверьте доступ VDS к GitHub и откройте UDP-порт 443.\n$raw"
            else -> "[SRV-800] Установка не завершена\n$raw\nПредыдущая рабочая версия восстановлена автоматически."
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    private data class InstallRequest(
        val name: String,
        val host: String,
        val user: String,
        val port: Int,
        val password: String,
        val document: String,
        val encryptionKey: String,
        val installHysteria: Boolean,
    ) {
        fun valid() = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && port in 1..65535 &&
            password.isNotEmpty() && document.startsWith("https://")
    }

    private data class InstalledServer(val transport: String, val fingerprint: String, val hysteriaUri: String?) {
        val transportLabel: String get() = if (transport == "vyandex") "новый Яндекс Документ" else "классический Яндекс Документ"
    }

    private data class DetectedInstall(val terzaet: Boolean, val openFlux: Boolean)
    private data class Preflight(val dockerReady: Boolean, val freeGb: Long, val cpu: String, val ram: String, val os: String)

    private class FirstUseInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassword() = password
        override fun promptYesNo(message: String) = !message.contains("changed", ignoreCase = true)
        override fun getPassphrase(): String? = null
        override fun promptPassphrase(message: String?) = false
        override fun promptPassword(message: String?) = true
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { password }
    }
}
