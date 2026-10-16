package com.brudcast.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun parsesASessionResponse() {
        val payload = JSONObject(
            """{"session":{"token":"jwt","expiresAt":"2030-01-01T00:15:00.000Z"},
               "user":{"id":"u1","externalId":"4812","verified":true,"optedOut":false},
               "messages":[],"frequencyCap":null,"serverTime":"${BrudcastDates.format(System.currentTimeMillis())}"}""",
        )
        val session = BrudcastCore.parseSession(payload)!!
        assertEquals("jwt", session.token)
        assertEquals("u1", session.userId)
        assertEquals("4812", session.externalId)
        assertTrue(session.verified)
        assertTrue(kotlin.math.abs(session.serverTimeOffset) < 1_000)
    }

    @Test
    fun refusesAnIncompleteSession() {
        assertNull(BrudcastCore.parseSession(JSONObject("""{"user":{"id":"u1"}}""")))
    }

    @Test
    fun unwrapsTheEnvelopeAndReadsErrors() {
        val ok = BrudcastApiClient.decode(202, """{"status":true,"data":{"accepted":2}}""")
        assertEquals(2, (ok as BrudcastResult.Success).data.getInt("accepted"))
        val failed = BrudcastApiClient.decode(409, """{"status":false,"message":"Push is not linked","code":"PUSH_NOT_LINKED"}""")
        val error = (failed as BrudcastResult.Failure).error
        assertEquals(409, error.statusCode)
        assertEquals("PUSH_NOT_LINKED", error.code)
        assertFalse(error.isRetryable)
        assertTrue(BrudcastApiError(503, null, "").isRetryable)
        assertTrue(BrudcastApiError(null, null, "").isRetryable)
    }

    @Test
    fun validatesEventNamesLikeTheService() {
        assertTrue(Brudcast.isValidEventName("checkout_started"))
        assertTrue(Brudcast.isValidEventName("cart:item.added-v2"))
        assertFalse(Brudcast.isValidEventName("1st_open"))
        assertFalse(Brudcast.isValidEventName("a".repeat(65)))
    }

    @Test
    fun convertsHostValuesAndDropsCollections() {
        val properties = BrudcastValue.properties(mapOf("s" to "x", "i" to 3, "b" to true, "n" to null, "list" to listOf(1)))
        assertEquals(BrudcastValue.Text("x"), properties["s"])
        assertEquals(BrudcastValue.Number(3.0), properties["i"])
        assertEquals(BrudcastValue.Bool(true), properties["b"])
        assertEquals(BrudcastValue.Null, properties["n"])
        assertFalse(properties.containsKey("list"))
    }

    @Test
    fun parsesDatesWithAndWithoutMilliseconds() {
        assertEquals(1_800_000_000_000L, BrudcastDates.parse("2027-01-15T08:00:00.000Z"))
        assertEquals(1_800_000_000_000L, BrudcastDates.parse("2027-01-15T08:00:00Z"))
        assertNull(BrudcastDates.parse("yesterday"))
    }

    @Test
    fun localEnvironmentTargetsTheEmulatorHost() {
        assertEquals("http://10.0.2.2:8191/sdk/v1", BrudcastEnvironment.Local().baseUrl)
    }

    @Test
    fun tellsARefusedKeyFromARefusedUserHash() {
        val hashRefused = BrudcastApiError(401, null, "The user hash does not match this user")
        val keyRefused = BrudcastApiError(401, null, "Unknown or disabled publishable key")
        assertFalse(BrudcastCore.isKeyRejection(hashRefused, hadIdentity = true))
        assertTrue(BrudcastCore.isKeyRejection(keyRefused, hadIdentity = true))
        assertTrue(BrudcastCore.isKeyRejection(hashRefused, hadIdentity = false))
        assertFalse(BrudcastCore.isKeyRejection(BrudcastApiError(401, "identity_rejected", "publishable key"), hadIdentity = true))
        assertTrue(BrudcastCore.isKeyRejection(BrudcastApiError(401, "key_rejected", ""), hadIdentity = true))
    }

    @Test
    fun aRequestTheClientCouldNotBuildIsNotRetried() {
        assertFalse(BrudcastApiError(null, "client", "bad url").isRetryable)
        assertTrue(BrudcastApiError(null, null, "timeout").isRetryable)
    }
}
