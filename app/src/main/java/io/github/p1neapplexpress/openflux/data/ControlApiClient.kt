package io.github.p1neapplexpress.openflux.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.p1neapplexpress.openflux.util.Loopback
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

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
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val session: Session
    private val localPort: Int
    private val token: String

    init {
        val jsch = JSch()
        session = jsch.getSession(user, host, sshPort)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.serverAliveInterval = 15_000
        session.connect(15_000)
        localPort = Loopback.freeTcpPort()
        session.setPortForwardingL(localPort, "127.0.0.1", 8787)
        token = exec("cat /opt/terzaet/control/admin.token").trim()
        require(token.length >= 24) { "Control API token is unavailable" }
    }

    fun listUsers(): List<ManagedUser> = request("GET", "/v1/users", null).let { json.decodeFromString(it) }

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
}
