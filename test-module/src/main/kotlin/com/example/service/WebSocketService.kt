package com.example.service

import okhttp3.*
import org.json.JSONObject
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class OkHttpFyersWebSocketClient(
    private val appId: String,
    private val accessToken: String,
    private val subscribedSymbols: List<String>
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun start() {
        val authHeader = "$appId:$accessToken"
        val url = "wss://socket.fyers.in/hsm/v1-5/prod?access_token=$authHeader"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authHeader")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("✅ OkHttp Fyers WebSocket Connected!")
                val subObj = JSONObject().apply {
                    put("symbol", subscribedSymbols)
                    put("data_type", "symbolUpdate")
                    put("sub_type", "lite")
                }
                webSocket.send(subObj.toString())
                println("WebSocket subscribed to symbols: $subscribedSymbols")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    println("Tick -> ${json.optString("symbol", "")} LTP: ${json.optDouble("ltp", 0.0)}")
                } catch (e: Exception) {
                    println("WebSocket parse error: ${e.message} | Text: $text")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("⚠️ OkHttp Fyers WebSocket Closed: $code / $reason")
                CoroutineScope(Dispatchers.IO).launch {
                    TelegramNotifier.sendAlert("⚠️ [WebSocket Closed] $code: $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ OkHttp Fyers WebSocket Failure: ${t.message}")
                CoroutineScope(Dispatchers.IO).launch {
                    TelegramNotifier.sendAlert("❌ [WebSocket Error] ${t.message}")
                }
            }
        })
    }

    fun stop() {
        webSocket?.close(1000, "Client shutdown")
        webSocket = null
    }
}
