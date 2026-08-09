package com.newax.aegis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistantEngineTest {

    @Test
    fun testParseReplyNotification() {
        val result = LocalAssistantEngine.parseCommand("reply notification 0|com.whatsapp|1|null|1000 ::: Hello there!")
        assertTrue(result.action is ProposedAction.ReplyNotification)
        val action = result.action as ProposedAction.ReplyNotification
        assertEquals("0|com.whatsapp|1|null|1000", action.notificationKey)
        assertEquals("Hello there!", action.text)
    }

    @Test
    fun testParseSetAlarm() {
        val result = LocalAssistantEngine.parseCommand("set alarm 07:30 ::: Wake up")
        assertTrue(result.action is ProposedAction.SetAlarm)
        val action = result.action as ProposedAction.SetAlarm
        assertEquals("07:30", action.time)
        assertEquals("Wake up", action.label)
    }

    @Test
    fun testParseCreateEvent() {
        val result = LocalAssistantEngine.parseCommand("create event 15:00 ::: Team Meeting")
        assertTrue(result.action is ProposedAction.CreateEvent)
        val action = result.action as ProposedAction.CreateEvent
        assertEquals("15:00", action.time)
        assertEquals("Team Meeting", action.title)
    }
}
