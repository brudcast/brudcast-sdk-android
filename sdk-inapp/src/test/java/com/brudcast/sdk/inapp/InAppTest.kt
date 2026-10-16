package com.brudcast.sdk.inapp

import com.brudcast.sdk.BrudcastValue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private object Fixtures {
    fun design(format: String = "modal", schemaVersion: Int = 1, blocks: String? = null): JSONObject = JSONObject(
        """{"schemaVersion":$schemaVersion,"format":"$format",
            "layout":{"position":"center","dismissible":true,"overlay":{"color":"#000000","opacity":0.5},
              "background":{"color":"#FFFFFF","imageUrl":null},"cornerRadius":16,"padding":24,
              "darkMode":{"backgroundColor":"#111111"},"autoDismissSeconds":null,"bannerAction":null},
            "blocks":${blocks ?: """[
              {"id":"img","type":"image","url":"https://cdn.example.com/a.png","aspectRatio":1.6,"alt":"A gift","cornerRadius":null},
              {"id":"t","type":"text","role":"title","text":"Hi Ada","style":{"size":null,"weight":"bold","align":"center","color":null,"darkColor":null}},
              {"id":"cta","type":"button","label":"Upgrade","action":{"type":"deeplink","value":"myapp://upgrade","target":null},"style":{"variant":"primary"}},
              {"id":"later","type":"button","label":"Not now","action":{"type":"dismiss","value":null,"target":null},"style":{"variant":"text"}}
            ]"""}}""",
    )

    fun message(
        id: String = "m1",
        format: String = "modal",
        design: JSONObject = design(format),
        trigger: String = """{"type":"on_session"}""",
        maxImpressions: Int = 1,
        impressionCount: Int = 0,
        startsAt: String? = null,
        endsAt: String? = null,
        test: Boolean = false,
    ): JSONObject = JSONObject()
        .put("id", id).put("kind", "campaign").put("campaignId", "c1").put("test", test).put("format", format)
        .put("design", design).put("trigger", JSONObject(trigger))
        .put("displayRules", JSONObject().put("maxImpressions", maxImpressions).put("delaySeconds", 0).put("priority", "normal"))
        .put("inbox", JSONObject().put("addToInbox", false).put("title", "").put("preview", "").put("expiresInDays", 30))
        .put("display", format != "inbox_only").put("inboxEntry", false).put("read", false)
        .put("impressionCount", impressionCount).put("createdAt", "2026-10-16T10:00:00.000Z")
        .put("startsAt", startsAt ?: JSONObject.NULL).put("endsAt", endsAt ?: JSONObject.NULL)

    fun feed(vararg messages: JSONObject): JSONArray = JSONArray().apply { messages.forEach { put(it) } }
}

class InAppDesignTest {
    @Test
    fun parsesEveryBlockOfAModal() {
        val design = InAppDesign.parse(Fixtures.design())
        assertEquals(InAppFormat.MODAL, design.format)
        assertEquals(4, design.blocks.size)
        assertEquals("Hi Ada", design.title)
        assertEquals(listOf("https://cdn.example.com/a.png"), design.imageUrls)
        assertEquals(InAppAction(InAppActionType.DEEPLINK, "myapp://upgrade", false), (design.blocks[2] as InAppBlock.Button).action)
        assertEquals("#111111", design.layout.darkBackgroundColor)
    }

    @Test(expected = InAppDesignError.UnsupportedVersion::class)
    fun refusesANewerSchemaVersion() {
        InAppDesign.parse(Fixtures.design(schemaVersion = 2))
    }

    @Test(expected = InAppDesignError.UnknownBlock::class)
    fun refusesAnUnknownBlockRatherThanDrawingPartOfIt() {
        InAppDesign.parse(Fixtures.design(blocks = """[{"id":"x","type":"video","url":"https://x"}]"""))
    }

    @Test(expected = InAppDesignError.InvalidBlock::class)
    fun refusesAButtonWithoutAUsableAction() {
        InAppDesign.parse(Fixtures.design(blocks = """[{"id":"b","type":"button","label":"Go","action":{"type":"url","value":""}}]"""))
    }

    @Test
    fun urlActionsOpenInAppUnlessTheBrowserIsAsked() {
        assertTrue(InAppAction.parse(JSONObject("""{"type":"url","value":"https://a.co","target":"in_app"}"""))!!.opensInApp)
        assertFalse(InAppAction.parse(JSONObject("""{"type":"url","value":"https://a.co","target":"browser"}"""))!!.opensInApp)
    }

