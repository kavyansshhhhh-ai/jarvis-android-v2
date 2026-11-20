package app.shilex.android.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.shilex.android.Diag
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val launchActivity: String = "",
    val launchable: Boolean = true,
    val systemApp: Boolean = false,
)

/**
 * Pure A-Z sorting, alias lookup, search, and disambiguation logic for installed apps.
 * Never invents apps that are not in the provided list.
 */
object AppCatalog {
    private val COLLATOR: Collator = Collator.getInstance(Locale.US).apply {
        strength = Collator.PRIMARY
    }

    private val ALIASES = mapOf(
        "yt music" to "youtube music",
        "yt" to "youtube",
        "gpay" to "google pay",
        "g pay" to "google pay",
        "wa" to "whatsapp",
        "whats app" to "whatsapp",
        "insta" to "instagram",
        "fb" to "facebook",
        "tg" to "telegram",
        "maps" to "google maps",
        "chrome" to "google chrome",
    )

    fun sortAlphabetical(apps: List<InstalledAppInfo>): List<InstalledAppInfo> =
        apps.distinctBy { it.packageName }
            .sortedWith { a, b ->
                val cmp = COLLATOR.compare(a.label.trim(), b.label.trim())
                if (cmp != 0) cmp else a.packageName.compareTo(b.packageName)
            }

    fun normalizeQuery(raw: String): String {
        val transliterated = transliterateDevanagari(raw)
        val cleaned = transliterated.lowercase()
            .trim()
            .removePrefix("open ")
            .removePrefix("launch ")
            .removePrefix("start ")
            .removeSuffix(" app")
            .removeSuffix(" kholo")
            .removeSuffix(" khol")
            .replace(Regex("\\s+"), " ")
            .trim()
        return ALIASES[cleaned] ?: cleaned
    }

    /** Strips spaces/punctuation cleanly for space-agnostic matching (e.g., "last wave" == "LastWave"). */
    fun compactKey(raw: String): String = transliterateDevanagari(raw)
        .lowercase()
        .filter { it.isLetterOrDigit() }

    /** Simple phonetic normalization to catch Hindi/Indian-English pronunciation variants like "lost wave" <-> "lastwave". */
    fun normalizePhonetic(raw: String): String {
        val compact = compactKey(raw)
        if (compact.isEmpty()) return ""
        return compact
            .replace("ph", "f")
            .replace("v", "w")
            .replace("z", "s")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("aa", "a")
            .replace(Regex("(.)\\1+"), "$1")
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }

    /** Translates common Devanagari Hindi app queries into ASCII phonetic text cleanly. */
    fun transliterateDevanagari(input: String): String {
        if (input.none { it in '\u0900'..'\u097F' }) return input
        val sb = StringBuilder(input.length * 2)
        for (ch in input) {
            val mapped = DEVANAGARI_MAP[ch]
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    /** Finds best matching apps: exact -> prefix -> contains -> compact -> package -> strict phonetic/fuzzy fallback. */
    fun find(query: String, apps: List<InstalledAppInfo>, includeNonLaunchable: Boolean = false): List<InstalledAppInfo> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptyList()
        val pool = if (includeNonLaunchable) apps else apps.filter { it.launchable }
        val sortedPool = sortAlphabetical(pool)

        val exact = sortedPool.filter { it.label.lowercase().trim() == q }
        if (exact.isNotEmpty()) return exact

        val prefix = sortedPool.filter { it.label.lowercase().trim().startsWith(q) }
        if (prefix.isNotEmpty()) return prefix

        val contains = sortedPool.filter { q in it.label.lowercase() }
        if (contains.isNotEmpty()) return contains

        val pkgMatch = sortedPool.filter { q in it.packageName.lowercase() }
        if (pkgMatch.isNotEmpty()) return pkgMatch

        // Space-agnostic exact match (e.g. "last wave" -> "LastWave", Devanagari "लास्ट वेव" -> "LastWave")
        val qCompact = compactKey(q)
        if (qCompact.length >= 3) {
            val compactExact = sortedPool.filter { compactKey(it.label) == qCompact }
            if (compactExact.isNotEmpty()) return compactExact

            val compactContains = sortedPool.filter { qCompact in compactKey(it.label) }
            if (compactContains.isNotEmpty()) return compactContains
        }

        // Strict phonetic + edit-distance fallback for speech typos (never matches unrelated apps)
        val qPhonetic = normalizePhonetic(q)
        if (qPhonetic.length >= 4) {
            val phoneticExact = sortedPool.filter { normalizePhonetic(it.label) == qPhonetic }
            if (phoneticExact.isNotEmpty()) return phoneticExact

            val scored = sortedPool.mapNotNull { app ->
                val lPhon = normalizePhonetic(app.label)
                if (lPhon.length < 4) return@mapNotNull null
                val maxLen = maxOf(qPhonetic.length, lPhon.length)
                val dist = editDistance(qPhonetic, lPhon)
                val maxAllowed = if (maxLen >= 7) 2 else 1
                val sim = 1.0 - (dist.toDouble() / maxLen)
                if (dist <= maxAllowed && sim >= 0.72) app to dist else null
            }.sortedBy { it.second }
            if (scored.isNotEmpty()) {
                val bestDist = scored.first().second
                return scored.filter { it.second == bestDist }.map { it.first }
            }
        }

        return emptyList()
    }

    fun search(query: String, apps: List<InstalledAppInfo>, includeNonLaunchable: Boolean = false): List<InstalledAppInfo> {
        val q = normalizeQuery(query)
        val sorted = sortAlphabetical(if (includeNonLaunchable) apps else apps.filter { it.launchable })
        if (q.isEmpty()) return sorted
        val qCompact = compactKey(q)
        val direct = sorted.filter { app ->
            val l = app.label.lowercase()
            val p = app.packageName.lowercase()
            q in l || q in p || (qCompact.length >= 3 && qCompact in compactKey(app.label))
        }
        return direct.ifEmpty { find(query, apps, includeNonLaunchable) }
    }

    private val DEVANAGARI_MAP = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ा' to "a", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ं' to "n", 'ँ' to "n",
        '्' to "", '़' to "",
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "w",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )

