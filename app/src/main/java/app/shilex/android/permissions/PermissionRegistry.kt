package app.shilex.android.permissions

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.shilex.android.control.ControlService
import app.shilex.android.notify.NotificationWatch

enum class PermissionCategory {
    Runtime,
    SpecialSetting,
    SystemPicker,
}

enum class PermissionStatus(val label: String) {
    Granted("Granted"),
    NotGranted("Not granted"),
    NeedsSetup("Needs setup"),
    RestrictedByAndroid("Restricted by Android"),
    NotRequired("Not required"),
}

enum class PermissionActionKind {
    AllowRuntime,
    OpenSettings,
    ManageSettings,
    None,
}

enum class PermissionId {
    Microphone,
    Notifications,
    Contacts,
    PhoneCalls,
    GalleryMedia,
    Calendar,
    AccessibilityControl,
    NotificationAccess,
    Overlay,
    BatteryOptimization,
    ExactAlarms,
    FilesAndPhotosPicker,
}

data class PermissionSpec(
    val id: PermissionId,
    val title: String,
    val category: PermissionCategory,
    val reason: String,
    val dependentFeature: String,
    val manifestPermission: String? = null,
    val minApi: Int = Build.VERSION_CODES.Q,
    val essential: Boolean = false,
)

data class PermissionStateItem(
    val spec: PermissionSpec,
    val status: PermissionStatus,
    val previouslyGranted: Boolean = false,
    val deniedBefore: Boolean = false,
    val permanentlyDenied: Boolean = false,
    val revoked: Boolean = false,
    val restrictedByAndroid: Boolean = false,
) {
    val isReady: Boolean
        get() = status == PermissionStatus.Granted || status == PermissionStatus.NotRequired

    val actionKind: PermissionActionKind
        get() = when (status) {
            PermissionStatus.Granted -> PermissionActionKind.ManageSettings
            PermissionStatus.NotGranted ->
                if (spec.category == PermissionCategory.Runtime && !permanentlyDenied && !restrictedByAndroid) {
                    PermissionActionKind.AllowRuntime
                } else {
                    PermissionActionKind.OpenSettings
                }
            PermissionStatus.NeedsSetup, PermissionStatus.RestrictedByAndroid ->
                PermissionActionKind.OpenSettings
            PermissionStatus.NotRequired -> PermissionActionKind.None
        }

    val actionLabel: String
        get() = when (actionKind) {
            PermissionActionKind.AllowRuntime -> "Allow"
            PermissionActionKind.OpenSettings -> "Open Settings"
            PermissionActionKind.ManageSettings -> "Manage"
            PermissionActionKind.None -> ""
        }

    /** Actionable status explanation without fake grants or vague placeholders. */
    val statusDetail: String
        get() = when {
            status == PermissionStatus.Granted -> spec.reason
            status == PermissionStatus.NotRequired -> spec.reason
            restrictedByAndroid || status == PermissionStatus.RestrictedByAndroid ->
                "Restricted by Android policy or device settings. ${spec.dependentFeature} is unavailable until enabled in Android Settings."
            permanentlyDenied ->
                "Denied in Android so the popup cannot appear again. ${spec.dependentFeature} needs this enabled in App Settings."
            revoked ->
                "Previously allowed, then turned off in Android Settings. ${spec.dependentFeature} needs it enabled again."
            deniedBefore ->
                "Not granted yet. ${spec.dependentFeature} needs this permission; unrelated features keep working."
            spec.category == PermissionCategory.SpecialSetting ->
                "Needs setup in Android Settings. ${spec.reason}"
            else -> spec.reason
        }
}

data class PermissionSnapshot(
    val items: List<PermissionStateItem>,
) {
    val deduplicatedItems: List<PermissionStateItem> = items.distinctBy { it.spec.id }

    val revokedItems: List<PermissionStateItem>
        get() = deduplicatedItems.filter { it.revoked }

    val deniedItems: List<PermissionStateItem>
        get() = deduplicatedItems.filter { it.deniedBefore || it.permanentlyDenied || it.revoked }

    val permanentlyDeniedItems: List<PermissionStateItem>
        get() = deduplicatedItems.filter { it.permanentlyDenied }

    val missingRuntime: List<PermissionStateItem>
        get() = deduplicatedItems.filter {
            it.spec.category == PermissionCategory.Runtime &&
                it.actionKind == PermissionActionKind.AllowRuntime
        }

    val unpromptedRuntime: List<PermissionStateItem>
        get() = missingRuntime.filter { !it.deniedBefore && !it.permanentlyDenied }

    val specialPending: List<PermissionStateItem>
        get() = deduplicatedItems.filter {
            it.spec.category == PermissionCategory.SpecialSetting && !it.isReady
        }

    val essentialPending: List<PermissionStateItem>
        get() = deduplicatedItems.filter { it.spec.essential && !it.isReady }

    val grantedCount: Int
        get() = deduplicatedItems.count { it.status == PermissionStatus.Granted }

    val actionableTotalCount: Int
        get() = deduplicatedItems.count { it.status != PermissionStatus.NotRequired }

    val readyCount: Int
        get() = deduplicatedItems.count { it.isReady }

    val totalCount: Int
        get() = deduplicatedItems.size

    companion object {
        fun from(items: List<PermissionStateItem>): PermissionSnapshot =
            PermissionSnapshot(items.distinctBy { it.spec.id })
    }
}

