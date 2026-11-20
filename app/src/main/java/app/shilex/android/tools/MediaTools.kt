package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenElement
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.PersonalProfileResolver
import app.shilex.android.data.PersonalProfileStore
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.notify.NotificationWatch
import app.shilex.android.task.ActiveTaskContext
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Pure music track matching engine (JVM unit-tested without Android).
 * Scores song title and artist as independent signals, rejects misleading letter-similar
 * tracks like "Lost In Wave" for "Tu Hi Mera", and flags genuine ambiguity.
 */
object MusicTrackMatcher {
    data class TrackCandidate(
        val elementNumber: Int,
        val title: String,
        val artist: String = "",
        val rawLabel: String = title,
    )

    sealed interface MatchOutcome {
        data class Matched(val candidate: TrackCandidate, val score: Int) : MatchOutcome
        data class Ambiguous(val candidates: List<TrackCandidate>) : MatchOutcome
        data object None : MatchOutcome
    }

    private val FILLER_TOKENS = setOf("song", "gana", "gaana", "track", "play", "audio", "official", "video", "lyrics")

    fun tokens(text: String): List<String> = AppCatalog.transliterateDevanagari(text)
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in FILLER_TOKENS }

    fun compact(text: String): String = tokens(text).joinToString("")

    /** Separates "Tu Hi Mera, Pritam" or uses explicit [artistHint] if provided. */
    fun splitQuery(query: String, artistHint: String = ""): Pair<String, String> {
        if (artistHint.isNotBlank()) return query.trim() to artistHint.trim()
        val parts = query.split(Regex("""\s*(?:,|by|-|\|)\s*""", RegexOption.IGNORE_CASE), limit = 2)
        return if (parts.size == 2) {
            parts[0].trim() to parts[1].replace(Regex("""\b(wala|ka|ki|song|gaana|gana)\b""", RegexOption.IGNORE_CASE), "").trim()
        } else {
            query.trim() to ""
        }
    }

    fun bestMatch(
        wantedTitle: String,
        wantedArtist: String,
        candidates: List<TrackCandidate>,
    ): MatchOutcome {
        val wantedTitleTokens = tokens(wantedTitle)
        val wantedArtistTokens = tokens(wantedArtist)
        if (wantedTitleTokens.isEmpty()) return MatchOutcome.None
        val wantedCompact = wantedTitleTokens.joinToString("")
        val artistCompact = wantedArtistTokens.joinToString("")

        val scored = candidates.mapNotNull { cand ->
            val candTitleTokens = tokens(cand.title)
            val candArtistTokens = tokens(cand.artist + " " + cand.rawLabel)
            if (candTitleTokens.isEmpty()) return@mapNotNull null

            val exactTitleMatch = candTitleTokens.joinToString("") == wantedCompact
            val matchedTitleCount = wantedTitleTokens.count { wt ->
                candTitleTokens.any { ct -> ct == wt || (wt.length >= 4 && ct.startsWith(wt)) }
            }
            val titleCoverage = matchedTitleCount.toFloat() / wantedTitleTokens.size.toFloat()
            // Strict rejection of unrelated titles with superficial character overlap (e.g. "Lost In Wave" vs "Tu Hi Mera")
            if (!exactTitleMatch && titleCoverage < 0.65f) return@mapNotNull null

            var score = (titleCoverage * 100).toInt()
            if (exactTitleMatch) score += 60
            // Extra precision penalty when candidate contains lots of unrelated extra title tokens
            val extraTokens = (candTitleTokens.size - wantedTitleTokens.size).coerceAtLeast(0)
            score -= extraTokens * 8

            if (wantedArtistTokens.isNotEmpty()) {
                val matchedArtistCount = wantedArtistTokens.count { at ->
                    candArtistTokens.any { ct -> ct == at || (at.length >= 4 && ct.startsWith(at)) }
                }
                val artistCoverage = matchedArtistCount.toFloat() / wantedArtistTokens.size.toFloat()
                if (candArtistTokens.joinToString("") == artistCompact && artistCompact.isNotEmpty()) {
                    score += 70
                } else if (artistCoverage >= 0.5f) {
                    score += (artistCoverage * 50).toInt()
                } else {
                    score -= 20
                }
            }

            if (score >= 60) cand to score else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return MatchOutcome.None
        val topScore = scored.first().second
        val topTitle = scored.first().first.title.lowercase()
        val topCandidates = scored.filter { topScore - it.second <= 5 && it.first.title.lowercase() == topTitle }.map { it.first }
        val distinctArtists = topCandidates.map { it.artist.lowercase() }.filter { it.isNotBlank() }.distinct()
        return if (distinctArtists.size > 1 && wantedArtistTokens.isEmpty()) {
            MatchOutcome.Ambiguous(topCandidates)
        } else {
            MatchOutcome.Matched(scored.first().first, topScore)
        }
    }

    /** Identifies likely Search input field number on a music app screen. */
    fun findSearchFieldNumber(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): Int? {
        return elements.firstOrNull { el ->
            el.role == "field" ||
                ("search" in el.label.lowercase() || "search" in meta[el.number]?.viewId.orEmpty().lowercase())
        }?.number
    }

    /** Extracts track candidates from visible Accessibility elements (Play buttons & Song rows). */
    fun candidatesFromElements(elements: List<ScreenElement>, meta: Map<Int, ControlService.NodeMeta>): List<TrackCandidate> {
        return elements.mapNotNull { el ->
            val lbl = el.label.trim()
            val viewId = meta[el.number]?.viewId.orEmpty()
            if (lbl.isEmpty() || "mini_player" in viewId || "miniplayer" in viewId.lowercase()) return@mapNotNull null
            val cleaned = lbl
                .removePrefix("Play ")
                .removePrefix("play ")
                .removePrefix("Shuffle ")
                .trim()
            if (el.role == "button" || "Song" in lbl || "track" in viewId.lowercase() || "title" in viewId.lowercase()) {
                val parts = cleaned.split("•", "·", "-", "—").map { it.trim() }.filter { it.isNotEmpty() }
                val title = parts.firstOrNull() ?: cleaned
                val artist = parts.drop(1).joinToString(" ")
                TrackCandidate(el.number, title, artist, lbl)
            } else {
                null
            }
        }
    }
}

