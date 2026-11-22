package app.shilex.android.control

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppWorkflowTest {
    @Test
    fun matchesComposerDraftAndDistinguishesSendButtonFromMicButton() {
        val elements = listOf(
            ScreenElement(1, "field", "Message", "On my way home"),
            ScreenElement(2, "button", "Attach", ""),
            ScreenElement(3, "button", "Send", ""),
        )
        val meta = mapOf(
            3 to ControlService.NodeMeta(3, "send", "Send", "button", "com.whatsapp", Rect(0, 0, 10, 10)),
        )
        assertTrue(WhatsAppComposerMatcher.hasMatchingDraft(elements, "On my way home!"))
        assertFalse(WhatsAppComposerMatcher.hasMatchingDraft(elements, "Different text"))
        assertEquals(3, WhatsAppComposerMatcher.findSendButtonNumber(elements, meta))

        // Must never confuse voice note / mic button with Send
        val micOnly = listOf(
            ScreenElement(1, "field", "Message", "On my way home"),
            ScreenElement(2, "button", "Send voice message", ""),
        )
        val micMeta = mapOf(
            2 to ControlService.NodeMeta(2, "voice_note_btn", "Send voice message", "button", "com.whatsapp", Rect(0, 0, 10, 10)),
        )
        assertNull(WhatsAppComposerMatcher.findSendButtonNumber(micOnly, micMeta))
    }

    @Test
    fun verifiesChatRecipientHeaderAndFindsCallButtons() {
        val headerElements = listOf(
            ScreenElement(1, "button", "Navigate up", ""),
            ScreenElement(2, "text", "Rahul Sharma", ""),
            ScreenElement(3, "button", "Voice call", ""),
            ScreenElement(4, "button", "Video call", ""),
            ScreenElement(5, "field", "Message", ""),
        )
        val meta = mapOf(
            2 to ControlService.NodeMeta(2, "conversation_contact_name", "Rahul Sharma", "text", "com.whatsapp", Rect(50, 0, 200, 50)),
            3 to ControlService.NodeMeta(3, "voice_call", "Voice call", "button", "com.whatsapp", Rect(250, 0, 300, 50)),
            4 to ControlService.NodeMeta(4, "video_call", "Video call", "button", "com.whatsapp", Rect(310, 0, 360, 50)),
        )

        assertTrue(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "Rahul"))
        assertTrue(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "rahul sharma"))
        assertFalse(WhatsAppChatVerifier.matchesExpectedRecipient(headerElements, meta, "Priya"))

        assertEquals(3, WhatsAppCallMatcher.findCallButtonNumber(headerElements, meta, WhatsAppCallMatcher.CallKind.Audio))
        assertEquals(4, WhatsAppCallMatcher.findCallButtonNumber(headerElements, meta, WhatsAppCallMatcher.CallKind.Video))
    }

    @Test
    fun findsMessageBubbleDeleteIconAndDistinguishesDeleteScopes() {
        val chatMessages = listOf(
            ScreenElement(1, "text", "Rahul Sharma", ""),
            ScreenElement(2, "text", "Hello, reaching in 10 mins", ""),
            ScreenElement(3, "text", "Okay see you", ""),
            ScreenElement(4, "field", "Message", ""),
        )
        assertEquals(2, WhatsAppDeleteMatcher.findMessageBubbleNumber(chatMessages, "reaching in 10"))
        assertEquals(3, WhatsAppDeleteMatcher.findMessageBubbleNumber(chatMessages, ""))

        val selectionBar = listOf(
            ScreenElement(1, "text", "1 selected", ""),
            ScreenElement(2, "button", "Delete", ""),
        )
        val selectionMeta = mapOf(
            2 to ControlService.NodeMeta(2, "menuitem_conversations_delete", "Delete", "button", "com.whatsapp", Rect(200, 0, 260, 50)),
        )
        assertEquals(2, WhatsAppDeleteMatcher.findDeleteButtonNumber(selectionBar, selectionMeta))

        val bothOptionsDialog = listOf(
            ScreenElement(1, "text", "Delete message?", ""),
            ScreenElement(2, "button", "Delete for everyone", ""),
            ScreenElement(3, "button", "Delete for me", ""),
            ScreenElement(4, "button", "Cancel", ""),
        )
        val both = WhatsAppDeleteMatcher.inspectDeleteDialog(bothOptionsDialog, emptyMap())
        assertEquals(2, both.forEveryoneElementNumber)
        assertEquals(3, both.forMeElementNumber)
        assertEquals(4, both.cancelElementNumber)

        val onlyMeDialog = listOf(
            ScreenElement(1, "text", "Delete message from Rahul?", ""),
            ScreenElement(2, "button", "Delete for me", ""),
            ScreenElement(3, "button", "Cancel", ""),
        )
        val onlyMe = WhatsAppDeleteMatcher.inspectDeleteDialog(onlyMeDialog, emptyMap())
        assertNull(onlyMe.forEveryoneElementNumber)
        assertEquals(2, onlyMe.forMeElementNumber)
    }
}
