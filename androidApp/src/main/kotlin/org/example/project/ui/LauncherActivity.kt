package org.example.project.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.example.project.accessibility.GuidanceAccessibilityService
import org.example.project.capture.CaptureLogger
import org.example.project.guidance.FeatureFlags
import org.example.project.guidance.GuidanceTask
import org.example.project.guidance.Tasks
import org.example.project.guidance.VoiceTaskMatcher
import org.example.project.speech.SpeechController
import org.example.project.speech.VoiceTaskRecognizer

class LauncherActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private lateinit var voiceRecognizer: VoiceTaskRecognizer
    private lateinit var announcer: SpeechController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        voiceRecognizer = VoiceTaskRecognizer(this)
        announcer = SpeechController(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (FeatureFlags.voiceTaskSelectionEnabled) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            LauncherScreen(
                tasks = Tasks.allTasks,
                isServiceEnabled = { isAccessibilityServiceEnabled(this) },
                onOpenAccessibilitySettings = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onStartTask = ::startTask,
                onStopTask = { GuidanceAccessibilityService.instance?.stopTask() },
                onSetCaptureMode = { enabled -> GuidanceAccessibilityService.captureModeEnabled = enabled },
                adbPullCommand = CaptureLogger.adbPullCommand(this),
                voiceEnabled = FeatureFlags.voiceTaskSelectionEnabled,
                onMicTapped = { onDone -> startVoiceTaskSelection(onDone) },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceRecognizer.destroy()
        announcer.shutdown()
    }

    /** Listens for one utterance, matches it against the task list, starts a task if confident. */
    private fun startVoiceTaskSelection(onDone: () -> Unit) {
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            announcer.speak("Microphone permission is needed for voice commands.")
            onDone()
            return
        }

        voiceRecognizer.listenOnce { transcript ->
            val matchedTask = transcript?.let { VoiceTaskMatcher.match(it, Tasks.allTasks) }
            if (matchedTask != null) {
                startTask(matchedTask)
            } else {
                announcer.speak(noMatchMessage())
            }
            // Re-enables the mic button (see LauncherScreen's isListening) so a follow-up
            // attempt after this message works exactly like the first one, not a one-shot mic.
            onDone()
        }
    }

    /** Built from the real task list, not hardcoded, so it can't go stale as tasks are added. */
    private fun noMatchMessage(): String {
        val titles = Tasks.allTasks.joinToString(" or ") { it.title.lowercase() }
        return "As of now, I can only help with: $titles. Try saying that, or tap a card."
    }

    private fun startTask(task: GuidanceTask) {
        GuidanceAccessibilityService.instance?.startTask(task)
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