/**
 * Play a named song (or saved favourite song) in ANY requested installed music app
 * (such as LastWave, Spotify, YouTube Music, etc.) WITHOUT silently substituting another app.
 */
object MediaTools {
    fun all(): List<PhoneTool> = listOf(PlaySong, MediaControl)

    private val KNOWN_MUSIC_PACKAGES = mapOf(
        "youtube_music" to ("com.google.android.apps.youtube.music" to "YouTube Music"),
        "spotify" to ("com.spotify.music" to "Spotify"),
    )

    private object PlaySong : PhoneTool {
        override val name = "play_song"

        override fun declaration() = Schema.function(
            name,
            "Searches and plays a song/artist in the requested music app (e.g. LastWave, Spotify, YouTube Music, or any installed music app) or the user's saved Personal Profile favorite. Strictly uses the requested app and never substitutes YouTube Music/Spotify without asking.",
            Schema.string("query", "Song title, artist, or 'my favourite song'."),
            Schema.string("artist", "Artist name if the user mentioned one, e.g. Pritam."),
            Schema.string("app", "Exact requested music app name (e.g. LastWave, Spotify, YouTube Music). Empty uses saved profile or active music app context."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.MediaPlayback)) {
                return Results.failed("automation_disabled", "Media playback automation is disabled in Settings → Permissions.")
            }

            val profile = PersonalProfileStore(host.context).load()
            val rawQuery = args.text("query")
            val resolvedQuery = PersonalProfileResolver.resolveMusicQuery(rawQuery, profile)
            if (resolvedQuery.isNullOrBlank()) {
                return Results.failed(
                    "missing_favorite_song",
                    "No favourite song is saved in Personal Profile yet. Ask which song they would like to play.",
                )
            }
            val (titlePart, artistPart) = MusicTrackMatcher.splitQuery(resolvedQuery, args.text("artist"))

