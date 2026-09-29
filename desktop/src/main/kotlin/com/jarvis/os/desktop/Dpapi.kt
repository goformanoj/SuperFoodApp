package com.jarvis.os.desktop

import com.sun.jna.platform.win32.Crypt32Util

/**
 * Seals secrets at rest: bytes only THIS Windows user, on THIS machine, can read back — the
 * OS ties the key to the login, not to anything JARVIS stores itself, so copying the file
 * to another account or machine yields nothing. Shared by everything on the laptop that
 * keeps a real credential (the Google refresh token, this laptop's own signed-in identity
 * once it's linked to an account) rather than each writing its own copy of the same code.
 *
 * [Vault] is the seam tests use: swap in a fake so a test never touches the real Windows
 * API (DPAPI does not exist off Windows, and CI runs `:desktop:test` on Linux).
 */
interface Vault {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

object Dpapi : Vault {
    override fun seal(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
    override fun open(sealed: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(sealed)
}
