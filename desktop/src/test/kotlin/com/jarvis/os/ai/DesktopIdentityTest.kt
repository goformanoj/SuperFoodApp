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

    @Before fun useTempStore() { Identity.storeFile = tmp.root.resolve("identity.properties") }
    @After fun reset() { Identity.signOut(); Identity.storeFile = null }

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
}
