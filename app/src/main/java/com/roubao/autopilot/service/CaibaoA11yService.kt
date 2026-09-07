package com.roubao.autopilot.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 菜包自有无障碍服务 —— 感知通道 A 的地基（S1）。
 *
 * 实测依据（POC-报告 第10章）：
 * - HyperOS 3 放行 adb/Shizuku 的追加写入启用
 * - 进程内 getRootInActiveWindow() <100ms，远优于 uiautomator dump 的 2.3-3.3s
 * - 崩溃后 HyperOS 不自动重绑（恢复靠 Shizuku 关→开切换，见 SelfHeal 后续扩展）
 *
 * 设计：静态 instance 持有，供 A11yPerception 直接读取节点树。
 */
class CaibaoA11yService : AccessibilityService() {

    companion object {
        const val COMPONENT = "com.roubao.autopilot/com.roubao.autopilot.service.CaibaoA11yService"

        @Volatile
        var instance: CaibaoA11yService? = null
            private set

        val isConnected: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        println("[CaibaoA11y] onServiceConnected —— 感知通道 A 上线")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        println("[CaibaoA11y] onUnbind —— 感知通道 A 下线")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 感知采用按需拉取（截图前主动读树），不做事件流处理，省电且足够
    }

    override fun onInterrupt() {}
}
