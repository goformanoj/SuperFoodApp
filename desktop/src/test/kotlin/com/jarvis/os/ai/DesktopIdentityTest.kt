package com.jarvis.os.ai

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopIdentityTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A stand-in for DPAPI — real Windows-only encryption has no place in a JVM unit test
     *  (and CI runs this module on Linux), so this "seals" by reversing the bytes: enough
     *  to prove load/save round-trips through SOME transform without touching the OS API. */
    private val fakeVault = object : com.jarvis.os.desktop.Vault {
        override fun seal(plain: ByteArray) = plain.reversedArray()
        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }

    @Before fun useTempStore() {
        Identity.storeFile = tmp.root.resolve("identity.properties")
        Identity.vault = fakeVault
    }
    @After fun reset() { Identity.signOut(); Identity.storeFile = null; Identity.vault = com.jarvis.os.desktop.Dpapi }

    @Test
    fun idpPayloadLinksOntoTheAnonymousUidWhenThereIsOne() {
        val o = JSONObject(Identity.buildIdpPayload("gTok", "anonTok"))
        assertEquals("id_token=gTok&providerId=google.com", o.getString("postBody"))
        assertEquals("anonTok", o.getString("idToken"))
        assertTrue(o.getBoolean("returnSecureToken"))
    }

    @Test
    fun idpPayloadWithoutAnonIsAPlainSignIn() {
        assertFalse(JSONObject(Identity.buildIdpPayload("gTok", null)).has("idToken"))
    }

    @Test
    fun parsesTheIdpResponseIncludingTheDisplayName() {
        val r = Identity.parseIdp("""{"idToken":"i","refreshToken":"r","expiresIn":"3600","email":"a@b.com","displayName":"Pranjal"}""")
        assertEquals("i", r.idToken)
        assertEquals("a@b.com", r.email)
        assertEquals("Pranjal", r.name)
    }

    @Test
    fun fallsBackToFullNameAndToNoName() {
        assertEquals("P K", Identity.parseIdp("""{"idToken":"i","refreshToken":"r","fullName":"P K"}""").name)
        assertNull(Identity.parseIdp("""{"idToken":"i","refreshToken":"r"}""").name)
    }

    @Test
    fun linkConflictsAreRecognised() {
        assertTrue(Identity.isLinkConflict(400, """{"error":{"message":"FEDERATED_USER_ID_ALREADY_LINKED"}}"""))
        assertTrue(Identity.isLinkConflict(400, """{"error":{"message":"EMAIL_EXISTS"}}"""))
        assertFalse(Identity.isLinkConflict(400, """{"error":{"message":"INVALID_IDP_RESPONSE"}}"""))
        assertFalse(Identity.isLinkConflict(200, "{}"))
    }

    @Test
    fun aFreshLaptopIsAGuestOnTheFreePlan() {
        val a = Identity.account()
        assertFalse(a.isSignedIn)
        assertEquals("Guest", a.label())
        assertEquals('G', a.initial)
        assertEquals("free", a.plan)
    }

    @Test
    fun theCachedPlanShowsOnTheAccountAndSignOutClearsIt() {
        Identity.cachePlan("pro")
        assertTrue(Identity.account().isPro)
        Identity.signOut()
        assertFalse(Identity.account().isPro)
    }

    // ── the identity file is sealed at rest, not left in plain text ──

    @Test
    fun whatsOnDiskIsNeverThePlainPropertiesText() {
        Identity.cachePlan("pro")
        val onDisk = Identity.storeFile!!.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("account_plan"))   // the property KEY, in the clear, would be this
        assertFalse(onDisk.contains("pro"))
    }

    @Test
    fun anExistingPlainTextFileFromBeforeThisStillLoadsAndIsResealed() {
        val f = Identity.storeFile!!
        val plain = java.util.Properties().apply { setProperty("account_plan", "pro") }
        f.outputStream().use { plain.store(it, "old-style plain text, as every laptop before this had") }
        assertTrue(Identity.account().isPro)                 // read back correctly despite not being sealed
        val resealedBytes = f.readBytes()
        assertFalse(resealedBytes.toString(Charsets.ISO_8859_1).contains("account_plan")) // and it's protected now
        assertTrue(Identity.account().isPro)                  // still readable after the reseal
    }

    @Test
    fun aCorruptOrUnreadableFileIsAFreshGuestNotACrash() {
        Identity.storeFile!!.writeBytes(byteArrayOf(1, 2, 3))
        val a = Identity.account()
        assertFalse(a.isSignedIn)
        assertEquals("free", a.plan)
    }

    @Test
    fun anUnrecognisedEntryIsDroppedRatherThanCarriedForwardForever() {
        // Reproduces a real bug found while building this: a file that (however it got
        // there) holds something other than this code's own known keys must not keep
        // re-saving that unknown entry on every future write, growing forever.
        val f = Identity.storeFile!!
        val withGarbage = java.util.Properties().apply {
            setProperty("refresh_token", "REAL_TOKEN")
            setProperty("some_unexpected_entry", "should not survive")
        }
        f.outputStream().use { withGarbage.store(it, "legacy, with something this code doesn't recognise") }
        Identity.cachePlan("free")   // any save
        val onDisk = f.readBytes()
        val reread = java.util.Properties().apply {
            fakeVault.open(onDisk.copyOfRange(1, onDisk.size)).inputStream().use { load(it) }
        }
        assertEquals(setOf("refresh_token", "account_plan"), reread.stringPropertyNames())
    }

    @Test
    fun aWriteThatCannotBeVerifiedNeverTouchesTheRealFile() {
        // A vault whose seal/open don't actually invert each other — writeSealed must
        // notice the mismatch on its own verification read and refuse to commit, rather
        // than trust a seal it cannot prove is readable.
        Identity.vault = object : com.jarvis.os.desktop.Vault {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray) = "wrong bytes entirely".toByteArray()
        }
        Identity.cachePlan("pro")
        assertFalse(Identity.storeFile!!.exists())   // nothing was ever written — not even a bad file
    }
}