            // Context continuity: if user already opened LastWave (or another music app) right before "ab Tu Hi Mera search karo"
            val explicitApp = args.text("app").ifEmpty {
                val s = ActiveTaskContext.snapshot()
                if (s.isActive && (s.currentTask.contains("Open App", ignoreCase = true) || s.currentTask.contains("Music", ignoreCase = true))) {
                    s.currentApplication
                } else {
                    ""
                }
            }

            val appKey = PersonalProfileResolver.resolveMusicAppKey(explicitApp, profile)
            val catalog = InstalledAppsRegistry.apps(host.context, forceRefresh = true)

            val resolvedTarget = resolveMusicTarget(host, appKey, explicitApp, catalog)
            if (resolvedTarget.errorJson != null) return resolvedTarget.errorJson

            val targetPkg = resolvedTarget.packageName
            val targetLabel = resolvedTarget.label

            ActiveTaskContext.beginTask(
                task = "Play Music",
                application = targetLabel,
                appPackage = targetPkg,
                searchQuery = listOf(titlePart, artistPart).filter { it.isNotBlank() }.joinToString(" "),
                executionStep = "Open & search in $targetLabel",
            )

            val searchQueryFull = listOf(titlePart, artistPart).filter { it.isNotBlank() }.joinToString(" ")
            val mediaSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, searchQueryFull)
                .setPackage(targetPkg)
            val supportsMediaSearch = mediaSearchIntent.resolveActivity(host.context.packageManager) != null
            val launchIntent = host.context.packageManager.getLaunchIntentForPackage(targetPkg)

            // Only allow browser YouTube Music fallback when NO specific installed app failed and YouTube Music itself wasn't installed
            val intentToOpen = when {
                supportsMediaSearch -> mediaSearchIntent
                launchIntent != null -> launchIntent
                else -> return Results.failed(
                    "no_launch",
                    "$targetLabel is installed ($targetPkg) but does not expose an openable launcher or search screen. Ask if they want another music app.",
                )
            }

            val control = ControlService.instance?.takeIf { !it.overlay.stopped }
            if (control == null) {
                return host.openApp(
                    intentToOpen,
                    targetLabel,
                    "$targetLabel will open after you speak. Phone control is off, so the user searches and taps Play on \"$titlePart\".",
                )
            }

