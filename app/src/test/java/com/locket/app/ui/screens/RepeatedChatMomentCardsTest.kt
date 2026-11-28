package com.locket.app.ui.screens

import com.locket.app.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatedChatMomentCardsTest {
    private fun reply(id: String, moment: String? = "photo", sender: String = "sender",
                      text: String = "reply", url: String? = "https://example.invalid/photo.jpg") =
        ChatMessage(id, "conversation", sender, true, text, replyMomentUid = moment, thumbnailUrl = url)

    @Test
    fun consecutiveRepliesRenderOnePhotoWithoutRemovingMessages() {
        val messages = listOf(reply("1"), reply("2"), reply("3"))
        assertEquals(setOf("2", "3"), repeatedChatMomentCards(messages))
        assertEquals(listOf("1", "2", "3"), messages.map { it.id })
    }

    @Test
    fun distinctMomentsSendersAndStandalonePhotosKeepTheirCards() {
        val messages = listOf(reply("1"), reply("2", moment = "other"), reply("3", sender = "other"),
            reply("4", sender = "other", text = ""), reply("5", sender = "other", url = null),
            reply("6", sender = "other"))
        assertEquals(emptySet<String>(), repeatedChatMomentCards(messages))
    }

    @Test
    fun knownStorageObjectMatchesAcrossDownloadTokens() {
        val base = "https://firebasestorage.googleapis.com/v0/b/locket-img/o/users%2Fowner%2Fmoments%2Fthumbnails%2Fphoto.jpg"
        val messages = listOf(reply("1", moment = null, url = "$base?token=one"),
            reply("2", moment = null, url = "$base?token=two"))
        assertEquals(setOf("2"), repeatedChatMomentCards(messages))
    }
}