    @Test
    fun bannersNeverSitInTheCentreAndValuesAreClamped() {
        val banner = InAppDesign.parse(Fixtures.design("banner", blocks = """[{"id":"t","type":"text","role":"title","text":"Sale"}]"""))
        assertEquals(InAppPosition.TOP, banner.layout.position)
        val clamped = InAppDesign.parse(Fixtures.design(blocks = """[{"id":"s","type":"spacer","height":900},{"id":"i","type":"image","url":"https://a.co/i.png","aspectRatio":99}]"""))
        assertEquals(InAppBlock.Spacer("s", 120.0), clamped.blocks[0])
        assertEquals(4.0, (clamped.blocks[1] as InAppBlock.Image).aspectRatio, 0.0)
    }

    @Test
    fun anUnsupportedDesignStillYieldsAMessageToReport() {
        val message = InAppMessage.parse(Fixtures.message(design = Fixtures.design(schemaVersion = 3)))!!
        assertNull(message.design)
        assertEquals(InAppDesignState.Unsupported(InAppDesignError.UnsupportedVersion(3)), message.designState)
    }

    @Test
    fun anUnreadableTriggerStillYieldsAMessageThatNeverFires() {
        val message = InAppMessage.parse(Fixtures.message(trigger = """{"type":"app_open"}"""))!!
        assertTrue(message.triggerUnreadable)
        assertFalse(TriggerMatcher.matches(message.trigger, InAppOccurrence.Session))
        assertFalse(TriggerMatcher.matches(message.trigger, InAppOccurrence.Event("app_open", emptyMap())))
    }

    @Test
    fun keepsTrailingButtonsTogether() {
        val (content, buttons) = splitTrailingButtons(InAppDesign.parse(Fixtures.design()).blocks)
        assertEquals(listOf("img", "t"), content.map { it.id })
        assertEquals(listOf("cta", "later"), buttons.map { it.id })
    }
}

class TriggerMatcherTest {
    private fun trigger(filters: String) =
        InAppTrigger.parse(JSONObject("""{"type":"event","eventName":"checkout_started","filters":$filters}"""))!!

    private fun event(properties: Map<String, BrudcastValue>) = InAppOccurrence.Event("checkout_started", properties)

    @Test
    fun matchesTheTypeAndName() {
        val event = trigger("[]")
        assertTrue(TriggerMatcher.matches(event, event(emptyMap())))
        assertFalse(TriggerMatcher.matches(event, InAppOccurrence.Event("checkout_done", emptyMap())))
        assertFalse(TriggerMatcher.matches(event, InAppOccurrence.Screen("checkout_started", emptyMap())))
        assertFalse(TriggerMatcher.matches(event, InAppOccurrence.Session))
        assertTrue(TriggerMatcher.matches(InAppTrigger.ON_SESSION, InAppOccurrence.Session))
    }

