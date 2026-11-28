package com.locket.app.rust

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendChatMessagePayloadTest {
    @Test
    fun fallbackPreservesExplicitNullAndUnicode() {
        for (moment in listOf(null, "", " ")) {
            val bytes = RustLocketCore.buildSendChatMessagePayloadFallback("friend", "❤️", moment, TOKEN)
            val data = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("data")
            assertTrue(data.has("moment_uid"))
            assertTrue(data.get("moment_uid").isJsonNull)
            assertEquals("❤️", data.get("msg").asString)
            assertEquals("friend", data.get("receiver_uid").asString)
            assertEquals(TOKEN, data.get("client_token").asString)
            assertEquals(4, data.size())
        }
    }

    @Test
    fun fallbackPreservesReplyMoment() {
        val bytes = RustLocketCore.buildSendChatMessagePayloadFallback("friend", "reply", "canonical-moment", TOKEN)
        val data = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("data")
        assertEquals("canonical-moment", data.get("moment_uid").asString)
    }

    companion object {
        private const val TOKEN = "123e4567-e89b-42d3-a456-426614174000"
    }
}
