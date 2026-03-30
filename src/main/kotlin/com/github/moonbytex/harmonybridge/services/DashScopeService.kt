package com.github.moonbytex.harmonybridge.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.awt.EventQueue
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class DashScopeService {

    private val apiKey = "sk-6354cec743c0434fb34bd49e4c1a515d"
    private val baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val model = "qwen3-4b"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class ChatMessage(
        val role: String,
        val content: String
    )

    interface StreamCallback {
        fun onThinking(content: String)
        fun onResponse(content: String)
        fun onError(error: String)
        fun onComplete()
    }

    fun sendChatStream(messages: List<ChatMessage>, callback: StreamCallback) {
        // 在新线程中执行网络请求，避免阻塞 EDT
        Thread {
            try {
                val jsonMessages = JSONArray()
                messages.forEach { msg ->
                    jsonMessages.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", jsonMessages)
                    put("stream", true)
                    put("enable_thinking", true)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        EventQueue.invokeLater {
                            callback.onError("API 请求失败：${response.code} - ${response.message}")
                        }
                        return@use
                    }

                    val reader = response.body?.byteStream()?.bufferedReader()
                        ?: throw Exception("响应体为空")

                    var isAnswering = false

                    reader.useLines { lines ->
                        lines.forEach { line ->
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data == "[DONE]") {
                                    EventQueue.invokeLater {
                                        callback.onComplete()
                                    }
                                    return@forEach
                                }

                                try {
                                    val json = JSONObject(data)
                                    val choices = json.getJSONArray("choices")
                                    if (choices.length() > 0) {
                                        val delta = choices.getJSONObject(0).getJSONObject("delta")
                                        
                                        // 处理思考内容
                                        if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                            val thinkingContent = delta.getString("reasoning_content")
                                            EventQueue.invokeLater {
                                                callback.onThinking(thinkingContent)
                                            }
                                        }
                                        
                                        // 处理回复内容
                                        if (delta.has("content") && !delta.isNull("content")) {
                                            val content = delta.getString("content")
                                            if (!isAnswering) {
                                                isAnswering = true
                                            }
                                            EventQueue.invokeLater {
                                                callback.onResponse(content)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    thisLogger().error("解析响应失败：${e.message}", e)
                                }
                            }
                        }
                    }
                    
                    EventQueue.invokeLater {
                        callback.onComplete()
                    }
                }
            } catch (e: Exception) {
                thisLogger().error("发送消息失败：${e.message}", e)
                EventQueue.invokeLater {
                    callback.onError("发送失败：${e.message}")
                }
            }
        }.start()
    }
}
