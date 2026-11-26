package com.stmobile

import com.stmobile.core.model.MessageRole
import com.stmobile.core.stengine.BridgeProtocol
import com.stmobile.core.stengine.EngineCommand
import com.stmobile.core.stengine.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {

    @Test
    fun `decodes stream token envelope`() {
        val raw = """{"type":"STREAM_TOKEN","payload":{"messageId":3,"swipeId":1,"rawText":"Hel","displayText":"Hello"}}"""
        val envelope = BridgeProtocol.decodeEnvelope(raw)!!
        val event = BridgeProtocol.eventFrom(envelope, "chat-1") as EngineEvent.StreamToken
        assertEquals(3, event.messageId)
        assertEquals(1, event.swipeId)
        assertEquals("Hel", event.rawText)
        assertEquals("Hello", event.displayText)
    }

    @Test
    fun `decodes engine ready`() {
        val raw = """{"type":"ENGINE_READY","payload":{"version":"1.12.14","currentUser":"Jackie"}}"""
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!) as EngineEvent.Ready
        assertEquals("1.12.14", event.version)
        assertEquals("Jackie", event.currentUser)
    }

    @Test
    fun `decodes character sync with tags`() {
        val raw = """
            {"type":"CHARACTERS_SYNC","payload":{"characters":[
              {"id":"seraphina.png","name":"Seraphina","avatarUrl":"/characters/seraphina.png",
               "tags":["fantasy","noble"],"lastMessageDate":1710000000000}
            ]}}
        """.trimIndent()
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!) as EngineEvent.CharactersSync
        assertEquals(1, event.characters.size)
        val character = event.characters.first()
        assertEquals("Seraphina", character.name)
        assertEquals(listOf("fantasy", "noble"), character.tags)
        assertEquals(1710000000000L, character.lastMessageDate)
    }

    @Test
    fun `decodes chat loaded and keeps raw and display text separate`() {
        val raw = """
            {"type":"CHAT_LOADED","payload":{"chatId":"chat-9","characterId":"sera.png","messages":[
              {"messageId":0,"role":"user","rawText":"Hi","displayText":"Hi","swipeId":0,"swipeCount":1,"timestamp":10},
              {"messageId":1,"role":"assistant","rawText":"*smiles* Hello","displayText":"<b>smiles</b> Hello","swipeId":2,"swipeCount":4,"timestamp":20}
            ]}}
        """.trimIndent()
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!) as EngineEvent.ChatLoaded
        assertEquals("chat-9", event.chatId)
        assertEquals(2, event.messages.size)
        val assistant = event.messages[1]
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals("*smiles* Hello", assistant.rawText)
        assertEquals("<b>smiles</b> Hello", assistant.displayText)
        assertEquals(2, assistant.swipeId)
        assertEquals(4, assistant.swipeCount)
    }

    @Test
    fun `missing display text falls back to raw text`() {
        val raw = """{"type":"CHAT_LOADED","payload":{"chatId":"c","messages":[{"messageId":0,"rawText":"fallback"}]}}"""
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!) as EngineEvent.ChatLoaded
        assertEquals("fallback", event.messages.first().displayText)
    }

    @Test
    fun `unknown event type is surfaced instead of crashing`() {
        val raw = """{"type":"SOMETHING_NEW","payload":{"x":1}}"""
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!)
        assertTrue(event is EngineEvent.Unknown)
    }

    @Test
    fun `malformed json decodes to null`() {
        assertNull(BridgeProtocol.decodeEnvelope("{not json"))
        assertNull(BridgeProtocol.decodeEnvelope("{}"))
    }

    @Test
    fun `encodes command with payload and request id`() {
        val payload = BridgeProtocol.commandPayload("text" to "hello")
        val encoded = BridgeProtocol.encodeCommand(EngineCommand.SEND_MESSAGE, payload, "req-1")
        val envelope = BridgeProtocol.decodeEnvelope(encoded)!!
        assertEquals(EngineCommand.SEND_MESSAGE, envelope.type)
        assertEquals("req-1", envelope.requestId)
        assertEquals("hello", envelope.payload["text"].toString().trim('"'))
    }

    @Test
    fun `generation state decodes`() {
        val raw = """{"type":"GENERATION_STATE","payload":{"isGenerating":true,"reason":"started"}}"""
        val event = BridgeProtocol.eventFrom(BridgeProtocol.decodeEnvelope(raw)!!) as EngineEvent.GenerationState
        assertTrue(event.isGenerating)
        assertEquals("started", event.reason)
    }
}
