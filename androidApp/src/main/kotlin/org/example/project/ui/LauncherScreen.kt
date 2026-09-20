package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.example.project.guidance.GuidanceTask

/** Large type, big targets, high contrast -- built for elderly users. */
@Composable
fun LauncherScreen(
    tasks: List<GuidanceTask>,
    isServiceEnabled: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onStartTask: (GuidanceTask) -> Unit,
    onStopTask: () -> Unit,
    onSetCaptureMode: (Boolean) -> Unit,
    adbPullCommand: String,
) {
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled()) }
    var captureMode by remember { mutableStateOf(false) }
    var activeTaskTitle by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isServiceEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF102A43))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(text = "Guidance", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

            if (!serviceEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF7A2E00), MaterialTheme.shapes.medium)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "This app needs the accessibility service turned on before it can help.",
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Text("Open accessibility settings", fontSize = 20.sp)
                    }
                }
            } else {
                Text(text = "What do you need help with?", color = Color.White, fontSize = 22.sp)

                tasks.forEach { task ->
                    Button(
                        onClick = {
                            activeTaskTitle = task.title
                            onStartTask(task)
                        },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                    ) {
                        Text(task.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (activeTaskTitle != null) {
                    Button(
                        onClick = {
                            activeTaskTitle = null
                            onStopTask()
                        },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    ) {
                        Text("STOP", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF334E68))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = captureMode,
                        onCheckedChange = {
                            captureMode = it
                            onSetCaptureMode(it)
                        },
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Capture mode (for retargeting)", color = Color.White, fontSize = 16.sp)
                }
                if (captureMode) {
                    Text(
                        text = "Pull the dump with:\n$adbPullCommand",
                        color = Color(0xFFBCCCDC),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
