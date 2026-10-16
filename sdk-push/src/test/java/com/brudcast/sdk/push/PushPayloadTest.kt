package com.brudcast.sdk.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
    private val data = mapOf(
        "bc_mid" to "pm_1",
        "bc_cid" to "c_1",
        "bc_app" to "app_1",
        "click_url" to "myapp://sale",
        "google.message_id" to "0:1",
        "coupon" to "SAVE20",
    )

    @Test
    fun readsABrudcastNotification() {
        val notification = BrudcastPushNotification.from(data, "Sale", "20% off", "https://cdn.example.com/sale.png")!!
        assertEquals("pm_1", notification.messageId)
        assertEquals("c_1", notification.campaignId)
        assertEquals("app_1", notification.applicationId)
        assertEquals("myapp://sale", notification.clickUrl)
        assertEquals("Sale", notification.title)
        assertEquals("https://cdn.example.com/sale.png", notification.imageUrl)
        assertEquals(mapOf("coupon" to "SAVE20"), notification.data)
    }

    @Test
    fun leavesOtherNotificationsToTheHost() {
        assertNull(BrudcastPushNotification.from(mapOf("google.message_id" to "1")))
        assertFalse(PushModule.isBrudcastNotification(mapOf("title" to "Hi")))
        assertTrue(PushModule.isBrudcastNotification(data))
    }
}
