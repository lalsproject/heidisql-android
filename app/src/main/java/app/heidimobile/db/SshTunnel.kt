package app.heidimobile.db

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session as JschSession

/**
 * Replacement for HeidiSQL's TSecureShellCmd, which shells out to plink.exe
 * ("plink user@host -pw ... -P 22 -i key -L 55555:localhost:3306"). Android cannot run plink,
 * so the same local port forward is done in-process with JSch.
 *
 * Host keys: trust on first use. The fingerprint seen on the first successful connect is stored in
 * the session; a different fingerprint afterwards aborts the connection.
 */
class SshTunnel(private val s: Session) : AutoCloseable {
    private var ses: JschSession? = null

    /** Fingerprint of the server's host key after [open] succeeded. */
    var hostKeyFingerprint: String = ""
        private set

    /** Opens the tunnel and returns the local port that forwards to [targetHost]:[targetPort]. */
    fun open(targetHost: String, targetPort: Int): Int {
        val jsch = JSch()
        if (s.sshKey.isNotBlank()) {
            val pass = s.sshPassword.takeIf { it.isNotEmpty() }?.toByteArray()
            jsch.addIdentity("heidimobile-key", s.sshKey.toByteArray(), null, pass)
        }
        val session = jsch.getSession(s.sshUser, s.sshHost, s.sshPort)
        if (s.sshKey.isBlank() && s.sshPassword.isNotEmpty()) session.setPassword(s.sshPassword)
        session.setConfig("StrictHostKeyChecking", "no") // pinned manually below
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.setServerAliveInterval(30_000)
        try {
            session.connect(15_000)
            val fp = session.hostKey.getFingerPrint(jsch)
            if (s.sshHostKeyFp.isNotEmpty() && s.sshHostKeyFp != fp) {
                throw IllegalStateException(
                    "SSH host key changed (expected ${s.sshHostKeyFp}, got $fp). Possible man-in-the-middle attack."
                )
            }
            hostKeyFingerprint = fp
            val local = session.setPortForwardingL("127.0.0.1", 0, targetHost, targetPort)
            ses = session
            return local
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }

    override fun close() {
        try { ses?.disconnect() } catch (_: Exception) {}
        ses = null
    }
}
