package com.exam.auto

import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ClaudeApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 이미지(JPEG bytes)와 프롬프트를 Claude에 전송하고 응답 텍스트 반환
     */
    fun analyze(
        apiKey: String,
        imageBytes: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        model: String = "claude-opus-4-5"
    ): String {
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", b64)
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            if (!response.isSuccessful) throw IOException("API 오류 ${response.code}: $responseBody")
            val json = JSONObject(responseBody)
            return json.getJSONArray("content").getJSONObject(0).getString("text")
        }
    }
}
