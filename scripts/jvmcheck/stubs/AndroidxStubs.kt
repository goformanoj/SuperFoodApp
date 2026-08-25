package androidx.core.content

import android.content.Context
import android.content.pm.PackageManager

object ContextCompat {
    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int =
        PackageManager.PERMISSION_DENIED
}

/**
 * Enough of `FileProvider` to type-check `AssistantEngine.openArtifact`.
 *
 * It shares a file JARVIS wrote out to another app — a PDF viewer — without
 * needing a storage permission. The real one is in `androidx.core`, which lives
 * on `dl.google.com` and so cannot be fetched here.
 *
 * Never executed: the tests that run in this harness are pure Kotlin, so a stub
 * only has to have the right SHAPE.
 */
object FileProvider {
    @JvmStatic
    fun getUriForFile(context: Context, authority: String, file: java.io.File): android.net.Uri =
        android.net.Uri.EMPTY
}
