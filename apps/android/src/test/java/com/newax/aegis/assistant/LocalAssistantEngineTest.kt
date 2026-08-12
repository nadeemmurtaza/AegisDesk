package com.newax.aegis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistantEngineTest {

    private val engine = LocalAssistantEngine()

    @Test
    fun testParseReplyNotification() {
        val reply = engine.generateReply(
            "reply notification 0|com.whatsapp|1|null|1000 ::: Hello there!",
            screen = ""
        )
        val action = reply.proposedAction
        assertTrue(action is ProposedAction.ReplyNotification)
        action as ProposedAction.ReplyNotification
        assertEquals("0|com.whatsapp|1|null|1000", action.key)
        assertEquals("Hello there!", action.text)
    }

    @Test
    fun testParseCreateEvent() {
        val reply = engine.generateReply("create event Team Meeting at 15:00", screen = "")
        val action = reply.proposedAction
        assertTrue(action is ProposedAction.CreateEvent)
        action as ProposedAction.CreateEvent
        assertEquals("Team Meeting", action.title)
        assertEquals("15:00", action.time)
    }

    @Test
    fun testParseOpenApp() {
        val reply = engine.generateReply("open WhatsApp", screen = "")
        val action = reply.proposedAction
        assertTrue(action is ProposedAction.OpenApp)
        assertEquals("WhatsApp", (action as ProposedAction.OpenApp).name)
    }

    @Test
    fun testCanHandleRecognizesCommandPrefixes() {
        assertTrue(engine.canHandle("reply notification 123 ::: Hi"))
        assertTrue(engine.canHandle("create event Standup at 09:00"))
        assertTrue(engine.canHandle("open WhatsApp"))
    }
}
