package com.jarvis.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json on the phone's JVM tests needs Robolectric (same as ProxyClientTest). The same cases run in the desktop module. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageClientTest {

    @Test
    fun aWellFormedAnswerIsParsed() {
        assertEquals(UsageClient.Snapshot("pro", 2_000_000, 1_997_191), UsageClient.parse("""{"plan":"pro","cap":2000000,"remaining":1997191}"""))
        assertEquals(UsageClient.Snapshot("free", 60_000, 743), UsageClient.parse("""{"plan":"free","cap":60000,"remaining":743}"""))
    }

    @Test
    fun anythingElseIsNullSoAFailedCallNeverOverwritesGoodState() {
        assertNull(UsageClient.parse(""))
        assertNull(UsageClient.parse("not json"))
        assertNull(UsageClient.parse("""{"error":"forbidden"}"""))
        assertNull(UsageClient.parse("""{"plan":"pro"}"""))                                  // no numbers
        assertNull(UsageClient.parse("""{"plan":"","cap":1,"remaining":1}"""))               // blank plan
        assertNull(UsageClient.parse("""{"plan":"pro","cap":"many","remaining":1}"""))       // wrong type
    }
}