data class OnboardingPermissionSummary(
    val essentialGranted: Int,
    val essentialTotal: Int,
    val optionalGranted: Int,
    val optionalTotal: Int,
    val nextRuntimeBatch: List<PermissionStateItem>,
    val nextSpecialSetting: PermissionStateItem?,
    val blockedOrDeniedItems: List<PermissionStateItem>,
    val canContinue: Boolean,
) {
    val allEssentialReady: Boolean get() = essentialGranted >= essentialTotal
    val allActionableReady: Boolean get() = essentialGranted >= essentialTotal && optionalGranted >= optionalTotal
}

/**
 * Pure onboarding checklist & step-by-step guidance analyzer derived strictly from [PermissionSnapshot].
 */
object OnboardingPermissionGuide {
    fun analyze(snapshot: PermissionSnapshot): OnboardingPermissionSummary {
        val actionable = snapshot.deduplicatedItems.filter { it.status != PermissionStatus.NotRequired }
        val essential = actionable.filter { it.spec.essential }
        val optional = actionable.filter { !it.spec.essential }

        val nextRuntime = snapshot.unpromptedRuntime.ifEmpty { emptyList() }
        val specialSettings = actionable.filter { it.spec.category == PermissionCategory.SpecialSetting }
        val nextSpecial = specialSettings.firstOrNull { it.spec.essential && !it.isReady && it.actionKind == PermissionActionKind.OpenSettings }
            ?: specialSettings.firstOrNull { !it.isReady && it.actionKind == PermissionActionKind.OpenSettings }

        return OnboardingPermissionSummary(
            essentialGranted = essential.count { it.status == PermissionStatus.Granted },
            essentialTotal = essential.size,
            optionalGranted = optional.count { it.status == PermissionStatus.Granted },
            optionalTotal = optional.size,
            nextRuntimeBatch = nextRuntime,
            nextSpecialSetting = nextSpecial,
            blockedOrDeniedItems = snapshot.deniedItems + snapshot.permanentlyDeniedItems.filter { !it.deniedBefore && !it.revoked },
            // Optional or denied permissions must never lock the user out of JARVIS.
            canContinue = true,
        )
    }
}

/**
 * Single authoritative source of truth for JARVIS permissions:
 * runtime permissions, special Android settings, exact alarms, battery exemption,
 * and on-demand Photo Picker / SAF (never requesting broad storage permissions).
 */
