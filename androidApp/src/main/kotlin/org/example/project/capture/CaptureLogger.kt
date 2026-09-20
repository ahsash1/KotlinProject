package org.example.project.capture

import android.content.Context
import android.util.Log
import org.example.project.guidance.ScreenElement
import java.io.File

/**
 * Dumps every screen the user visits while Capture Mode is on, to Logcat
 * under [TAG] and to a file under app-external storage. This is how you
 * gather the real labels/ids to write [org.example.project.guidance.Tasks]
 * for a new target app.
 *
 * File format: one "==== package @ timestamp ====" header line per dump,
 * then one line per element: "text | contentDescription | viewId | bounds | clickable".
 */
object CaptureLogger {
    private const val TAG = "GuidanceCapture"
    private const val FILE_NAME = "guidance_capture.txt"

    fun dump(context: Context, packageName: String, elements: List<ScreenElement>) {
        val header = "==== $packageName @ ${System.currentTimeMillis()} ===="
        Log.i(TAG, header)
        val lines = elements.map(::formatLine)
        lines.forEach { Log.i(TAG, it) }

        try {
            val file = File(context.getExternalFilesDir(null), FILE_NAME)
            val body = buildString {
                appendLine(header)
                lines.forEach { appendLine(it) }
                appendLine()
            }
            file.appendText(body)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write capture file", t)
        }
    }

    private fun formatLine(e: ScreenElement): String {
        val text = sanitize(e.text)
        val desc = sanitize(e.contentDescription)
        val viewId = e.viewId.orEmpty()
        val bounds = "${e.left},${e.top},${e.right},${e.bottom}"
        return "$text | $desc | $viewId | $bounds | ${e.isClickable}"
    }

    /** Keeps one element to one line: embedded newlines (real UI text can contain them) would otherwise split it. */
    private fun sanitize(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').replace('|', '/')

    fun filePath(context: Context): String = File(context.getExternalFilesDir(null), FILE_NAME).absolutePath

    fun adbPullCommand(context: Context): String =
        "adb pull \"${filePath(context)}\" ./guidance_capture.txt"
}
