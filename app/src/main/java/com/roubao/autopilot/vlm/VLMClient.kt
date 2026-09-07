package com.roubao.autopilot.vlm

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * VLM (Vision Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4V, Qwen-VL, Claude, etc.)
 */
class VLMClient(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-vision-preview"
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        /**
         * 流式读取 + JSON 早停：模型吐出完整动作 JSON 的瞬间就收工，
         * 不再等它把剩余的思考/客套话写完（实测省掉数秒到十几秒）。
         * 服务端未走 SSE 时自动退回一次性解析。
         */
        private suspend fun readStreamedContent(response: okhttp3.Response): String {
            val isStream = response.header("Content-Type")?.contains("event-stream") == true
            response.use { r ->
                if (!r.isSuccessful) return ""
                if (!isStream) {
                    val body = r.body?.string() ?: return ""
                    return JSONObject(body).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content") ?: ""
                }
                val source = r.body?.source() ?: return ""
                val sb = StringBuilder()
                try {
                    while (!source.exhausted()) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = JSONObject(data).optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("delta")
                            ?.optString("content")
                        if (!delta.isNullOrEmpty()) {
                            sb.append(delta)
                            if (hasCompleteActionJson(sb)) {
                                println("[VLMClient] 流式早停：已收到完整动作 JSON")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 取消/中断：已收到的内容若含完整 JSON 仍可用
                }
                return sb.toString()
            }
        }

        /**
         * 判断累积文本里是否已出现「括号闭合且含 action 字段」的 JSON 对象
         */
        private fun hasCompleteActionJson(sb: StringBuilder): Boolean {
            val s = sb.toString()
            val start = s.indexOf('{')
            if (start < 0) return false
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until s.length) {
                val c = s[i]
                if (esc) { esc = false; continue }
                when {
                    c == '\\' && inStr -> esc = true
                    c == '"' -> inStr = !inStr
                    !inStr && c == '{' -> depth++
                    !inStr && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            return s.substring(start, i + 1).contains("\"action\"")
                        }
                    }
                }
            }
            return false
        }

        /**
         * 可取消的同步 HTTP 调用：协程被取消（用户点停止）时立即掐断在途请求，
         * 而不是傻等 readTimeout（最长 90s）。这是「停止按钮要很久才生效」的修复。
         */
        private suspend fun cancellableCall(client: OkHttpClient, request: Request): okhttp3.Response {
            val call = client.newCall(request)
            val job = coroutineContext[kotlinx.coroutines.Job]
            val handle = job?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
            try {
                return call.execute()
            } finally {
                handle?.dispose()
            }
        }

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }

        /**
         * 从 API 获取可用模型列表
         * @param baseUrl API 基础地址
         * @param apiKey API 密钥
         * @return 模型 ID 列表
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            // 验证 baseUrl 是否为空
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("Base URL 不能为空"))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // 清理 URL，确保正确拼接
            val cleanBaseUrl = normalizeUrl(baseUrl.removeSuffix("/chat/completions"))

            val request = try {
                Request.Builder()
                    .url("$cleanBaseUrl/models")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(Exception("Base URL 格式无效: ${e.message}"))
            }

            try {
                cancellableCall(client, request).use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val data = json.optJSONArray("data") ?: JSONArray()
                        val models = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i)
                            if (item != null) {
                                val id = item.optString("id", "").trim()
                                if (id.isNotEmpty()) {
                                    models.add(id)
                                }
                            }
                        }
                        Result.success(models)
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 调用 VLM 进行多模态推理 (带重试)
     */
    suspend fun predict(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 预先编码图片 (避免重试时重复编码)
        val encodedImages = images.map { bitmapToBase64Url(it) }

        for (attempt in 1..MAX_RETRIES) {
            coroutineContext.ensureActive()  // 用户已停止则立即退出重试
            try {
                val content = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    encodedImages.forEach { imageUrl ->
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                            })
                        })
                    }
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 2048)
                    put("stream", true)
                    put("temperature", 0.0)
                    put("top_p", 0.85)
                    put("frequency_penalty", 0.2)  // 减少重复输出
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = cancellableCall(client, request)
                val responseContent = readStreamedContent(response)

                if (response.isSuccessful) {
                    if (responseContent.isNotBlank()) {
                        return@withContext Result.success(responseContent)
                    }
                    lastException = Exception("No response from model")
                } else {
                    lastException = Exception("API error: ${response.code}")
                }
            } catch (e: UnknownHostException) {
                // DNS 解析失败，重试
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时，重试
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                // IO 错误，重试
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                // 其他错误，不重试
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 调用 VLM 进行多模态推理 (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            coroutineContext.ensureActive()  // 用户已停止则立即退出重试
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 2048)
                    put("stream", true)
                    put("temperature", 0.0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = cancellableCall(client, request)
                val responseContent = readStreamedContent(response)

                if (response.isSuccessful) {
                    if (responseContent.isNotBlank()) {
                        return@withContext Result.success(responseContent)
                    }
                    lastException = Exception("No response from model")
                } else {
                    lastException = Exception("API error: ${response.code}")
                }
            } catch (e: UnknownHostException) {
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * Bitmap 转 Base64 URL (只压缩质量，不压缩分辨率)
     * 保持原始分辨率以确保坐标准确
     */
    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 使用 JPEG 格式，质量 70%，保持原始分辨率
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[VLMClient] 图片压缩: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 调整图片大小
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

/**
 * 常用 VLM 配置
 */
object VLMConfigs {
    // OpenAI GPT-4V
    fun gpt4v(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4-vision-preview"
    )

    // Qwen-VL (阿里云)
    fun qwenVL(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-max"
    )

    // Claude (Anthropic)
    fun claude(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-20241022"
    )

    // 自定义 (vLLM / Ollama / LocalAI)
    fun custom(apiKey: String, baseUrl: String, model: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )
}