object PermissionRegistry {
    fun specs(sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionSpec> = listOf(
        PermissionSpec(
            id = PermissionId.Microphone,
            title = "Microphone",
            category = PermissionCategory.Runtime,
            reason = "Needed when you tap Talk or enable local \"Hey Jarvis\" wake listening.",
            dependentFeature = "Voice conversation & wake-word detection",
            manifestPermission = Manifest.permission.RECORD_AUDIO,
            essential = true,
        ),
        PermissionSpec(
            id = PermissionId.Notifications,
            title = "Notifications",
            category = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) PermissionCategory.Runtime else PermissionCategory.SpecialSetting,
            reason = "Shows Android's required visible listening indicator, reminders, and app alerts.",
            dependentFeature = "Wake standby notification & reminders",
            manifestPermission = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
            minApi = Build.VERSION_CODES.Q,
            essential = true,
        ),
        PermissionSpec(
            id = PermissionId.Contacts,
            title = "Contacts",
            category = PermissionCategory.Runtime,
            reason = "Looks up the person you ask to call, SMS, or message on WhatsApp.",
            dependentFeature = "Calling & messaging by contact name",
            manifestPermission = Manifest.permission.READ_CONTACTS,
        ),
        PermissionSpec(
            id = PermissionId.PhoneCalls,
            title = "Phone Calls",
            category = PermissionCategory.Runtime,
            reason = "Places normal cellular phone calls directly when you ask (WhatsApp calls use WhatsApp instead).",
            dependentFeature = "Direct phone calling",
            manifestPermission = Manifest.permission.CALL_PHONE,
        ),
        PermissionSpec(
            id = PermissionId.GalleryMedia,
            title = "Gallery Photos",
            category = PermissionCategory.Runtime,
            reason = "Finds a targeted photo in your gallery so JARVIS can ask confirmation before Android's delete dialog.",
            dependentFeature = "Gallery photo lookup & confirmed deletion",
            manifestPermission = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        ),
        PermissionSpec(
            id = PermissionId.Calendar,
            title = "Calendar",
            category = PermissionCategory.Runtime,
            reason = "Checks upcoming events and opens pre-filled Calendar drafts.",
            dependentFeature = "Schedule checks & creating events",
            manifestPermission = Manifest.permission.READ_CALENDAR,
        ),
        PermissionSpec(
            id = PermissionId.AccessibilityControl,
            title = "Phone Control (Accessibility)",
            category = PermissionCategory.SpecialSetting,
            reason = "Lets JARVIS read non-sensitive screens, open Quick Settings/Notification panels, press Send after your confirmation, and assist app steps.",
            dependentFeature = "On-screen assistance, WhatsApp Send & system panels",
        ),
        PermissionSpec(
            id = PermissionId.NotificationAccess,
            title = "Notification Listener",
            category = PermissionCategory.SpecialSetting,
            reason = "Reads incoming notifications in memory so JARVIS can summarize unread messages and reply only when you confirm.",
            dependentFeature = "\"What did I miss?\" & hands-free replies",
        ),
        PermissionSpec(
            id = PermissionId.Overlay,
            title = "Display Over Other Apps",
            category = PermissionCategory.SpecialSetting,
            reason = "Allows a temporary wake indicator or assistant Orb over other apps when JARVIS is active. Nothing stays visible when idle; hidden on PIN, banking, and lock screens.",
            dependentFeature = "Wake indicator dot & Floating Orb",
        ),
        PermissionSpec(
            id = PermissionId.BatteryOptimization,
            title = "Background Running",
            category = PermissionCategory.SpecialSetting,
            reason = "Prevents Android battery saver from killing offline wake-word listening when the app is in background.",
            dependentFeature = "Reliable background wake word & reminders",
        ),
        PermissionSpec(
            id = PermissionId.ExactAlarms,
            title = "Exact Alarms",
            category = PermissionCategory.SpecialSetting,
            reason = "Rings your JARVIS reminders on the exact scheduled minute instead of delaying them.",
            dependentFeature = "On-time reminders",
            minApi = Build.VERSION_CODES.S,
        ),
        PermissionSpec(
            id = PermissionId.FilesAndPhotosPicker,
            title = "Photos & Files",
            category = PermissionCategory.SystemPicker,
            reason = "Uses Android's built-in Photo Picker and Document Picker only for items you select—broad storage access is not required.",
            dependentFeature = "Inspecting photos & files you pick",
        ),
    ).distinctBy { it.id }

    /** Pure status evaluator for deterministic logic and JVM unit tests. */
    fun evaluateItem(
        spec: PermissionSpec,
        isCurrentlyGranted: Boolean,
        wasPreviouslyGranted: Boolean = false,
        wasRequestedBefore: Boolean = false,
        canShowRationale: Boolean = false,
        isRestrictedByPolicy: Boolean = false,
        isRequiredOnDevice: Boolean = true,
    ): PermissionStateItem {
        if (!isRequiredOnDevice || spec.category == PermissionCategory.SystemPicker) {
            return PermissionStateItem(
                spec = spec,
                status = PermissionStatus.NotRequired,
            )
        }

        if (isCurrentlyGranted) {
            return PermissionStateItem(
                spec = spec,
                status = PermissionStatus.Granted,
                previouslyGranted = true,
            )
        }

        if (isRestrictedByPolicy) {
            return PermissionStateItem(
                spec = spec,
                status = PermissionStatus.RestrictedByAndroid,
                previouslyGranted = wasPreviouslyGranted,
                restrictedByAndroid = true,
                revoked = wasPreviouslyGranted,
            )
        }

        return when (spec.category) {
            PermissionCategory.Runtime -> {
                val permDenied = (wasRequestedBefore || wasPreviouslyGranted) && !canShowRationale
                val status = if (permDenied) PermissionStatus.NeedsSetup else PermissionStatus.NotGranted
                PermissionStateItem(
                    spec = spec,
                    status = status,
                    previouslyGranted = wasPreviouslyGranted,
                    deniedBefore = wasRequestedBefore && !wasPreviouslyGranted,
                    permanentlyDenied = permDenied,
                    revoked = wasPreviouslyGranted,
                )
            }
            PermissionCategory.SpecialSetting -> PermissionStateItem(
                spec = spec,
                status = PermissionStatus.NeedsSetup,
                previouslyGranted = wasPreviouslyGranted,
                revoked = wasPreviouslyGranted,
            )
            PermissionCategory.SystemPicker -> PermissionStateItem(
                spec = spec,
                status = PermissionStatus.NotRequired,
            )
        }
    }

