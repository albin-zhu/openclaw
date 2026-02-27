package ai.openclaw.android.node

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * AccessibilityService for UI automation
 * Allows Gateway AI to control the phone UI
 */
class UiAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAutomationService"
        var instance: UiAutomationService? = null
            private set

        // Pending actions queue
        val pendingActions = mutableListOf<UiAction>()
        var lastScreenshot: ByteArray? = null
    }

    data class UiAction(
        val type: String,
        val params: JSONObject,
        val deferred: CompletableDeferred<JSONObject> = CompletableDeferred()
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "UI Automation Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "UI Automation Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log events for debugging
        event?.let {
            Log.d(TAG, "Event: ${AccessibilityEvent.eventTypeToString(it.eventType)}, " +
                "Package: ${it.packageName}, Class: ${it.className}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = serviceInfo.apply {
            // Listen to all events
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SELECTED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info

        Log.i(TAG, "UI Automation Service connected")
    }

    /**
     * Get current UI hierarchy as JSON
     */
    fun getUiHierarchy(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().apply {
            put("error", "No active window")
        }

        return JSONObject().apply {
            put("packageName", root.packageName?.toString() ?: "unknown")
            put("className", root.className?.toString() ?: "unknown")
            put("windowId", root.windowId)
            put("nodes", traverseNode(root))
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo): JSONArray {
        val result = JSONArray()

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val nodeJson = JSONObject().apply {
                    put("id", generateNodeId(child))
                    put("text", child.text?.toString() ?: "")
                    put("contentDescription", child.contentDescription?.toString() ?: "")
                    put("className", child.className?.toString() ?: "")
                    put("packageName", child.packageName?.toString() ?: "")
                    put("viewId", child.viewIdResourceName ?: "")
                    put("clickable", child.isClickable)
                    put("longClickable", child.isLongClickable)
                    put("scrollable", child.isScrollable)
                    put("editable", child.isEditable)
                    put("focusable", child.isFocusable)
                    put("enabled", child.isEnabled)
                    put("checked", child.isChecked)
                    put("selected", child.isSelected)

                    val bounds = Rect()
                    child.getBoundsInScreen(bounds)
                    put("bounds", JSONObject().apply {
                        put("left", bounds.left)
                        put("top", bounds.top)
                        put("right", bounds.right)
                        put("bottom", bounds.bottom)
                        put("width", bounds.width())
                        put("height", bounds.height())
                        put("centerX", bounds.centerX())
                        put("centerY", bounds.centerY())
                    })

                    put("children", traverseNode(child))
                }
                result.put(nodeJson)
                child.recycle()
            }
        }

        return result
    }

    private fun generateNodeId(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "${node.viewIdResourceName ?: node.className ?: "node"}_${bounds.left}_${bounds.top}"
    }

    /**
     * Find node by text or content description
     */
    fun findNodeByText(text: String, partialMatch: Boolean = true): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursive(root, text, partialMatch)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String, partialMatch: Boolean): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""

        if (partialMatch) {
            if (nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true)) {
                return node
            }
        } else {
            if (nodeText == text || nodeDesc == text) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findNodeRecursive(child, text, partialMatch)
                if (result != null) return result
                child.recycle()
            }
        }

        return null
    }

    /**
     * Click at specific coordinates
     */
    suspend fun click(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    /**
     * Click on a node
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Long click on a node
     */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    /**
     * Input text into an editable field
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Scroll forward
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scroll backward
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /**
     * Swipe gesture
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean =
        withContext(Dispatchers.Main) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            val deferred = CompletableDeferred<Boolean>()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            }, null)

            deferred.await()
        }

    /**
     * Press back button
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Press home button
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Press recent apps button
     */
    fun pressRecentApps(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open notifications
     */
    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * Open quick settings
     */
    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * Get current foreground app info
     */
    fun getCurrentApp(): JSONObject {
        val root = rootInActiveWindow
        return JSONObject().apply {
            if (root != null) {
                put("packageName", root.packageName?.toString() ?: "unknown")
                put("className", root.className?.toString() ?: "unknown")
                put("windowId", root.windowId)
            } else {
                put("error", "No active window")
            }
        }
    }
}