    fun summarizeAlphabetical(apps: List<InstalledAppInfo>, limit: Int = 80, includeSystem: Boolean = true): String {
        val filtered = sortAlphabetical(apps.filter { it.launchable && (includeSystem || !it.systemApp) })
        if (filtered.isEmpty()) return "No launchable apps were found."
        val shown = filtered.take(limit)
        return buildString {
            append("${filtered.size} installed apps (A–Z):")
            shown.forEach { app ->
                append("\n- ${app.label}")
                if (filtered.count { it.label.equals(app.label, ignoreCase = true) } > 1) {
                    append(" (${app.packageName})")
                }
            }
            if (filtered.size > shown.size) {
                append("\n...and ${filtered.size - shown.size} more apps. Ask to search by name to narrow down.")
            }
        }
    }
}

/**
 * Live installed-app registry backed by [PackageManager] plus a dynamic package change receiver
 * so newly installed/uninstalled apps update automatically without stale caches.
 */
object InstalledAppsRegistry {
    @Volatile private var initialized = false
    @Volatile private var cachedApps: List<InstalledAppInfo> = emptyList()
    private val refreshLock = Any()
    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "installed-apps-index").apply { isDaemon = true }
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    refreshAsync(appContext)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            initialized = true
        }
        refreshAsync(appContext)
    }

    fun refreshAsync(context: Context, onUpdated: ((List<InstalledAppInfo>) -> Unit)? = null) {
        val appContext = context.applicationContext
        refreshExecutor.execute {
            val apps = refresh(appContext)
            onUpdated?.invoke(apps)
        }
    }

    fun cachedApps(): List<InstalledAppInfo> = cachedApps

    fun apps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        if (forceRefresh || cachedApps.isEmpty()) refresh(context.applicationContext)
        return cachedApps
    }

    fun refresh(context: Context): List<InstalledAppInfo> = synchronized(refreshLock) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        val launchMap = LinkedHashMap<String, Pair<String, String>>()
        for (info in launchActivities) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            val activity = info.activityInfo.name.orEmpty()
            if (!launchMap.containsKey(pkg)) {
                launchMap[pkg] = label to activity
            }
        }

        val allApps = LinkedHashMap<String, InstalledAppInfo>()
        val installedPackages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

        for (appInfo in installedPackages) {
            val pkg = appInfo.packageName ?: continue
            val launch = launchMap[pkg]
            val fallbackLaunch = if (launch == null && pm.getLaunchIntentForPackage(pkg) != null) {
                pm.getLaunchIntentForPackage(pkg)?.component?.className.orEmpty()
            } else {
                ""
            }
            val label = launch?.first ?: runCatching { pm.getApplicationLabel(appInfo).toString().trim() }.getOrDefault(pkg)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            allApps[pkg] = InstalledAppInfo(
                label = label.ifEmpty { pkg },
                packageName = pkg,
                launchActivity = launch?.second ?: fallbackLaunch,
                launchable = launch != null || fallbackLaunch.isNotEmpty(),
                systemApp = isSystem,
            )
        }

        // Ensure every resolved launcher activity appears even if omitted from getInstalledApplications on restricted devices.
        for ((pkg, pair) in launchMap) {
            if (!allApps.containsKey(pkg)) {
                allApps[pkg] = InstalledAppInfo(
                    label = pair.first,
                    packageName = pkg,
                    launchActivity = pair.second,
                    launchable = true,
                    systemApp = false,
                )
            }
        }

        val sorted = AppCatalog.sortAlphabetical(allApps.values.toList())
        cachedApps = sorted
        Diag.i("apps: indexed ${sorted.size} installed apps (${sorted.count { it.launchable }} launchable)")
        return sorted
    }
}
