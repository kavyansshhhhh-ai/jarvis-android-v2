package app.shilex.android.tools

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.shilex.android.Diag
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenVision
import app.shilex.android.data.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure photo candidate matching, metadata formatting & safety confirmation helper (unit-tested without Android).
 */
object GalleryPhotoMatcher {
    data class MediaPhoto(
        val id: Long,
        val displayName: String,
        val relativePath: String = "",
        val dateModifiedSec: Long = 0L,
        val mimeType: String = "image/jpeg",
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0L,
    )

    sealed interface Resolution {
        data class Single(val photo: MediaPhoto) : Resolution
        data class Ambiguous(val candidates: List<MediaPhoto>) : Resolution
        data object None : Resolution
    }

    fun resolve(query: String, photos: List<MediaPhoto>): Resolution {
        if (photos.isEmpty()) return Resolution.None
        val ordered = photos.sortedByDescending { it.dateModifiedSec }
        val q = query.lowercase().trim()
        if (q.isEmpty() || q in LATEST_SYNONYMS) {
            return Resolution.Single(ordered.first())
        }
        val exactName = ordered.filter { it.displayName.lowercase() == q || it.displayName.substringBeforeLast('.').lowercase() == q }
        if (exactName.size == 1) return Resolution.Single(exactName.single())
        if (exactName.size > 1) return Resolution.Ambiguous(exactName.take(5))

        val partial = searchPhotos(q, ordered)
        return when {
            partial.size == 1 -> Resolution.Single(partial.single())
            partial.size > 1 -> Resolution.Ambiguous(partial.take(5))
            else -> Resolution.None
        }
    }

    fun searchPhotos(query: String, photos: List<MediaPhoto>): List<MediaPhoto> {
        val ordered = photos.sortedByDescending { it.dateModifiedSec }
        val tokens = query.lowercase().trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in SEARCH_STOP_WORDS }
        if (tokens.isEmpty()) return ordered
        return ordered.filter { photo ->
            val searchable = buildString {
                append(photo.displayName.lowercase()).append(' ')
                append(photo.relativePath.lowercase()).append(' ')
                append(photo.mimeType.lowercase()).append(' ')
                if (photo.dateModifiedSec > 0L) {
                    val dateText = runCatching {
                        DATE_FMT.format(Instant.ofEpochSecond(photo.dateModifiedSec).atZone(ZoneId.systemDefault()))
                    }.getOrDefault("")
                    append(dateText.lowercase())
                }
            }
            tokens.all { token ->
                when (token) {
                    "screenshot", "screenshots", "screen" -> "screenshot" in searchable || "screen_" in searchable
                    "camera", "selfie" -> "dcim" in searchable || "camera" in searchable || "img_" in searchable
                    "whatsapp" -> "whatsapp" in searchable
                    "download", "downloads" -> "download" in searchable
                    else -> token in searchable
                }
            }
        }
    }

    fun formatMetadataSummary(photo: MediaPhoto): String = buildString {
        append(photo.displayName)
        if (photo.relativePath.isNotBlank()) {
            append(" [").append(photo.relativePath.trimEnd('/')).append(']')
        }
        if (photo.width > 0 && photo.height > 0) {
            append(" (${photo.width}x${photo.height})")
        }
        if (photo.sizeBytes > 0L) {
            val kb = photo.sizeBytes / 1024L
            if (kb >= 1024L) {
                append(String.format(java.util.Locale.US, " %.1f MB", kb / 1024f))
            } else {
                append(" ${kb.coerceAtLeast(1L)} KB")
            }
        }
        if (photo.dateModifiedSec > 0L) {
            val formatted = runCatching {
                DATE_FMT.format(Instant.ofEpochSecond(photo.dateModifiedSec).atZone(ZoneId.systemDefault()))
            }.getOrDefault("")
            if (formatted.isNotEmpty()) append(" · ").append(formatted)
        }
    }

    fun confirmationPrompt(photo: MediaPhoto): String =
        "Kya aap isi photo ko delete karna chahte hain? (${photo.displayName})"

    private val LATEST_SYNONYMS = setOf(
        "latest", "last", "recent", "this", "current", "ye", "yeh", "is",
        "this photo", "yeh photo", "ye photo", "is photo", "selected", "most recent",
    )
    private val SEARCH_STOP_WORDS = setOf("photo", "photos", "image", "images", "pic", "picture", "my", "the", "a", "in", "from")
    private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", java.util.Locale.US)
}

