package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Represents a single permission or OS survival requirement shown in the Settings checklist.
 */
data class PermissionRequirement(
    val id: String,
    val title: String,
    val purpose: String,
    val isGranted: Boolean,
    val isCriticalForService: Boolean,
    val runtimePermissionString: String? = null,
    val specialIntentAction: String? = null
)

data class OemBatteryGuide(
    val brandTitle: String,
    val steps: String,
    val isDetectedDeviceBrand: Boolean
)

object PermissionHelper {

    const val ID_MIC = "perm_record_audio"
    const val ID_CALL = "perm_call_phone"
    const val ID_CONTACTS = "perm_read_contacts"
    const val ID_CALL_LOG = "perm_read_call_log"
    const val ID_CAMERA = "perm_camera"
    const val ID_NOTIF = "perm_post_notifications"
    const val ID_OVERLAY = "perm_system_alert_window"
    const val ID_WRITE_SETTINGS = "perm_write_settings"
    const val ID_BATTERY_OPT = "perm_ignore_battery_opt"

    /**
     * Evaluates all runtime and special permissions required by NEMI:
     * RECORD_AUDIO, CALL_PHONE, READ_CONTACTS, READ_CALL_LOG, CAMERA, POST_NOTIFICATIONS,
     * SYSTEM_ALERT_WINDOW (Display over other apps), WRITE_SETTINGS (Brightness),
     * and Battery Optimization exclusion.
     */
    fun evaluatePermissions(context: Context): List<PermissionRequirement> {
        val list = mutableListOf<PermissionRequirement>()

        // 1. RECORD_AUDIO
        list += PermissionRequirement(
            id = ID_MIC,
            title = "Microphone",
            purpose = "Listens for your wake word and voice commands offline",
            isGranted = isGranted(context, Manifest.permission.RECORD_AUDIO),
            isCriticalForService = true,
            runtimePermissionString = Manifest.permission.RECORD_AUDIO
        )

        // 2. POST_NOTIFICATIONS (Android 13+)
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        list += PermissionRequirement(
            id = ID_NOTIF,
            title = "Notifications",
            purpose = "Shows listener status and action confirmations",
            isGranted = hasNotif,
            isCriticalForService = true,
            runtimePermissionString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else null
        )

        // 3. CALL_PHONE
        list += PermissionRequirement(
            id = ID_CALL,
            title = "Phone Calls",
            purpose = "Places phone calls directly when you say 'Call …'",
            isGranted = isGranted(context, Manifest.permission.CALL_PHONE),
            isCriticalForService = false,
            runtimePermissionString = Manifest.permission.CALL_PHONE
        )

        // 4. READ_CONTACTS
        list += PermissionRequirement(
            id = ID_CONTACTS,
            title = "Contacts",
            purpose = "Matches spoken names to your contacts on-device",
            isGranted = isGranted(context, Manifest.permission.READ_CONTACTS),
            isCriticalForService = false,
            runtimePermissionString = Manifest.permission.READ_CONTACTS
        )

        // 5. READ_CALL_LOG
        list += PermissionRequirement(
            id = ID_CALL_LOG,
            title = "Call History",
            purpose = "Picks the most-used number when a contact has multiple numbers",
            isGranted = isGranted(context, Manifest.permission.READ_CALL_LOG),
            isCriticalForService = false,
            runtimePermissionString = Manifest.permission.READ_CALL_LOG
        )

        // 6. CAMERA (Torch)
        list += PermissionRequirement(
            id = ID_CAMERA,
            title = "Camera / Torch",
            purpose = "Needed on some phones to toggle the flashlight",
            isGranted = isGranted(context, Manifest.permission.CAMERA),
            isCriticalForService = false,
            runtimePermissionString = Manifest.permission.CAMERA
        )

        // 7. SYSTEM_ALERT_WINDOW (Section B.5: Display over other apps to start apps from background)
        val canDrawOverlays = Settings.canDrawOverlays(context)
        list += PermissionRequirement(
            id = ID_OVERLAY,
            title = "Display Over Other Apps",
            purpose = "Allows NEMI to open apps ('Open YouTube') from the background",
            isGranted = canDrawOverlays,
            isCriticalForService = false,
            specialIntentAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        )

        // 8. WRITE_SETTINGS (Section C: Brightness up/down)
        val canWriteSettings = Settings.System.canWrite(context)
        list += PermissionRequirement(
            id = ID_WRITE_SETTINGS,
            title = "Modify System Settings",
            purpose = "Allows changing screen brightness ('Brightness up/down')",
            isGranted = canWriteSettings,
            isCriticalForService = false,
            specialIntentAction = Settings.ACTION_MANAGE_WRITE_SETTINGS
        )

        // 9. Battery Optimization Exclusion
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOpt = pm.isIgnoringBatteryOptimizations(context.packageName)
        list += PermissionRequirement(
            id = ID_BATTERY_OPT,
            title = "Unrestricted Battery Usage",
            purpose = "Keeps the voice listener active when the screen is off",
            isGranted = isIgnoringBatteryOpt,
            isCriticalForService = true,
            specialIntentAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        )

        return list
    }

    fun verifyZeroInternetPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_DENIED
    }

    fun buildSpecialPermissionIntent(context: Context, action: String): Intent {
        return Intent(action).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getOemBatteryGuides(): List<OemBatteryGuide> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return listOf(
            OemBatteryGuide(
                brandTitle = "Xiaomi / Redmi / POCO",
                steps = "1. App Info → enable 'Autostart'\n2. Battery Saver → select 'No restrictions'\n3. Recents → lock NEMI.",
                isDetectedDeviceBrand = manufacturer.contains("xiaomi") ||
                    manufacturer.contains("redmi") ||
                    manufacturer.contains("poco")
            ),
            OemBatteryGuide(
                brandTitle = "Samsung",
                steps = "1. Settings → Battery → Background usage limits → 'Never sleeping apps' → Add NEMI\n2. App Info → Battery → 'Unrestricted'.",
                isDetectedDeviceBrand = manufacturer.contains("samsung")
            ),
            OemBatteryGuide(
                brandTitle = "Oppo / Vivo / Realme / OnePlus",
                steps = "1. App Info → Battery usage → enable 'Allow background activity' & 'Allow auto-launch'.",
                isDetectedDeviceBrand = manufacturer.contains("oppo") ||
                    manufacturer.contains("vivo") ||
                    manufacturer.contains("realme") ||
                    manufacturer.contains("oneplus")
            )
        )
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
