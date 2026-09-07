package com.roubao.autopilot.controller

/**
 * 自愈工具：借助 Shizuku shell 修复菜包自身的常见系统限制。
 *
 * 典型场景：HyperOS 的「获取应用列表」隐私开关默认拒绝侧载应用，
 * 导致 QUERY_ALL_PACKAGES 失效、AppScanner 只能扫到菜包自己。
 * shell 身份有权限执行 appops set 把开关打开。
 */
object SelfHeal {

    /** 由 DeviceController 在 ShellService 连接后注入（shell uid 执行） */
    @Volatile
    var shellExec: ((String) -> String)? = null

    /**
     * 修复应用列表可见性。返回 true 表示已执行 appops 放行（调用方应重扫）。
     */
    fun fixAppVisibility(pkg: String = "com.roubao.autopilot"): Boolean {
        val exec = shellExec ?: run {
            println("[SelfHeal] 无 shell 通道（Shizuku 未连接），无法自愈")
            return false
        }
        return try {
            val out = exec("appops set $pkg QUERY_ALL_PACKAGES allow && appops get $pkg QUERY_ALL_PACKAGES")
            val ok = out.contains("allow")
            println("[SelfHeal] QUERY_ALL_PACKAGES 自愈${if (ok) "成功" else "失败"}: ${out.trim()}")
            ok
        } catch (e: Exception) {
            println("[SelfHeal] 自愈异常: $e")
            false
        }
    }
}
