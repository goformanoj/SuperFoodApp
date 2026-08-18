package androidx.core.content

import android.content.Context
import android.content.pm.PackageManager

object ContextCompat {
    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int =
        PackageManager.PERMISSION_DENIED
}
