package com.example.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {

    companion object {

        @Volatile
        var instance: JarvisAccessibilityService? = null
    }

    // =========================================================
    // SERVICE CONNECTED
    // =========================================================

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
    }

    // =========================================================
    // ACCESSIBILITY EVENTS
    // =========================================================

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        if (event == null) {
            return
        }

        // Accessibility events can be observed here.
        //
        // Later DeepSeek integration can use:
        //
        // TYPE_WINDOW_STATE_CHANGED
        // TYPE_WINDOW_CONTENT_CHANGED
        // TYPE_VIEW_TEXT_CHANGED
        //
        // to detect DeepSeek UI changes and assistant responses.
    }

    // =========================================================
    // INTERRUPT
    // =========================================================

    override fun onInterrupt() {
        // Required by AccessibilityService.
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    // =========================================================
    // MAIN JARVIS ACTION ROUTER
    // =========================================================

    fun performJarvisAction(
        action: String,
        target: String? = null,
        value: String? = null
    ): Boolean {

        return when (action.trim().uppercase()) {

            // -------------------------------------------------
            // BACK
            // -------------------------------------------------

            "BACK" -> {
                performGlobalAction(
                    GLOBAL_ACTION_BACK
                )
            }

            // -------------------------------------------------
            // HOME
            // -------------------------------------------------

            "HOME" -> {
                performGlobalAction(
                    GLOBAL_ACTION_HOME
                )
            }

            // -------------------------------------------------
            // RECENTS
            // -------------------------------------------------

            "RECENTS",
            "RECENT_APPS" -> {
                performGlobalAction(
                    GLOBAL_ACTION_RECENTS
                )
            }

            // -------------------------------------------------
            // SCROLL UP
            // -------------------------------------------------

            "SCROLL_UP" -> {
                scrollWindow(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            }

            // -------------------------------------------------
            // SCROLL DOWN
            // -------------------------------------------------

            "SCROLL_DOWN" -> {
                scrollWindow(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
            }

            // -------------------------------------------------
            // CLICK
            // -------------------------------------------------

            "CLICK" -> {
                clickTarget(
                    target = target,
                    value = value
                )
            }

            // -------------------------------------------------
            // TYPE
            // -------------------------------------------------

            "TYPE" -> {
                typeText(
                    target = target,
                    value = value
                )
            }

            // -------------------------------------------------
            // UNKNOWN ACTION
            // -------------------------------------------------

            else -> {
                false
            }
        }
    }

    // =========================================================
    // CLICK BY VISIBLE TEXT
    // =========================================================

    fun clickText(
        text: String
    ): Boolean {

        if (text.isBlank()) {
            return false
        }

        val root =
            rootInActiveWindow
                ?: return false

        val node =
            AccessibilityHelper.findText(
                root,
                text.trim()
            )

        return AccessibilityHelper.click(node)
    }

    // =========================================================
    // CLICK TARGET
    // =========================================================

    private fun clickTarget(
        target: String?,
        value: String?
    ): Boolean {

        val searchText =
            target
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: value
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: return false

        val root =
            rootInActiveWindow
                ?: return false

        // 1. Try visible text.
        if (
            clickByText(
                root,
                searchText
            )
        ) {
            return true
        }

        // 2. Try content description.
        if (
            clickByContentDescription(
                root,
                searchText
            )
        ) {
            return true
        }

        // 3. Try resource ID.
        return clickByResourceId(
            root,
            searchText
        )
    }

    // =========================================================
    // CLICK BY TEXT
    // =========================================================

    private fun clickByText(
        root: AccessibilityNodeInfo,
        target: String
    ): Boolean {

        val cleanTarget =
            target.trim()

        if (cleanTarget.isBlank()) {
            return false
        }

        val nodes =
            try {
                root.findAccessibilityNodeInfosByText(
                    cleanTarget
                )
            } catch (_: Exception) {
                emptyList()
            }

        // -----------------------------------------------------
        // First: directly clickable node
        // -----------------------------------------------------

        for (node in nodes) {

            if (
                node.isVisibleToUser &&
                node.isClickable
            ) {

                if (
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                ) {
                    return true
                }
            }
        }

        // -----------------------------------------------------
        // Second: clickable parent
        // -----------------------------------------------------

        for (node in nodes) {

            if (!node.isVisibleToUser) {
                continue
            }

            var parent =
                node.parent

            while (parent != null) {

                if (
                    parent.isVisibleToUser &&
                    parent.isClickable
                ) {

                    if (
                        parent.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
                }

                parent =
                    parent.parent
            }
        }

        return false
    }

    // =========================================================
    // CLICK BY CONTENT DESCRIPTION
    // =========================================================

    private fun clickByContentDescription(
        root: AccessibilityNodeInfo,
        target: String
    ): Boolean {

        val targetLower =
            target.trim().lowercase()

        if (targetLower.isBlank()) {
            return false
        }

        return findNodeByDescription(
            node = root,
            target = targetLower
        )
    }

    // =========================================================
    // FIND NODE BY DESCRIPTION
    // =========================================================

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo,
        target: String
    ): Boolean {

        val description =
            node.contentDescription
                ?.toString()
                ?.trim()
                ?.lowercase()

        if (
            !description.isNullOrBlank() &&
            (
                description == target ||
                description.contains(target)
            ) &&
            node.isVisibleToUser
        ) {

            // Direct click.
            if (node.isClickable) {

                if (
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                ) {
                    return true
                }
            }

            // Click parent.
            var parent =
                node.parent

            while (parent != null) {

                if (
                    parent.isVisibleToUser &&
                    parent.isClickable
                ) {

                    if (
                        parent.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
                }

                parent =
                    parent.parent
            }
        }

        // Search children.
        for (i in 0 until node.childCount) {

            val child =
                try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            if (
                findNodeByDescription(
                    node = child,
                    target = target
                )
            ) {
                return true
            }
        }

        return false
    }

    // =========================================================
    // CLICK BY RESOURCE ID
    // =========================================================

    private fun clickByResourceId(
        root: AccessibilityNodeInfo,
        target: String
    ): Boolean {

        val cleanTarget =
            target.trim()

        if (cleanTarget.isBlank()) {
            return false
        }

        val nodes =
            try {
                root.findAccessibilityNodeInfosByViewId(
                    cleanTarget
                )
            } catch (_: Exception) {
                emptyList()
            }

        for (node in nodes) {

            // -------------------------------------------------
            // Direct click
            // -------------------------------------------------

            if (
                node.isVisibleToUser &&
                node.isClickable
            ) {

                if (
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                ) {
                    return true
                }
            }

            // -------------------------------------------------
            // Click parent
            // -------------------------------------------------

            var parent =
                node.parent

            while (parent != null) {

                if (
                    parent.isVisibleToUser &&
                    parent.isClickable
                ) {

                    if (
                        parent.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) {
                        return true
                    }
                }

                parent =
                    parent.parent
            }
        }

        return false
    }

    // =========================================================
    // TYPE TEXT
    // =========================================================

    private fun typeText(
        target: String?,
        value: String?
    ): Boolean {

        if (value.isNullOrBlank()) {
            return false
        }

        val root =
            rootInActiveWindow
                ?: return false

        // -----------------------------------------------------
        // Find target editable field.
        // -----------------------------------------------------

        val node =
            if (!target.isNullOrBlank()) {

                findEditableNode(
                    root = root,
                    target = target.trim()
                )
                    ?: findFocusedEditableNode(
                        root
                    )

            } else {

                findFocusedEditableNode(
                    root
                )
            }
                ?: findAnyEditableNode(
                    root
                )
                ?: return false

        // -----------------------------------------------------
        // Prepare text arguments.
        // -----------------------------------------------------

        val arguments =
            Bundle()

        arguments.putCharSequence(
            AccessibilityNodeInfo
                .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )

        // -----------------------------------------------------
        // Set text.
        // -----------------------------------------------------

        return try {

            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )

        } catch (_: Exception) {

            false
        }
    }

    // =========================================================
    // FIND FOCUSED EDITABLE
    // =========================================================

    private fun findFocusedEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (
            root.isEditable &&
            root.isFocused &&
            root.isVisibleToUser
        ) {
            return root
        }

        for (i in 0 until root.childCount) {

            val child =
                try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            val result =
                findFocusedEditableNode(
                    child
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    // =========================================================
    // FIND ANY EDITABLE FIELD
    // =========================================================

    private fun findAnyEditableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (
            root.isEditable &&
            root.isVisibleToUser
        ) {
            return root
        }

        for (i in 0 until root.childCount) {

            val child =
                try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            val result =
                findAnyEditableNode(
                    child
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    // =========================================================
    // FIND EDITABLE BY TARGET
    // =========================================================

    private fun findEditableNode(
        root: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {

        val targetLower =
            target.trim().lowercase()

        if (targetLower.isBlank()) {
            return null
        }

        if (
            root.isEditable &&
            root.isVisibleToUser
        ) {

            val text =
                root.text
                    ?.toString()
                    ?.trim()
                    ?.lowercase()

            val description =
                root.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.lowercase()

            val hint =
                root.hintText
                    ?.toString()
                    ?.trim()
                    ?.lowercase()

            if (
                text?.contains(
                    targetLower
                ) == true ||

                description?.contains(
                    targetLower
                ) == true ||

                hint?.contains(
                    targetLower
                ) == true
            ) {

                return root
            }
        }

        for (i in 0 until root.childCount) {

            val child =
                try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            val result =
                findEditableNode(
                    root = child,
                    target = targetLower
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    // =========================================================
    // SCROLL WINDOW
    // =========================================================

    private fun scrollWindow(
        action: Int
    ): Boolean {

        val root =
            rootInActiveWindow
                ?: return false

        val scrollable =
            findScrollableNode(
                root
            )
                ?: return false

        return try {

            scrollable.performAction(
                action
            )

        } catch (_: Exception) {

            false
        }
    }

    // =========================================================
    // FIND SCROLLABLE NODE
    // =========================================================

    private fun findScrollableNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (
            root.isScrollable &&
            root.isVisibleToUser
        ) {
            return root
        }

        for (i in 0 until root.childCount) {

            val child =
                try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            val result =
                findScrollableNode(
                    child
                )

            if (result != null) {
                return result
            }
        }

        return null
    }
}