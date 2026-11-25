package com.example.sparklog.admin

import com.example.sparklog.data.Prefs
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * Updates the SparkLog server by logging in over SSH and running the installer
 * (server/install.sh, the same one-liner as in the README). Credentials are used once and never stored.
 * The server's SSH host key is remembered on first use and checked on every later login.
 */
object ServerUpdater {
    private const val INSTALL_URL = "https://raw.githubusercontent.com/Chastises/sparklog/main/server/install.sh"

    /** Host part of the server URL, or null if it isn't set or valid. */
    fun hostOf(serverUrl: String): String? =
        try { URI(serverUrl.trim()).host?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }

    private fun sha256(key: ByteArray) =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

    /** Accepts the host key we saved earlier, and reports a first-seen key so it can be saved after a successful login. */
    private class SavedHostKey(private val saved: String) : HostKeyRepository {
        var seen: String = ""
        override fun check(host: String?, key: ByteArray?): Int {
            seen = key?.let { sha256(it) } ?: ""
            return when {
                saved.isEmpty() -> HostKeyRepository.NOT_INCLUDED
                saved == seen -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "sparklog"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /**
     * Blocking: call from a background thread. [onLine] receives the installer's output as it arrives.
     * Returns null on success, otherwise a message to show the person.
     */
    fun update(prefs: Prefs, host: String, user: String, password: String, onLine: (String) -> Unit): String? {
        val repo = SavedHostKey(prefs.sshHostKey)
        var session: Session? = null
        try {
            val jsch = JSch()
            jsch.hostKeyRepository = repo
            session = jsch.getSession(user, host, 22).apply {
                setPassword(password)
                // With a saved key we refuse any other key before the password is sent; the first time we trust and save it.
                setConfig("StrictHostKeyChecking", if (prefs.sshHostKey.isEmpty()) "no" else "yes")
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                connect(15_000)
            }
            if (prefs.sshHostKey.isEmpty() && repo.seen.isNotEmpty()) {
                prefs.sshHostKey = repo.seen
                onLine("Saved this server's SSH key (${repo.seen}). It is checked on every later update.")
            }

            val script = "command -v curl >/dev/null || { apt-get update -qq && apt-get install -y -qq curl; }; " +
                "curl -fsSL $INSTALL_URL -o /tmp/sparklog-install.sh && bash /tmp/sparklog-install.sh"
            val asRoot = user == "root"
            val command = (if (asRoot) "" else "sudo -S -p '' ") + "sh -c '$script' 2>&1"
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            if (!asRoot) channel.setInputStream(ByteArrayInputStream((password + "\n").toByteArray()))
            val out = channel.inputStream
            channel.connect(15_000)
            out.bufferedReader().forEachLine { onLine(it) }
            while (!channel.isClosed) Thread.sleep(100)
            val status = channel.exitStatus
            channel.disconnect()
            return if (status == 0) null else "The update failed (exit code $status). See the output above."
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            return when {
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> "Wrong user name or password."
                m.contains("HostKey", true) || m.contains("host key", true) ->
                    "The server's SSH key is not the one saved earlier. If you reinstalled the server, use Forget saved SSH key and try again; otherwise do not continue."
                m.contains("refused", true) || m.contains("timeout", true) || m.contains("UnknownHost", true) || m.contains("Network", true) ->
                    "Could not reach $host on port 22. Is SSH installed and running on the server?"
                else -> "SSH error: $m"
            }
        } catch (e: Exception) {
            return "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            session?.disconnect()
        }
    }
}
