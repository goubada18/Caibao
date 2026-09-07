package com.roubao.autopilot.controller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * L2 动作记忆（S1 后半 / S2 雏形）——「跳过模型」的第一块地基。
 *
 * 思路：同一个指令 + 同一个界面（包名 + 可点击元素签名）出现过一次后，
 * 把当时成功的动作序列记下来；下次遇到相同的 (指令, 界面) 直接重放，
 * 一次 VLM 都不调（单步从 20-40s 降到毫秒级）。
 *
 * 安全边界：
 * - 只缓存 click_element / click_sequence 这类通道 A 动作（可由无障碍确认成功）
 * - 重放时任何一步找不到元素，立即中止并回退到 VLM 决策
 * - 缓存按指令归一化 + 界面签名做键，界面结构变化会自然失配
 */
object ActionMemory {

    private const val CACHE_FILE = "action_memory.json"
    private const val MAX_ENTRIES = 200

    @Volatile
    private var cache: MutableMap<String, List<Action>>? = null

    @Volatile
    private var contextDir: File? = null

    fun init(filesDir: File) {
        contextDir = filesDir
        load()
    }

    /** 一条待重放的动作 */
    data class Action(
        val type: String,          // click_element / click_sequence
        val text: String = "",     // click_element 的目标
        val texts: List<String> = emptyList()  // click_sequence 的序列
    )

    /**
     * 界面签名：包名 + 可点击元素的标签集合（顺序无关，防微小布局抖动失配）
     */
    fun screenSignature(pkg: String, labels: List<String>): String {
        val sig = labels.sorted().joinToString("|")
        return md5("$pkg#$sig").take(12)
    }

    fun keyFor(instruction: String, pkg: String, labels: List<String>): String =
        "${normalize(instruction)}@${screenSignature(pkg, labels)}"

    /**
     * 查询：命中返回动作序列，未命中返回 null
     */
    fun lookup(key: String): List<Action>? {
        val hit = cache?.get(key)
        if (hit != null) println("[ActionMemory] L2 命中: ${key.take(40)} -> ${hit.size} 个动作")
        return hit
    }

    /**
     * 记录：任务成功时调用，缓存该 (指令, 界面) 下执行过的动作序列
     */
    fun remember(key: String, actions: List<Action>) {
        if (actions.isEmpty()) return
        val map = cache ?: return
        map[key] = actions
        if (map.size > MAX_ENTRIES) {
            // 简单的 FIFO 淘汰
            val drop = map.keys.firstOrNull()
            drop?.let { map.remove(it) }
        }
        persist()
        println("[ActionMemory] 已记忆: ${key.take(40)} -> ${actions.size} 个动作")
    }

    /**
     * 重放：全部走通道 A，任何一步失败立即返回 false（调用方回退 VLM）
     */
    fun replay(actions: List<Action>): Boolean {
        var allOk = true
        for (a in actions) {
            val ok = when (a.type) {
                "click_element" -> a.text.isNotBlank() && A11yPerception.clickByText(a.text)
                "click_sequence" -> {
                    var seqOk = a.texts.isNotEmpty()
                    for (t in a.texts) {
                        val one = A11yPerception.clickByText(t)
                        if (!one) { seqOk = false; break }
                        Thread.sleep(150)
                    }
                    seqOk
                }
                else -> false
            }
            if (!ok) {
                println("[ActionMemory] 重放失败于 $a —— 回退 VLM 决策")
                allOk = false
                break
            }
            Thread.sleep(120)
        }
        return allOk
    }

    // ========== 内部 ==========

    private fun normalize(instruction: String): String =
        instruction.trim().lowercase().replace(Regex("\\s+"), "").take(60)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun load() {
        val dir = contextDir ?: return
        val map = LinkedHashMap<String, List<Action>>()
        val file = File(dir, CACHE_FILE)
        if (file.exists()) {
            try {
                val obj = JSONObject(file.readText())
                obj.keys().forEach { k ->
                    val arr = obj.getJSONArray(k)
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val a = arr.getJSONObject(i)
                        val type = a.optString("type")
                        if (type.isBlank()) null
                        else Action(
                            type = type,
                            text = a.optString("text", ""),
                            texts = a.optJSONArray("texts")?.let { ja ->
                                (0 until ja.length()).map { j -> ja.optString(j) }
                            } ?: emptyList()
                        )
                    }
                    if (list.isNotEmpty()) map[k] = list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cache = map
        println("[ActionMemory] 载入 ${map.size} 条记忆")
    }

    @Synchronized
    private fun persist() {
        val dir = contextDir ?: return
        val map = cache ?: return
        try {
            val obj = JSONObject()
            for ((k, v) in map) {
                val arr = JSONArray()
                for (a in v) {
                    arr.put(JSONObject().apply {
                        put("type", a.type)
                        put("text", a.text)
                        put("texts", JSONArray(a.texts))
                    })
                }
                obj.put(k, arr)
            }
            File(dir, CACHE_FILE).writeText(obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
