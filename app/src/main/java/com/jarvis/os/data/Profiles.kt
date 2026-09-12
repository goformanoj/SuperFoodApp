package com.jarvis.os.data

/**
 * How on-device data is partitioned per account.
 *
 * Chats, memory, custom instructions and files "stay on this phone" — but the
 * phone can be shared, and a signed-in user's data must not be visible to a guest
 * (or to a different account). So every per-account store keys its storage name by
 * a **profile id**: a filename-safe token derived from the signed-in email, or
 * `guest` when nobody is signed in. Switching accounts switches the whole set of
 * namespaces at once, so the guest sees only guest data and each account sees only
 * its own.
 *
 * The id derivation and name-scoping are pure, so they are pinned off-device.
 */
object Profiles {

    const val GUEST = "guest"

    /**
     * The data-partition id for an account. `guest` when signed out or e-mail-less;
     * otherwise `u_` + the email reduced to lowercase letters and digits (so it is a
     * safe SharedPreferences / directory name and stable for the same address).
     */
    fun idFor(email: String?, signedIn: Boolean): String {
        if (!signedIn || email.isNullOrBlank()) return GUEST
        val safe = email.lowercase().filter { it.isLetterOrDigit() }
        return if (safe.isEmpty()) GUEST else "u_$safe"
    }

    /** A per-profile storage name: `<base>__<id>` (e.g. `jarvis_chat__u_a…`). */
    fun scoped(base: String, id: String): String = "${base}__$id"
}
