package com.roubao.autopilot.controller

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.roubao.autopilot.service.CaibaoA11yService

/**
 * A11y 感知控制器（感知通道 A）。
 *
 * 职责：
 * 1. 树快照：把当前窗口压成「可点击元素清单」（文本/desc/id/bounds/中心点）
 * 2. 紧凑文本：喂给 VLM 当结构化上下文（替代纯看图，省 token 且坐标更准）
 * 3. 直接动作：performClick / findByText —— 未来 L2 确定性匹配的地基
 *
 * 已知边界（P0-2/P0-4 实测）：微信类 App 返回空树（nodes=1），此时调用方应
 * 自动降级回截图+VLM 通道。
 */
object A11yPerception {

    data class Element(
        val index: Int,
        val className: String,
        val text: String,
        val desc: String,
        val viewId: String,
        val bounds: Rect,
        val isScrollable: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    data class Snapshot(
        val windowPkg: String,
        val totalNodes: Int,
        val elements: List<Element>,
        val truncated: Boolean
    )

    val isAvailable: Boolean
        get() = CaibaoA11yService.isConnected

    private const val MAX_NODES = 2500
    private const val MAX_DEPTH = 40
    private const val MAX_ELEMENTS = 80
    private const val MAX_TEXT_CHARS = 3200

    /**
     * 抓取当前活动窗口的节点树快照。服务未连接或窗口为空时返回 null。
     */
    fun snapshot(): Snapshot? {
        val service = CaibaoA11yService.instance ?: return null
        val root = try {
            service.getRootInActiveWindow()
        } catch (e: Exception) {
            println("[A11yPerception] getRoot 失败: ${e.message}")
            return null
        } ?: return null

        val elements = mutableListOf<Element>()
        var total = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (total >= MAX_NODES || depth > MAX_DEPTH) {
                truncated = true
                return
            }
            total++
            if (node.isClickable && elements.size < MAX_ELEMENTS) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) {
                    elements.add(Element(
                        index = elements.size + 1,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        text = node.text?.toString()?.trim() ?: "",
                        desc = node.contentDescription?.toString()?.trim() ?: "",
                        viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "",
                        bounds = rect,
                        isScrollable = node.isScrollable
                    ))
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    walk(child, depth + 1)
                }
            }
        }

        walk(root, 0)
        val pkg = root.packageName?.toString() ?: "?"
        if (total <= 1) {
            // 微信类屏蔽树：root 无子节点
            println("[A11yPerception] pkg=$pkg 树为空（内容被屏蔽），需走截图通道")
            return Snapshot(pkg, total, emptyList(), truncated = false)
        }
        println("[A11yPerception] pkg=$pkg nodes=$total 可点击元素=${elements.size}")
        return Snapshot(pkg, total, elements, truncated)
    }

    /**
     * 紧凑文本（喂 VLM）：只列可点击元素，坐标用像素中心点。
     * 微信类空树返回 null（不浪费 prompt）。
     */
    fun compactTreeText(): String? {
        val snap = snapshot() ?: return null
        if (snap.elements.isEmpty()) return null

        val sb = StringBuilder("[A11y 元素清单] pkg=${snap.windowPkg} 共${snap.totalNodes}节点，可点击：\n")
        for (e in snap.elements) {
            val label = e.text.ifEmpty { e.desc }.ifEmpty { e.viewId }.ifEmpty { e.className }
            val line = "${e.index}. ${e.className} \"$label\"" +
                    (if (e.viewId.isNotEmpty()) " id=${e.viewId}" else "") +
                    " center=(${e.centerX},${e.centerY})" +
                    (if (e.isScrollable) " [可滚动]" else "")
            if (sb.length + line.length > MAX_TEXT_CHARS) {
                sb.append("...(已截断)")
                break
            }
            sb.append(line).append('\n')
        }
        sb.append("TIP: to press multiple keys in a row (calculator/dialer), finish them ALL in one go with ")
        sb.append("\"action\":\"click_sequence\",\"texts\":[...]\" — much faster than one click per step.")
        return sb.toString()
    }

    /**
     * 按文本/描述/id 找可点击元素（L2 确定性匹配的雏形）。
     */
    fun findByText(query: String): Element? {
        val snap = snapshot() ?: return null
        val q = query.trim()
        if (q.isEmpty()) return null
        val exact = snap.elements.firstOrNull { it.text == q || it.desc == q }
        if (exact != null) return exact
        return snap.elements.firstOrNull {
            it.text.contains(q) || it.desc.contains(q) || it.viewId.contains(q, ignoreCase = true)
        }
    }

    /**
     * 直接点击元素（通道 A：无障碍 performAction，无需坐标注入）。
     * 微信类屏蔽树不可用。
     */
    fun clickByText(query: String): Boolean {
        val el = findByText(query) ?: return false
        return clickElement(el)
    }

    fun clickElement(el: Element): Boolean {
        val service = CaibaoA11yService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        // 重新定位到对应节点（避免持有过期引用），按 bounds+文本匹配
        val target = findNodeByBounds(root, el.bounds) ?: return false
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        println("[A11yPerception] performClick ${el.bounds} -> $ok")
        return ok
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > MAX_DEPTH) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r == bounds && node.isClickable) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                if (found != null) return
                val c = try { node.getChild(i) } catch (e: Exception) { null }
                c?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return found
    }
}
