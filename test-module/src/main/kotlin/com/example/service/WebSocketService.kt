package com.example.service

import com.tts.`in`.model.FyersClass
import com.tts.`in`.websocket.FyersSocket
import com.tts.`in`.websocket.FyersSocketDelegate
import org.json.JSONObject
import kotlinx.coroutines.*

class FyersSdkWebSocketClient(
    private val appId: String,
    private val accessToken: String,
    private val subscribedSymbols: List<String>,
    private val onTick: ((symbol: String, ltp: Double) -> Unit)? = null,
    private val onOpen: (() -> Unit)? = null,
    private val onError: (() -> Unit)? = null,
    private val onClose: (() -> Unit)? = null
) : FyersSocketDelegate {

    private var fyersSocket: FyersSocket? = null

    fun start() {
        try {
            val fyersClass = FyersClass.getInstance()
            fyersClass.clientId = appId
            fyersClass.accessToken = accessToken

            fyersSocket = FyersSocket(3).apply {
                webSocketDelegate = this@FyersSdkWebSocketClient
                val channelModesClass = Class.forName("in.tts.hsjavalib.ChannelModes")
                val liteEnum = channelModesClass.enumConstants.first { (it as Enum<*>).name == "LITE" }
                val method = this::class.java.getMethod("ConnectHSM", channelModesClass)
                method.invoke(this, liteEnum)
            }
            println("🚀 Fyers SDK WebSocket initialized for symbols: $subscribedSymbols")
        } catch (e: Exception) {
            println("❌ Fyers SDK WebSocket Start Error: ${e.message}")
            onError?.invoke()
        }
    }

    override fun OnIndex(index: JSONObject?) {}
    override fun OnScrips(scrips: JSONObject?) {
        if (scrips != null) {
            val symbol = scrips.optString("symbol", "")
            val ltp = scrips.optDouble("ltp", 0.0)
            if (symbol.isNotEmpty() && ltp > 0.0) {
                println("SDK Tick -> $symbol LTP: $ltp")
                onTick?.invoke(symbol, ltp)
            }
        }
    }
    override fun OnDepth(depths: JSONObject?) {}
    override fun OnOrder(orders: JSONObject?) {}
    override fun OnTrade(trades: JSONObject?) {}
    override fun OnPosition(positions: JSONObject?) {}
    override fun OnOpen(status: String?) {
        println("✅ Fyers SDK WebSocket Connected: $status")
        onOpen?.invoke()
        try {
            fyersSocket?.SubscribeData(subscribedSymbols)
            println("Fyers SDK WebSocket subscribed to: $subscribedSymbols")
        } catch (e: Exception) {
            println("❌ Fyers SDK WebSocket Subscribe Error: ${e.message}")
        }
    }
    override fun OnClose(status: String?) {
        println("⚠️ Fyers SDK WebSocket Closed: $status")
        onClose?.invoke()
        CoroutineScope(Dispatchers.IO).launch {
            TelegramNotifier.sendAlert("⚠️ [Fyers SDK WebSocket Closed] $status")
        }
    }
    override fun OnError(error: JSONObject?) {
        println("❌ Fyers SDK WebSocket Error: $error")
        onError?.invoke()
        CoroutineScope(Dispatchers.IO).launch {
            TelegramNotifier.sendAlert("❌ [Fyers SDK WebSocket Error] $error")
        }
    }
    override fun OnMessage(message: JSONObject?) {}

    fun stop() {
        try {
            fyersSocket = null
        } catch (_: Exception) {}
    }
}
