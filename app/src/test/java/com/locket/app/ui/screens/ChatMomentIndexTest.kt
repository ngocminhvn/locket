package com.locket.app.ui.screens

import com.locket.app.model.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMomentIndexTest {
    private fun thumbnail(owner: String, token: String = "a") =
        "https://firebasestorage.googleapis.com/v0/b/locket-img/o/users%2F$owner%2Fmoments%2Fthumbnails%2Fphoto.jpg?alt=media&token=$token"

    @Test fun resolvesOldUncachedAttachmentFromStorageOwner() {
        val index = ChatMomentIndex(emptyList())
        assertEquals("friend", index.resolve("old-id", thumbnail("friend")).ownerUid)
        assertEquals("self", index.resolve("other-id", thumbnail("self")).ownerUid)
        assertNull(index.resolve("old-id", thumbnail("friend")).moment)
    }

    @Test fun matchesTheSamePhotoAfterDownloadTokenChanges() {
        val moment = Moment("canonical", "friend", "", thumbnailUrl = thumbnail("friend"), timestampSeconds = 123L)
        val result = ChatMomentIndex(listOf(moment)).resolve("different-id", thumbnail("friend", "b"))
        assertEquals(moment, result.moment)
        assertEquals("friend", result.ownerUid)
    }

    @Test fun rejectsConflictingCachedOwnerAndUntrustedStorageHosts() {
        val wrong = Moment("old-id", "self", "", thumbnailUrl = thumbnail("self"))
        val index = ChatMomentIndex(listOf(wrong))
        val result = index.resolve("old-id", thumbnail("friend"))
        assertEquals("friend", result.ownerUid)
        assertNull(result.moment)
        assertNull(index.resolve(null, thumbnail("friend").replace("firebasestorage.googleapis.com", "example.org")).ownerUid)
    }
}
