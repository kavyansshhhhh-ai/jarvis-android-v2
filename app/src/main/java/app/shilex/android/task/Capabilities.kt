package app.shilex.android.task

/** How much harm a wrong action could do. Decides what needs the user's own confirmation. */
enum class Risk { Low, Medium, High, Financial }

/** Who finishes the job: the phone by itself, or the user in another app (Send, Pay…). */
enum class Finish { Phone, User }

/**
 * One thing the assistant may do. The model can only call tools listed here; anything else
 * is refused, so it can't invent actions or permissions.
 */
data class Capability(
    val tool: String,
    val title: String,
    val risk: Risk,
    val finish: Finish = Finish.Phone,
    /** For [Finish.User]: what the user still has to do. */
    val handover: String = "",
)

object Capabilities {
    private val all = listOf(
        Capability("open_app", "Open an app", Risk.Low),
        Capability("list_installed_apps", "List installed apps", Risk.Low),
        Capability("set_alarm", "Set an alarm", Risk.Low),
        Capability("set_timer", "Start a timer", Risk.Low),
        Capability("reminder_set", "Set a reminder", Risk.Low),
        Capability("reminders_list", "List reminders", Risk.Low),
        Capability("reminder_cancel", "Cancel a reminder", Risk.Medium),
        Capability("play_song", "Play a song", Risk.Low),
        Capability("media_control", "Control music", Risk.Low),
        Capability("device_control", "Phone controls", Risk.Low),
        Capability("open_system_panel", "System panel", Risk.Low),
        Capability("get_current_datetime", "Check the time", Risk.Low),
        Capability("get_weather", "Check the weather", Risk.Low),
        Capability("web_search_internal", "Answer from web", Risk.Low),
        Capability("open_web_search", "Search in Chrome", Risk.Low),
        Capability("ui_read", "Read the screen", Risk.Medium),
        Capability("ui_describe_screen", "Understand visible photo", Risk.Medium),
        Capability("notifications_check", "Check notifications", Risk.Medium),
        Capability("watch_messages", "Watch messages", Risk.Low),
        Capability("reply_prepare", "Prepare a reply", Risk.Low),
        Capability("reply_send", "Send a reply", Risk.High),
        Capability("get_personal_profile", "Read personal profile", Risk.Low),
        Capability("remember", "Remember", Risk.Low),
        Capability("recall", "Look up a note", Risk.Low),
        Capability("forget", "Forget a note", Risk.Medium),
        Capability("note_edit", "Edit a note", Risk.Medium),
        Capability("routine_save", "Save a routine", Risk.Low),
        Capability("routine_run", "Run a routine", Risk.Low),
        Capability("calendar_list", "Check calendar", Risk.Medium),
        Capability("calendar_add", "Add to calendar", Risk.Medium, Finish.User, "Check the event, then tap Save in Calendar."),
        Capability("calendar_change", "Change an event", Risk.Medium, Finish.User, "Check the change, then tap Save in Calendar."),
        Capability("ui_click", "Tap on screen", Risk.Medium),
        Capability("ui_tap_seen", "Tap by sight", Risk.Medium),
        Capability("ui_type", "Type on screen", Risk.Medium),
        Capability("ui_scroll", "Scroll", Risk.Low),
        Capability("ui_key", "Press a key", Risk.Low),
        Capability("hand_to_user", "Your turn", Risk.Low),
        Capability("call_contact", "Call", Risk.High, Finish.User, "Tap Call in the dialer."),
        Capability("whatsapp_send", "WhatsApp message", Risk.High, Finish.User, "Approve on the Allow card or tap Send in WhatsApp."),
        Capability("whatsapp_open_chat", "Open WhatsApp chat", Risk.Low),
        Capability("whatsapp_read_chat", "Read WhatsApp chat", Risk.Medium),
        Capability("whatsapp_search_contact", "Search WhatsApp contact", Risk.Low),
        Capability("whatsapp_call", "WhatsApp call", Risk.High, Finish.User, "WhatsApp call screen opening."),
        Capability("whatsapp_delete_message", "Delete WhatsApp message", Risk.High),
        Capability("playstore_search_install", "Play Store lookup/install", Risk.Medium),
        Capability("gallery_browse_photos", "Browse gallery photos", Risk.Low),
        Capability("gallery_search_photos", "Search gallery photos", Risk.Low),
        Capability("gallery_open_photo", "Open a gallery photo", Risk.Low),
        Capability("gallery_analyze_photo", "Analyze a gallery photo", Risk.Medium),
        Capability("gallery_delete_photo", "Delete photo (confirmed)", Risk.High),
        Capability("shutdown_assistant", "Shut down JARVIS", Risk.Low),
        Capability("email_compose", "Email", Risk.High, Finish.User, "Check the email, then tap Send in the email app."),
        Capability("sms_send", "Text message", Risk.High, Finish.User, "Check the message, then tap Send in Messages."),
        Capability(
            "book_tickets", "Book tickets", Risk.Financial, Finish.User,
            "Check the show, seats and total, then pay in the booking app yourself. Nothing is booked until it confirms.",
        ),
    ).associateBy { it.tool }

    fun of(tool: String): Capability? = all[tool]

    val tools: Set<String> get() = all.keys
}