            return host.openApp(
                intentToOpen,
                targetLabel,
                "Opening $targetLabel to find \"$searchQueryFull\" and start playback.",
            ) {
                driveMusicPlayback(
                    service = control,
                    host = host,
                    pkg = targetPkg,
                    appLabel = targetLabel,
                    wantedTitle = titlePart,
                    wantedArtist = artistPart,
                    alreadySearchedByIntent = supportsMediaSearch,
                )
            }
        }

        private data class TargetResolution(
            val packageName: String = "",
            val label: String = "",
            val errorJson: JSONObject? = null,
        )

        private fun resolveMusicTarget(
            host: ToolHost,
            appKey: String,
            rawExplicit: String,
            catalog: List<InstalledAppInfo>,
        ): TargetResolution {
            // 1. User explicitly asked for a specific app (e.g. LastWave, Spotify, YouTube Music)
            if (rawExplicit.isNotBlank()) {
                val known = KNOWN_MUSIC_PACKAGES[appKey]
                if (known != null) {
                    val installed = catalog.firstOrNull { it.packageName == known.first && it.launchable }
                    return if (installed != null) {
                        TargetResolution(installed.packageName, installed.label)
                    } else {
                        TargetResolution(
                            errorJson = Results.failed(
                                "app_not_installed",
                                "${known.second} is not installed on this phone. Ask whether the user wants another installed music app instead—do not substitute silently.",
                            ),
                        )
                    }
                }
                // Arbitrary named music app (e.g. LastWave)
                val matches = AppCatalog.find(rawExplicit, catalog, includeNonLaunchable = false)
                if (matches.isNotEmpty()) {
                    val chosen = matches.first()
                    return TargetResolution(chosen.packageName, chosen.label)
                }
                val nonLaunchable = AppCatalog.find(rawExplicit, catalog, includeNonLaunchable = true)
                return if (nonLaunchable.isNotEmpty()) {
                    TargetResolution(
                        errorJson = Results.failed(
                            "no_launch",
                            "${nonLaunchable.first().label} is installed without a launchable UI screen.",
                        ),
                    )
                } else {
                    TargetResolution(
                        errorJson = Results.failed(
                            "app_not_installed",
                            "\"$rawExplicit\" was not found among installed apps on this phone. Ask which music app they want to use instead.",
                        ),
                    )
                }
            }

            // 2. Profile default app or default fallback when user did NOT name any app
            val preferredKey = appKey.ifEmpty { "youtube_music" }
            val known = KNOWN_MUSIC_PACKAGES[preferredKey]
            if (known != null) {
                val installed = catalog.firstOrNull { it.packageName == known.first && it.launchable }
                if (installed != null) return TargetResolution(installed.packageName, installed.label)
                return TargetResolution(
                    errorJson = Results.failed(
                        "app_not_installed",
                        "${known.second} is not installed on this phone. Ask which music app they'd like to use.",
                    ),
                )
            }
            val profileMatches = AppCatalog.find(preferredKey, catalog, includeNonLaunchable = false)
            return if (profileMatches.isNotEmpty()) {
                TargetResolution(profileMatches.first().packageName, profileMatches.first().label)
            } else {
                TargetResolution(
                    errorJson = Results.failed(
                        "app_not_installed",
                        "Preferred music app \"$preferredKey\" is not installed. Ask which music app to use.",
                    ),
                )
            }
        }

        private suspend fun driveMusicPlayback(
            service: ControlService,
            host: ToolHost,
            pkg: String,
            appLabel: String,
            wantedTitle: String,
            wantedArtist: String,
            alreadySearchedByIntent: Boolean,
        ): String? {
            if (!service.waitFor(pkg, OPEN_WAIT_MS)) {
                return "$appLabel didn't open in time; tell the user it could not launch cleanly."
            }

            var collapsedPlayer = false
            var typedSearch = alreadySearchedByIntent

            repeat(RESULT_TRIES) { attempt ->
                val snap = service.snapshot()
                if (snap != null && snap.packageName == pkg) {
                    // Collapse full-screen currently playing sheet once if hiding search/results
                    val playerToggle = if (!collapsedPlayer) service.findFirst {
                        "audio_video_switch_toggle" in it.viewIdResourceName.orEmpty()
                    } else null
                    if (playerToggle != null) {
                        service.releaseNode(playerToggle)
                        collapsedPlayer = true
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(700)
                        return@repeat
                    }

                    // If we launched the app directly (e.g. LastWave without MEDIA_PLAY_FROM_SEARCH), find & type in search bar
                    if (!typedSearch) {
                        val searchFieldNum = MusicTrackMatcher.findSearchFieldNumber(snap.elements, snap.meta)
                        val node = searchFieldNum?.let(service::node)
                        if (node != null) {
                            if (node.isEditable) {
                                val searchText = listOf(wantedTitle, wantedArtist).filter { it.isNotBlank() }.joinToString(" ")
                                if (service.type(node, searchText)) {
                                    typedSearch = true
                                    delay(900)
                                    return@repeat
                                }
                            } else {
                                service.releaseNode(node)
                            }
                        } else {
                            // Try tapping Search icon/tab first to reveal the input field
                            val searchTab = snap.elements.firstOrNull { el ->
                                el.role == "button" && "search" in el.label.lowercase()
                            }?.let { service.node(it.number) }
                            if (searchTab != null) {
                                service.click(searchTab)
                                delay(600)
                                return@repeat
                            }
                        }
                    }

                    // Look for accurate track match (rejecting similar-looking wrong songs!)
                    val candidates = MusicTrackMatcher.candidatesFromElements(snap.elements, snap.meta)
                    when (val matched = MusicTrackMatcher.bestMatch(wantedTitle, wantedArtist, candidates)) {
                        is MusicTrackMatcher.MatchOutcome.Ambiguous -> {
                            val options = matched.candidates.take(4).joinToString(", ") { "${it.title} (${it.artist})" }
                            return "Multiple matching tracks appeared in $appLabel: $options. Ask the user which one to play."
                        }
                        is MusicTrackMatcher.MatchOutcome.Matched -> {
                            val node = service.node(matched.candidate.elementNumber)
                            if (node != null && service.click(node)) {
                                service.overlay.showActive()
                                delay(PLAY_VERIFY_WAIT_MS)
                                val confirmed = isPlaybackConfirmed(host.context, service, pkg)
                                return if (confirmed) {
                                    Diag.i("music: verified playing ${matched.candidate.title} in $appLabel")
                                    host.endSessionSoon()
                                    null
                                } else {
                                    "Selected \"${matched.candidate.title}\" in $appLabel, though active playback isn't confirmed yet. Ask the user to check $appLabel."
                                }
                            }
                        }
                        MusicTrackMatcher.MatchOutcome.None -> {
                            // If YT Music / standard "Play <title>" button exists directly for exact query
                            if (attempt >= SONG_ROW_AFTER && candidates.isEmpty()) {
                                val fallbackNode = service.findFirst { n ->
                                    val desc = n.contentDescription?.toString().orEmpty()
                                    n.isClickable && n.packageName == pkg &&
                                        desc.startsWith("Play ", ignoreCase = true) &&
                                        wantedTitle.lowercase().split(" ").firstOrNull().orEmpty().let { tok ->
                                            tok.length >= 3 && tok in desc.lowercase()
                                        }
                                }
                                if (fallbackNode != null && service.click(fallbackNode)) {
                                    delay(PLAY_VERIFY_WAIT_MS)
                                    if (isPlaybackConfirmed(host.context, service, pkg)) {
                                        host.endSessionSoon()
                                        return null
                                    }
                                }
                            }
                        }
                    }
                }
                delay(500)
            }

            Diag.w("music: couldn't verify exact track \"$wantedTitle\" in $appLabel")
            return "$appLabel opened, but JARVIS couldn't find and start a verified match for \"$wantedTitle\". Tell the user honestly rather than claiming it's playing."
        }

        private const val OPEN_WAIT_MS = 8_000L
        private const val RESULT_TRIES = 14
        private const val SONG_ROW_AFTER = 6
        private const val PLAY_VERIFY_WAIT_MS = 1_100L
    }

    private object MediaControl : PhoneTool {
        override val name = "media_control"

        override fun declaration() = Schema.function(
            name,
            "Controls playback (play, pause, next, previous) and verifies active audio state honestly.",
            Schema.string("action", "What to do.", required = true, choices = KEYS.keys.toList()),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val action = args.text("action")
            val key = KEYS[action] ?: return Results.failed("bad_action", "Ask whether to play, pause or skip.")
            val audio = host.context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            delay(350)
            val active = audio.isMusicActive
            if (action != "pause" && active) host.endSessionSoon()
            return when (action) {
                "pause" -> if (!active) {
                    Results.done("Paused media playback (verified audio stopped).")
                } else {
                    Results.done("Sent Pause command; active audio stream is still reporting busy.")
                }
                "play" -> if (active) {
                    Results.done("Resumed playback (verified active audio).")
                } else {
                    Results.done("Sent Play command, though no active playback stream is confirmed yet.")
                }
                else -> Results.done("Sent $action command to the media session.")
            }
        }

        private val KEYS = mapOf(
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }

    /** Verifies whether music is genuinely playing via MediaSessionManager, AudioManager, or UI Pause state. */
    fun isPlaybackConfirmed(context: Context, service: ControlService, pkg: String): Boolean {
        val activeSession = runCatching {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(context, NotificationWatch::class.java)
            msm?.getActiveSessions(comp)?.any { controller ->
                controller.packageName == pkg &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
            } == true
        }.getOrDefault(false)
        if (activeSession) return true

        val audioActive = runCatching {
            context.getSystemService(AudioManager::class.java)?.isMusicActive == true
        }.getOrDefault(false)
        if (audioActive) return true

        val snap = service.snapshot() ?: return false
        return snap.packageName == pkg && snap.elements.any { el ->
            el.role == "button" && el.label.trim().equals("Pause", ignoreCase = true)
        }
    }
}