/**
 * Permission-aware Scoped-Storage & MediaStore gallery suite:
 * browse recent photos, search metadata, open a photo, analyze authorized photos with Vision
 * without repetitive per-image confirmation prompts, and delete with mandatory confirmation.
 */
object GalleryTools {
    val DeletePhoto: PhoneTool = DeletePhotoTool
    val BrowsePhotos: PhoneTool = BrowsePhotosTool
    val SearchPhotos: PhoneTool = SearchPhotosTool
    val OpenPhoto: PhoneTool = OpenPhotoTool
    val AnalyzePhoto: PhoneTool = AnalyzePhotoTool

    fun all(): List<PhoneTool> = listOf(
        BrowsePhotos,
        SearchPhotos,
        OpenPhoto,
        AnalyzePhoto,
        DeletePhoto,
    )

    fun requiredReadPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasReadAccess(host: ToolHost, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (host.hasPermission(requiredReadPermission(sdkInt))) return true
        return sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            host.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private suspend fun queryPhotos(host: ToolHost, limit: Int = 50): List<GalleryPhotoMatcher.MediaPhoto> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<GalleryPhotoMatcher.MediaPhoto>()
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            }
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                pathCol,
            )
            runCatching {
                host.context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val widthIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val pathIdx = cursor.getColumnIndex(pathCol)
                    if (idIdx < 0) return@use
                    while (cursor.moveToNext() && out.size < limit) {
                        val id = cursor.getLong(idIdx)
                        out += GalleryPhotoMatcher.MediaPhoto(
                            id = id,
                            displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().ifBlank { "Photo #$id" } else "Photo #$id",
                            relativePath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else "",
                            dateModifiedSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                            mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty().ifBlank { "image/jpeg" } else "image/jpeg",
                            width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0,
                            height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0,
                            sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        )
                    }
                }
            }.onFailure { err ->
                Diag.w("gallery: MediaStore query failed: ${err.message}")
            }
            out
        }

    private suspend fun photoExists(host: ToolHost, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            host.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { c ->
                c.moveToFirst()
            } ?: false
        }.getOrDefault(false)
    }

    private fun mediaUri(photo: GalleryPhotoMatcher.MediaPhoto): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)

    private object BrowsePhotosTool : PhoneTool {
        override val name = "gallery_browse_photos"

        override fun declaration() = Schema.function(
            name,
            "Lists recent photos from the user's gallery with filename, album folder, dimensions, size, and timestamp.",
            Schema.integer("limit", "How many recent photos to list (default 10, max 25)."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!hasReadAccess(host)) {
                host.requestPermission(requiredReadPermission())
                return Results.needsPermission("Photos / Gallery")
            }
            val limit = args.optInt("limit", 10).coerceIn(1, 25)
            val photos = queryPhotos(host, limit = limit)
            if (photos.isEmpty()) {
                return Results.done("No authorized photos were found in the device gallery. You can ask to pick a photo with Android Photo Picker.")
            }
            val lines = photos.mapIndexed { idx, photo ->
                "${idx + 1}. ${GalleryPhotoMatcher.formatMetadataSummary(photo)}"
            }.joinToString("\n")
            return Results.done("Found ${photos.size} recent gallery photo(s):\n$lines")
        }
    }

    private object SearchPhotosTool : PhoneTool {
        override val name = "gallery_search_photos"

        override fun declaration() = Schema.function(
            name,
            "Searches gallery photos using metadata (filename, album/folder like Screenshots or Camera, type, or date).",
            Schema.string("query", "Search keyword such as 'screenshot', 'whatsapp', album name, date, or partial filename.", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val query = args.text("query")
            if (query.isEmpty()) {
                return Results.failed("no_query", "Provide a keyword, album, or filename to search gallery photos.")
            }
            if (!hasReadAccess(host)) {
                host.requestPermission(requiredReadPermission())
                return Results.needsPermission("Photos / Gallery")
            }
            val photos = queryPhotos(host, limit = 80)
            val matches = GalleryPhotoMatcher.searchPhotos(query, photos).take(15)
            if (matches.isEmpty()) {
                return Results.done("No authorized gallery photos matched \"$query\".")
            }
            val lines = matches.mapIndexed { idx, photo ->
                "${idx + 1}. ${GalleryPhotoMatcher.formatMetadataSummary(photo)}"
            }.joinToString("\n")
            return Results.done("Matching gallery photos for \"$query\":\n$lines")
        }
    }

    private object OpenPhotoTool : PhoneTool {
        override val name = "gallery_open_photo"

        override fun declaration() = Schema.function(
            name,
            "Opens a targeted or latest photo from the user's gallery in the device photo viewer.",
            Schema.string("target", "Photo filename, keyword (e.g. 'screenshot'), or 'latest'."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val target = args.text("target").ifEmpty { "latest" }
            if (!hasReadAccess(host)) {
                host.requestPermission(requiredReadPermission())
                return Results.needsPermission("Photos / Gallery")
            }
            val photos = queryPhotos(host, limit = 60)
            if (photos.isEmpty()) {
                return Results.failed("no_photos", "No photos were found in MediaStore gallery.")
            }
            val selected = when (val res = GalleryPhotoMatcher.resolve(target, photos)) {
                is GalleryPhotoMatcher.Resolution.Single -> res.photo
                is GalleryPhotoMatcher.Resolution.Ambiguous -> {
                    return Results.ask(
                        "Multiple photos match \"$target\". Ask which one to open.",
                        res.candidates.map { GalleryPhotoMatcher.formatMetadataSummary(it) },
                    )
                }
                GalleryPhotoMatcher.Resolution.None -> {
                    return Results.failed("not_found", "Could not find a gallery photo matching \"$target\".")
                }
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(mediaUri(selected), selected.mimeType.ifBlank { "image/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return host.openApp(
                intent = intent,
                label = selected.displayName,
                done = "Opened ${selected.displayName} (${GalleryPhotoMatcher.formatMetadataSummary(selected)}).",
            )
        }
    }

    private object AnalyzePhotoTool : PhoneTool {
        override val name = "gallery_analyze_photo"

        override fun declaration() = Schema.function(
            name,
            "Reads metadata and visually analyzes an authorized gallery photo (or opens Android Photo Picker if gallery permission is not granted) to identify objects, documents, screenshots, text, and answer questions directly without extra confirmation popups.",
            Schema.string("target", "Photo filename, keyword like 'latest screenshot', or 'latest' (default: latest). Use 'pick' to choose via Android Photo Picker."),
            Schema.string("question", "What the user wants to know or check in the photo."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val target = args.text("target").ifEmpty { "latest" }
            val question = args.text("question")
            val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)

            if (target.equals("pick", ignoreCase = true) || target.equals("picker", ignoreCase = true)) {
                if (key.isNullOrBlank()) {
                    return Results.failed(
                        "no_key",
                        "Visual photo analysis needs a Gemini API key configured in Settings. Metadata browsing still works once Gallery permission is granted.",
                    )
                }
                host.requestPhotoPick(question)
                return Results.done("Opened Android's Photo Picker so you can select the photo for analysis.")
            }

            if (!hasReadAccess(host)) {
                if (target.equals("latest", ignoreCase = true) && !key.isNullOrBlank()) {
                    host.requestPhotoPick(question)
                    return Results.done(
                        "Gallery permission isn't granted yet, so Android's Photo Picker was opened for you to pick a photo directly.",
                    )
                }
                host.requestPermission(requiredReadPermission())
                return Results.needsPermission("Photos / Gallery")
            }

            val photos = queryPhotos(host, limit = 60)
            if (photos.isEmpty()) {
                if (!key.isNullOrBlank()) {
                    host.requestPhotoPick(question)
                    return Results.done("No photos were visible under current media access. Opened Android Photo Picker so you can pick the photo directly.")
                }
                return Results.failed("no_photos", "No authorized photos were found in MediaStore gallery.")
            }

            val selected = when (val res = GalleryPhotoMatcher.resolve(target, photos)) {
                is GalleryPhotoMatcher.Resolution.Single -> res.photo
                is GalleryPhotoMatcher.Resolution.Ambiguous -> res.candidates.first()
                GalleryPhotoMatcher.Resolution.None -> {
                    return Results.failed("not_found", "Could not find an authorized gallery photo matching \"$target\".")
                }
            }

            val metadata = GalleryPhotoMatcher.formatMetadataSummary(selected)
            if (key.isNullOrBlank()) {
                return Results.failed(
                    "no_key",
                    "AI vision analysis is unavailable because no Gemini API key is configured in Settings. Matched photo metadata: $metadata.",
                )
            }

            val uri = mediaUri(selected)
            return when (val outcome = ScreenVision(host.http, key).describeUri(host.context, uri, question)) {
                is ScreenVision.Outcome.Described -> Results.done(
                    "Photo: $metadata\n${outcome.summary}",
                )
                is ScreenVision.Outcome.Failed -> Results.failed(
                    "vision_failed",
                    "Could not analyze $metadata: ${outcome.message}",
                )
            }
        }
    }

    private object DeletePhotoTool : PhoneTool {
        override val name = "gallery_delete_photo"

        override fun declaration() = Schema.function(
            name,
            "Finds the targeted or latest photo in the user's gallery, asks explicit yes/no confirmation ('Kya aap isi photo ko delete karna chahte hain?'), launches Android's official delete confirmation dialog, and verifies whether the photo was deleted.",
            Schema.string("target", "Photo filename or 'latest'/'current'."),
            Schema.string("confirmed", "true ONLY after the user explicitly answered yes to deleting this specific photo.", choices = listOf("false", "true")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val target = args.text("target")
            if (target.isEmpty()) {
                return Results.failed("no_target", "Never delete automatically just because gallery was mentioned. Ask which exact photo the user wants to delete.")
            }

            if (!hasReadAccess(host)) {
                host.requestPermission(requiredReadPermission())
                return Results.needsPermission("Photos / Gallery")
            }

            val photos = queryPhotos(host, limit = 50)
            if (photos.isEmpty()) {
                return Results.failed("no_photos", "No photos were found in MediaStore gallery.")
            }

            val selected = when (val res = GalleryPhotoMatcher.resolve(target, photos)) {
                is GalleryPhotoMatcher.Resolution.Single -> res.photo
                is GalleryPhotoMatcher.Resolution.Ambiguous -> {
                    return Results.ask(
                        "Several photos match \"$target\". Ask which exact photo to delete.",
                        res.candidates.map { it.displayName },
                    )
                }
                GalleryPhotoMatcher.Resolution.None -> {
                    return Results.failed("not_found", "Could not find a gallery photo matching \"$target\".")
                }
            }

            // Explicit mandatory confirmation BEFORE invoking Android's delete request — never bypassed!
            val prompt = GalleryPhotoMatcher.confirmationPrompt(selected)
            val confirmedByArg = args.text("confirmed").equals("true", ignoreCase = true)
            val confirmed = if (confirmedByArg) {
                true
            } else {
                val overlay = ControlService.instance?.overlay?.takeUnless { it.stopped }
                overlay?.confirm(prompt) ?: return Results.ask(prompt, listOf("Haan, delete karo", "Nahi, cancel karo"))
            }
            if (!confirmed) {
                return Results.done("Photo deletion cancelled. \"${selected.displayName}\" was kept safe.")
            }

            val uri = mediaUri(selected)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent = runCatching {
                    MediaStore.createDeleteRequest(host.context.contentResolver, listOf(uri))
                }.getOrNull() ?: return Results.failed("delete_request_failed", "Android couldn't create the delete request for ${selected.displayName}.")

                val systemApproved = host.requestGalleryDelete(pendingIntent.intentSender)
                if (!systemApproved) {
                    return Results.done("Deletion of \"${selected.displayName}\" was cancelled in Android's confirmation dialog.")
                }
            } else {
                val rows = withContext(Dispatchers.IO) {
                    runCatching {
                        host.context.contentResolver.delete(uri, null, null)
                    }.getOrDefault(0)
                }
                if (rows <= 0) {
                    return Results.failed("delete_failed", "Android did not delete \"${selected.displayName}\".")
                }
            }

            // Verify actual MediaStore state after deletion before claiming deleted
            val stillExists = photoExists(host, uri)
            return if (!stillExists) {
                Diag.i("gallery: verified deleted ${selected.displayName}")
                Results.done("Verified: \"${selected.displayName}\" has been deleted from the gallery.")
            } else {
                Results.failed("verify_failed", "\"${selected.displayName}\" still appears in MediaStore after the delete request; it was not verified as deleted.")
            }
        }
    }
}