    @Test
    fun appliesEveryOperator() {
        val props = mapOf("plan" to BrudcastValue.Text("pro"), "total" to BrudcastValue.Number(120.0), "first" to BrudcastValue.Bool(true))
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"plan","operator":"eq","value":"pro"}]"""), event(props)))
        assertFalse(TriggerMatcher.matches(trigger("""[{"property":"plan","operator":"neq","value":"pro"}]"""), event(props)))
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"total","operator":"gt","value":100}]"""), event(props)))
        assertFalse(TriggerMatcher.matches(trigger("""[{"property":"total","operator":"lt","value":100}]"""), event(props)))
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"first","operator":"exists","value":null}]"""), event(props)))
        assertFalse(TriggerMatcher.matches(trigger("""[{"property":"coupon","operator":"exists","value":null}]"""), event(props)))
    }

    @Test
    fun comparesNumbersGivenAsStringsAndMissingProperties() {
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"total","operator":"eq","value":120}]"""), event(mapOf("total" to BrudcastValue.Text("120")))))
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"first","operator":"eq","value":"true"}]"""), event(mapOf("first" to BrudcastValue.Bool(true)))))
        assertTrue(TriggerMatcher.matches(trigger("""[{"property":"plan","operator":"neq","value":"pro"}]"""), event(emptyMap())))
        assertFalse(TriggerMatcher.matches(trigger("""[{"property":"plan","operator":"eq","value":"pro"}]"""), event(emptyMap())))
    }

    @Test
    fun refusesAnUnknownOperator() {
        assertNull(InAppTrigger.parse(JSONObject("""{"type":"event","eventName":"x","filters":[{"property":"a","operator":"contains","value":"b"}]}""")))
    }
}

class MessageStoreTest {
    private var clock = 1_800_000_000_000L
    private fun store() = MessageStore(null) { clock }

    @Test
    fun reportsAddedAndWithdrawnMessages() {
        val store = store()
        store.replace(Fixtures.feed(Fixtures.message("a"), Fixtures.message("b")), null, "u1")
        val change = store.replace(Fixtures.feed(Fixtures.message("b"), Fixtures.message("c")), null, "u1")
        assertEquals(listOf("c"), change.added.map { it.id })
        assertEquals(listOf("a"), change.removed)
    }

    @Test
    fun forgetsEverythingWhenTheUserChangesAndDeliversOnce() {
        val store = store()
        store.replace(Fixtures.feed(Fixtures.message("a")), null, "u1")
        assertEquals(listOf("a"), store.markDelivered(listOf("a")))
        assertEquals(emptyList<String>(), store.markDelivered(listOf("a")))
        store.replace(Fixtures.feed(Fixtures.message("a")), null, "u2")
        assertEquals(listOf("a"), store.markDelivered(listOf("a")))
    }

    @Test
    fun stopsAtMaxImpressionsCountingLocalOnes() {
        val store = store()
        store.replace(Fixtures.feed(Fixtures.message("a", maxImpressions = 2, impressionCount = 1)), null, "u1")
        assertTrue(store.isEligible(store.message("a")!!, clock))
        store.recordImpression("a")
        assertFalse(store.isEligible(store.message("a")!!, clock))
    }

    @Test
    fun respectsTheLiveWindow() {
        val store = store()
        store.replace(
            Fixtures.feed(
                Fixtures.message("future", startsAt = "2030-01-01T00:00:00.000Z"),
                Fixtures.message("past", endsAt = "2020-01-01T00:00:00.000Z"),
                Fixtures.message("live", startsAt = "2020-01-01T00:00:00.000Z", endsAt = "2030-01-01T00:00:00.000Z"),
            ),
            null, "u1",
        )
        assertFalse(store.isEligible(store.message("future")!!, clock))
        assertFalse(store.isEligible(store.message("past")!!, clock))
        assertTrue(store.isEligible(store.message("live")!!, clock))
    }

    @Test
    fun appliesTheFrequencyCapAcrossMessagesButNotToTests() {
        val store = store()
        val cap = JSONObject("""{"count":2,"hours":24,"impressionsInWindow":1}""")
        store.replace(Fixtures.feed(Fixtures.message("a"), Fixtures.message("b"), Fixtures.message("t", test = true, impressionCount = 9)), cap, "u1")
        assertTrue(store.isEligible(store.message("b")!!, clock))
        store.recordImpression("a")
        assertFalse(store.isEligible(store.message("b")!!, clock))
        assertTrue(store.isEligible(store.message("t")!!, clock))
    }

    @Test
    fun neverShowsInboxOnlyOrUnsupportedMessages() {
        val store = store()
        store.replace(Fixtures.feed(Fixtures.message("inbox", format = "inbox_only"), Fixtures.message("new", design = Fixtures.design(schemaVersion = 9))), null, "u1")
        assertFalse(store.isEligible(store.message("inbox")!!, clock))
        assertFalse(store.isEligible(store.message("new")!!, clock))
    }
}

class ServerSentEventParserTest {
    @Test
    fun parsesEventsCommentsAndRetry() {
        val parser = ServerSentEventParser()
        val lines = listOf("retry: 5000", "", ": ping", "", "event: message.added", """data: {"type":"message.added"}""", "", "data: a", "data: b", "")
        val events = lines.mapNotNull(parser::feed)
        assertEquals(listOf(ServerSentEvent("message.added", """{"type":"message.added"}"""), ServerSentEvent("message", "a\nb")), events)
        assertEquals(5_000L, parser.retryMilliseconds)
    }
}

class InAppColorTest {
    @Test
    fun parsesHexColours() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF112233), InAppHexColor.parse("#112233"))
        assertEquals(androidx.compose.ui.graphics.Color(0x80112233), InAppHexColor.parse("#11223380"))
        assertNull(InAppHexColor.parse("112233"))
        assertNull(InAppHexColor.parse("#12"))
    }
}
