package ai.openclaw.android.node

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handler for UI automation commands from Gateway
 */
class UiAutomationHandler(private val context: Context) {

    companion object {
        private const val TAG = "UiAutomationHandler"
        private const val COMMAND_TIMEOUT_MS = 30000L
    }

    /**
     * Handle UI automation commands
     */
    suspend fun handleCommand(command: String, paramsJson: String?): JSONObject =
        withContext(Dispatchers.Main) {
            try {
                val params = paramsJson?.let { JSONObject(it) } ?: JSONObject()

                when (command) {
                    "ui.hierarchy" -> getHierarchy()
                    "ui.click" -> click(params)
                    "ui.clickByText" -> clickByText(params)
                    "ui.longClickByText" -> longClickByText(params)
                    "ui.input" -> inputText(params)
                    "ui.swipe" -> swipe(params)
                    "ui.scroll" -> scroll(params)
                    "ui.back" -> pressBack()
                    "ui.home" -> pressHome()
                    "ui.recents" -> pressRecents()
                    "ui.notifications" -> openNotifications()
                    "ui.quickSettings" -> openQuickSettings()
                    "ui.currentApp" -> getCurrentApp()
                    "ui.find" -> findElement(params)
                    "ui.openApp" -> openApp(params)
                    "ui.openUrl" -> openUrl(params)
                    "ui.getScreenSize" -> getScreenSize()
                    "ui.performGesture" -> performGesture(params)
                    else -> JSONObject().apply {
                        put("error", "Unknown command: $command")
                        put("availableCommands", getAvailableCommands())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling command: $command", e)
                JSONObject().apply {
                    put("error", e.message ?: "Unknown error")
                    put("command", command)
                }
            }
        }

    private fun getAvailableCommands(): JSONArray {
        return JSONArray().apply {
            listOf(
                "ui.hierarchy - Get current UI hierarchy",
                "ui.click - Click at coordinates (x, y)",
                "ui.clickByText - Click element by text",
                "ui.longClickByText - Long click element by text",
                "ui.input - Input text into focused field",
                "ui.swipe - Swipe from (startX, startY) to (endX, endY)",
                "ui.scroll - Scroll element by text (direction: up/down/left/right)",
                "ui.back - Press back button",
                "ui.home - Press home button",
                "ui.recents - Show recent apps",
                "ui.notifications - Open notifications",
                "ui.quickSettings - Open quick settings",
                "ui.currentApp - Get current foreground app info",
                "ui.find - Find element by text",
                "ui.openApp - Open app by package name",
                "ui.openUrl - Open URL in browser",
                "ui.getScreenSize - Get screen dimensions",
                "ui.performGesture - Perform custom gesture"
            ).forEach { put(it) }
        }
    }

    private fun getHierarchy(): JSONObject {
        val service = UiAutomationService.instance
        return if (service != null) {
            service.getUiHierarchy()
        } else {
            JSONObject().apply {
                put("error", "UI Automation Service not running. Please enable it in accessibility settings.")
            }
        }
    }

    private suspend fun click(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0)
        val y = params.optDouble("y", -1.0)

        if (x < 0 || y < 0) {
            return JSONObject().apply {
                put("error", "Missing x or y coordinates")
            }
        }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val success = service.click(x.toFloat(), y.toFloat())
        return JSONObject().apply {
            put("success", success)
            put("x", x)
            put("y", y)
        }
    }

    private fun clickByText(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val partialMatch = params.optBoolean("partial", true)

        if (text.isEmpty()) {
            return JSONObject().apply {
                put("error", "Missing text parameter")
            }
        }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val node = service.findNodeByText(text, partialMatch)
        return if (node != null) {
            val success = service.clickNode(node)
            JSONObject().apply {
                put("success", success)
                put("text", text)
                put("found", true)
                node.recycle()
            }
        } else {
            JSONObject().apply {
                put("success", false)
                put("error", "Element not found: $text")
            }
        }
    }

    private fun longClickByText(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val partialMatch = params.optBoolean("partial", true)

        if (text.isEmpty()) {
            return JSONObject().apply {
                put("error", "Missing text parameter")
            }
        }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val node = service.findNodeByText(text, partialMatch)
        return if (node != null) {
            val success = service.longClickNode(node)
            JSONObject().apply {
                put("success", success)
                put("text", text)
                node.recycle()
            }
        } else {
            JSONObject().apply {
                put("success", false)
                put("error", "Element not found: $text")
            }
        }
    }

    private fun inputText(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val targetText = params.optString("targetText", "")

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        return if (targetText.isNotEmpty()) {
            // Find specific element
            val node = service.findNodeByText(targetText, true)
            if (node != null) {
                val success = service.inputText(node, text)
                JSONObject().apply {
                    put("success", success)
                    put("targetText", targetText)
                    node.recycle()
                }
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Target element not found: $targetText")
                }
            }
        } else {
            // Input to currently focused element
            val root = service.rootInActiveWindow
            if (root != null) {
                val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val success = service.inputText(focusedNode, text)
                    JSONObject().apply {
                        put("success", success)
                        put("method", "focused")
                    }
                    focusedNode.recycle()
                } else {
                    JSONObject().apply {
                        put("success", false)
                        put("error", "No focused input field found")
                    }
                }
                root.recycle()
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("error", "No active window")
                }
            }
        }
    }

    private suspend fun swipe(params: JSONObject): JSONObject {
        val startX = params.optDouble("startX", -1.0)
        val startY = params.optDouble("startY", -1.0)
        val endX = params.optDouble("endX", -1.0)
        val endY = params.optDouble("endY", -1.0)
        val duration = params.optLong("duration", 300)

        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return JSONObject().apply {
                put("error", "Missing coordinates (startX, startY, endX, endY)")
            }
        }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val success = service.swipe(
            startX.toFloat(), startY.toFloat(),
            endX.toFloat(), endY.toFloat(),
            duration
        )

        return JSONObject().apply {
            put("success", success)
            put("startX", startX)
            put("startY", startY)
            put("endX", endX)
            put("endY", endY)
            put("duration", duration)
        }
    }

    private fun scroll(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val direction = params.optString("direction", "down")

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val node = service.findNodeByText(text, true)
        return if (node != null) {
            val success = when (direction.lowercase()) {
                "up", "forward" -> service.scrollForward(node)
                "down", "backward" -> service.scrollBackward(node)
                else -> service.scrollForward(node)
            }
            JSONObject().apply {
                put("success", success)
                put("direction", direction)
                node.recycle()
            }
        } else {
            JSONObject().apply {
                put("success", false)
                put("error", "Scrollable element not found: $text")
            }
        }
    }

    private fun pressBack(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return JSONObject().apply {
            put("success", service.pressBack())
            put("action", "back")
        }
    }

    private fun pressHome(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return JSONObject().apply {
            put("success", service.pressHome())
            put("action", "home")
        }
    }

    private fun pressRecents(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return JSONObject().apply {
            put("success", service.pressRecentApps())
            put("action", "recents")
        }
    }

    private fun openNotifications(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return JSONObject().apply {
            put("success", service.openNotifications())
            put("action", "notifications")
        }
    }

    private fun openQuickSettings(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return JSONObject().apply {
            put("success", service.openQuickSettings())
            put("action", "quickSettings")
        }
    }

    private fun getCurrentApp(): JSONObject {
        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }
        return service.getCurrentApp()
    }

    private fun findElement(params: JSONObject): JSONObject {
        val text = params.optString("text", "")
        val partialMatch = params.optBoolean("partial", true)

        if (text.isEmpty()) {
            return JSONObject().apply {
                put("error", "Missing text parameter")
            }
        }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        val node = service.findNodeByText(text, partialMatch)
        return if (node != null) {
            JSONObject().apply {
                put("found", true)
                put("text", node.text?.toString() ?: "")
                put("contentDescription", node.contentDescription?.toString() ?: "")
                put("className", node.className?.toString() ?: "")
                put("clickable", node.isClickable)
                put("enabled", node.isEnabled)
                node.recycle()
            }
        } else {
            JSONObject().apply {
                put("found", false)
            }
        }
    }

    private fun openApp(params: JSONObject): JSONObject {
        val packageName = params.optString("packageName", "")

        if (packageName.isEmpty()) {
            return JSONObject().apply {
                put("error", "Missing packageName parameter")
            }
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                JSONObject().apply {
                    put("success", true)
                    put("packageName", packageName)
                }
            } else {
                JSONObject().apply {
                    put("success", false)
                    put("error", "App not found: $packageName")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
        }
    }

    private fun openUrl(params: JSONObject): JSONObject {
        val url = params.optString("url", "")

        if (url.isEmpty()) {
            return JSONObject().apply {
                put("error", "Missing url parameter")
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().apply {
                put("success", true)
                put("url", url)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
        }
    }

    private fun getScreenSize(): JSONObject {
        val displayMetrics = context.resources.displayMetrics
        return JSONObject().apply {
            put("width", displayMetrics.widthPixels)
            put("height", displayMetrics.heightPixels)
            put("density", displayMetrics.density)
            put("densityDpi", displayMetrics.densityDpi)
        }
    }

    private suspend fun performGesture(params: JSONObject): JSONObject {
        // Complex gesture with multiple points
        val points = params.optJSONArray("points")
            ?: return JSONObject().apply {
                put("error", "Missing points array")
            }

        val service = UiAutomationService.instance
            ?: return JSONObject().apply {
                put("error", "UI Automation Service not running")
            }

        // Build path from points
        if (points.length() < 2) {
            return JSONObject().apply {
                put("error", "Need at least 2 points for a gesture")
            }
        }

        val path = android.graphics.Path()
        val firstPoint = points.getJSONObject(0)
        path.moveTo(
            firstPoint.getDouble("x").toFloat(),
            firstPoint.getDouble("y").toFloat()
        )

        for (i in 1 until points.length()) {
            val point = points.getJSONObject(i)
            path.lineTo(
                point.getDouble("x").toFloat(),
                point.getDouble("y").toFloat()
            )
        }

        val duration = params.optLong("duration", 500)

        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        val success = deferred.await()

        return JSONObject().apply {
            put("success", success)
            put("points", points.length())
            put("duration", duration)
        }
    }
}
