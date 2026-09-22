package io.github.p1neapplexpress.openflux.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.UIKeyboardInteractive
import io.github.p1neapplexpress.openflux.util.Loopback
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException

@Serializable
data class ManagedUser(
    val id: String,
    val alias: String,
    val trafficCap: Long = 0,
    val timeCap: Long = 0,
    val createdAt: String = "",
    val revokedAt: String? = null,
    val bundle: String = "",
)

@Serializable
data class ManagedUserRequest(
    val alias: String,
    val trafficCap: Long = 0,
    val timeCap: Long = 0,
    val profiles: List<ManagedProfile> = emptyList(),
)

@Serializable
data class ManagedProfile(val name: String, val uri: String)

class ControlApiClient(
    host: String,
    user: String,
    sshPort: Int,
    password: String,
    knownHostsPath: String,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val session: Session
    private val localPort: Int
    private val token: String

    init {
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsPath)
        val activeSession = jsch.getSession(user, host, sshPort)
        session = activeSession
        try {
            activeSession.setPassword(password)
            activeSession.userInfo = PasswordInfo(password)
            activeSession.setConfig("StrictHostKeyChecking", "ask")
            activeSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            activeSession.serverAliveInterval = 15_000
            activeSession.connect(15_000)
            localPort = Loopback.freeTcpPort()
            activeSession.setPortForwardingL(localPort, "127.0.0.1", 8787)
            token = exec("cat /opt/terzaet/control/admin.token").trim()
            require(token.length >= 24) { "Control API token is unavailable" }
            try {
                request("GET", "/v1/health", null)
            } catch (error: ConnectException) {
                repairControlListener()
                waitForHealth()
            }
        } catch (error: Throwable) {
            activeSession.disconnect()
            throw error
        }
    }

    fun listUsers(): List<ManagedUser> = request("GET", "/v1/users", null).let {
        json.decodeFromString<List<ManagedUser>?>(it).orEmpty()
    }

    fun createUser(value: ManagedUserRequest): ManagedUser = request("POST", "/v1/users", json.encodeToString(value)).let { json.decodeFromString(it) }

    fun revokeUser(id: String) { request("POST", "/v1/users/$id/revoke", null) }

    fun deleteUser(id: String) { request("DELETE", "/v1/users/$id", null) }

    override fun close() { runCatching { session.disconnect() } }

    private fun request(method: String, path: String, body: String?): String {
        val connection = URL("http://127.0.0.1:$localPort$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) error("Control API ${connection.responseCode}: $response")
        return response
    }

    private fun exec(command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val output = channel.inputStream
        channel.connect(8_000)
        val result = output.bufferedReader().readText()
        while (!channel.isClosed) Thread.sleep(25)
        val code = channel.exitStatus
        channel.disconnect()
        if (code != 0) error("Remote command failed: $code")
        return result
    }

    private fun repairControlListener() {
        val command = "set -eu; name=terzaet-control; " +
            "[ \"\$(docker inspect -f '{{ index .Config.Labels \"app.terzaet.managed\" }}' \"\$name\" 2>/dev/null)\" = true ]; " +
            "[ \"\$(docker inspect -f '{{.Config.Image}}' \"\$name\")\" = terzaet-yandex:local ]; " +
            "mount=\$(docker inspect -f '{{range .Mounts}}{{if eq .Destination \"/opt/terzaet-control\"}}{{.Source}}{{end}}{{end}}' \"\$name\"); " +
            "[ \"\$mount\" = /opt/terzaet/control ] && [ -s /opt/terzaet/control/admin.token ] && [ -s /opt/terzaet/control/signing.key ]; " +
            "docker rm -f \"\$name\" >/dev/null; " +
            "docker run -d --name \"\$name\" --restart unless-stopped --label app.terzaet.managed=true " +
            "-p 127.0.0.1:8787:8787 -v /opt/terzaet/control:/opt/terzaet-control " +
            "-e ROLE=control -e CONTROL_LISTEN=0.0.0.0:8787 " +
            "-e CONTROL_DATA=/opt/terzaet-control/users.json -e CONTROL_SECRET=/opt/terzaet-control/signing.key " +
            "-e CONTROL_TOKEN_FILE=/opt/terzaet-control/admin.token terzaet-yandex:local >/dev/null"
        try {
            exec(command)
        } catch (error: Exception) {
            throw IllegalStateException("Сервис управления не отвечает; безопасное восстановление не выполнено. Проверьте контейнер TERZAET на VDS.", error)
        }
    }

    private fun waitForHealth() {
        var lastError: Exception? = null
        repeat(12) {
            try {
                request("GET", "/v1/health", null)
                return
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(400)
            }
        }
        throw IllegalStateException("Сервис управления TERZAET не запустился после восстановления", lastError)
    }

    private class PasswordInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassword() = password
        override fun promptYesNo(message: String) = !message.contains("changed", ignoreCase = true)
        override fun getPassphrase(): String? = null
        override fun promptPassphrase(message: String?) = false
        override fun promptPassword(message: String?) = true
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ) = Array(prompt?.size ?: 0) { password }
    }
}
