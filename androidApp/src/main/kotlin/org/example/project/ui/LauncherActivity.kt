package org.example.project.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.example.project.accessibility.GuidanceAccessibilityService
import org.example.project.capture.CaptureLogger
import org.example.project.guidance.Tasks

class LauncherActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LauncherScreen(
                tasks = Tasks.allTasks,
                isServiceEnabled = { isAccessibilityServiceEnabled(this) },
                onOpenAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onStartTask = { task -> GuidanceAccessibilityService.instance?.startTask(task) },
                onStopTask = { GuidanceAccessibilityService.instance?.stopTask() },
                onSetCaptureMode = { enabled -> GuidanceAccessibilityService.captureModeEnabled = enabled },
                adbPullCommand = CaptureLogger.adbPullCommand(this),
            )
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, GuidanceAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}
