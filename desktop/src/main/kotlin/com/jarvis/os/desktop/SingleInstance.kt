package com.jarvis.os.desktop

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * One JARVIS at a time. Closing the window only hides it to the tray, so launching the app again
 * used to start a second (then third) copy — each loading the wake-word model, grabbing the
 * microphone and fighting over the Quick bar hotkey (found 2026-10-02: three copies, ~750 MB).
 *
 * The first copy takes an OS file lock in [dir] and listens on a loopback-only port (written next to
 * the lock). A later launch finds the lock taken, tells the first copy "show yourself" over that
 * port, and exits. The lock is the OS's own, so it vanishes the instant a copy dies — a crash can
 * never leave a stale lock that blocks the next start.
 *
 * Pure of any UI: [claim] and [signal] are tested against a temp folder (SingleInstanceTest).
 */
class SingleInstance(private val dir: File) : AutoCloseable {

    /** What a later launch asks of the running copy. */
    enum class Handoff {
        /** Bring the window up (a normal launch). */
        SHOW,
        /** Nothing visible — a `--background` launch (Start with Windows) while JARVIS is already running. */
        QUIET;

        companion object {
            /** Pure; tested. Anything else (junk, nothing) is ignored rather than guessed at. */
            fun parse(line: String?): Handoff? = when (line?.trim()?.lowercase()) {
                "show" -> SHOW
                "quiet" -> QUIET
                else -> null
            }
        }
    }

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True if this process is now THE JARVIS; [onShow] runs (on a helper thread) whenever a later launch asks. */
    fun claim(onShow: () -> Unit): Boolean {
        dir.mkdirs()
        val ch = FileChannel.open(File(dir, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val taken = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null   // already held by another claim in this very process
        } catch (_: IOException) {
            null
        }
        if (taken == null) { ch.close(); return false }
        channel = ch
        lock = taken
        // A port file left by a copy that died must not be mistaken for this one's.
        File(dir, PORT).delete()
        val s = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = s
        File(dir, PORT).writeText(s.localPort.toString())
        thread(isDaemon = true, name = "jarvis-single-instance") { listen(s, onShow) }
        return true
    }

    private fun listen(s: ServerSocket, onShow: () -> Unit) {
        while (!s.isClosed) {
            try {
                s.accept().use { c ->
                    c.soTimeout = 2000
                    val request = Handoff.parse(c.getInputStream().bufferedReader().readLine())
                    if (request == Handoff.SHOW) runCatching(onShow)
                }
            } catch (_: IOException) {
                if (s.isClosed) return
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        runCatching { File(dir, PORT).delete() }
        server = null; lock = null; channel = null
    }

    companion object {
        private const val LOCK = "instance.lock"
        private const val PORT = "instance.port"

        /**
         * Asks the running copy (see [claim]) to [show] itself or stay quiet. The copy may still be starting
         * up and not have published its port yet, so this retries briefly. Returns whether anyone answered.
         */
        fun signal(dir: File, show: Boolean, attempts: Int = 15, waitMs: Long = 200): Boolean {
            repeat(attempts) {
                try {
                    val port = File(dir, PORT).readText().trim().toInt()
                    Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                        s.getOutputStream().write(((if (show) "show" else "quiet") + "\n").toByteArray())
                        s.getOutputStream().flush()
                    }
                    return true
                } catch (_: Exception) {
                    Thread.sleep(waitMs)
                }
            }
            return false
        }
    }
}