    /** Records that the app launched the system permission dialog for each supplied manifest permission. */
    fun markRequested(context: Context, vararg manifestPermissions: String) {
        if (manifestPermissions.isEmpty()) return
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        manifestPermissions.forEach { perm ->
            editor.putBoolean(requestedKey(perm), true)
        }
        editor.apply()
    }

    /** Evaluates live Android permission states and refreshes grant/denial/revocation history. */
    fun snapshot(
        context: Context,
        activity: Activity? = findActivity(context),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): PermissionSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val items = specs(sdkInt).map { spec ->
            val requiredHere = sdkInt >= spec.minApi
            val current = if (requiredHere) isGrantedNow(context, spec.id, sdkInt) else false
            val seenKey = seenKey(spec.id)
            val prevGranted = prefs.getBoolean(seenKey, false)
            if (current && !prevGranted) editor.putBoolean(seenKey, true)

            val perm = spec.manifestPermission
            val requestedBefore = perm != null && prefs.getBoolean(requestedKey(perm), false)
            val canRationale = if (activity != null && perm != null && !current) {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            } else {
                false
            }
            // If Android says we can show rationale, the user has definitely been prompted at least once.
            if (canRationale && perm != null && !requestedBefore) {
                editor.putBoolean(requestedKey(perm), true)
            }
            val effectiveRequestedBefore = requestedBefore || canRationale
            val restrictedByPolicy = requiredHere && !current && isRestrictedByPolicy(context, spec)

            evaluateItem(
                spec = spec,
                isCurrentlyGranted = current,
                wasPreviouslyGranted = prevGranted,
                wasRequestedBefore = effectiveRequestedBefore,
                canShowRationale = canRationale,
                isRestrictedByPolicy = restrictedByPolicy,
                isRequiredOnDevice = requiredHere,
            )
        }
        editor.apply()
        return PermissionSnapshot.from(items)
    }

    fun isGrantedNow(
        context: Context,
        id: PermissionId,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean = when (id) {
        PermissionId.Microphone ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        PermissionId.Notifications ->
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        PermissionId.Contacts ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        PermissionId.PhoneCalls ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        PermissionId.GalleryMedia -> {
            val mediaPerm = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            ContextCompat.checkSelfPermission(context, mediaPerm) == PackageManager.PERMISSION_GRANTED
        }
        PermissionId.Calendar ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        PermissionId.AccessibilityControl ->
            ControlService.instance != null || isAccessibilityServiceEnabledInSystem(context)
        PermissionId.NotificationAccess -> NotificationWatch.allowed(context)
        PermissionId.Overlay -> Settings.canDrawOverlays(context)
        PermissionId.BatteryOptimization ->
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionId.ExactAlarms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sdkInt >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            } else {
                true
            }
        PermissionId.FilesAndPhotosPicker -> true
    }

    private fun isAccessibilityServiceEnabledInSystem(context: Context): Boolean {
        val wanted = ComponentName(context, ControlService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(wanted, ignoreCase = true) }
    }

    private fun isRestrictedByPolicy(context: Context, spec: PermissionSpec): Boolean {
        val perm = spec.manifestPermission ?: return false
        return runCatching {
            context.packageManager.isPermissionRevokedByPolicy(perm, context.packageName)
        }.getOrDefault(false)
    }

    /** Official Android Settings intent for a special permission or denied/revoked runtime permission. */
    fun settingsIntent(context: Context, id: PermissionId): Intent = when (id) {
        PermissionId.AccessibilityControl -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionId.NotificationAccess -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PermissionId.Overlay -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        PermissionId.BatteryOptimization -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionId.ExactAlarms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                appDetailsIntent(context)
            }
        PermissionId.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> appDetailsIntent(context)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

    private fun seenKey(id: PermissionId) = "seen_${id.name}"
    private fun requestedKey(manifestPerm: String) = "req_$manifestPerm"

    private const val PREFS_NAME = "permission_history"
}
