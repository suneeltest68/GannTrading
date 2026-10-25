package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.model.GannLevels
import com.example.service.GannCalculator
import com.tts.`in`.websocket.FyersSocket
import com.tts.`in`.websocket.FyersSocketDelegate
import `in`.tts.hsjavalib.ChannelModes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.LocalTime
import java.time.Duration
import kotlin.math.round

class TradingWorkflowService(private val viewModel: AuthViewModel) {

    private val symbolsGannMap = mutableMapOf<String, GannLevels>()
    private val quantitiesMap = mutableMapOf<String, Int>()

    private suspend fun waitForMarketOpen() {
        val istZone = ZoneId.of("Asia/Kolkata")
        val marketOpenTime = LocalTime.of(9, 20)

        while (true) {
            val now = LocalTime.now(istZone)
            if (now.isAfter(marketOpenTime) || now == marketOpenTime) {
                println("[Market Open] It is 9:20 AM IST or later ($now). Proceeding with trading workflow...")
                break
            }

            val waitDurationMillis = Duration.between(now, marketOpenTime).toMillis()
            println("[Waiting] Current time (IST): $now. Waiting until market open at 9:20 AM IST (${waitDurationMillis / 1000} seconds remaining)...")
            
            delay(minOf(waitDurationMillis, 30_000L))
        }
    }

    suspend fun executeCurrentExpiryWorkflow(appId: String, token: String) {
        // Wait until 9:20 AM IST before fetching opening data and starting workflow
        waitForMarketOpen()

        // Use today's date in IST
        val targetDateStr = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        println("Target Execution Date: $targetDateStr")

        // Execute Nifty, Bank Nifty, and Sensex workflows concurrently using Coroutines with rate-limiting delays
        coroutineScope {
            val niftyDeferred = async {
                delay(100)
                processIndexWorkflow(appId, token, "Nifty 50", "NSE:NIFTY50-INDEX", targetDateStr, 50.0, 130)
            }
            /*val bankNiftyDeferred = async {
                delay(300)
                processIndexWorkflow(appId, token, "Bank Nifty", "NSE:NIFTYBANK-INDEX", targetDateStr, 100.0, 60)
            }*/
            val sensexDeferred = async {
                delay(500)
                processIndexWorkflow(appId, token, "Sensex", "BSE:SENSEX-INDEX", targetDateStr, 100.0, 40)
            }

            niftyDeferred.await()
//            bankNiftyDeferred.await()
            sensexDeferred.await()
        }

        // Initialize Paper Trading Engine & WebSocket for the collected strikes
        if (symbolsGannMap.isNotEmpty()) {
            println("\n[11] Initializing Paper Trading Engine for ${symbolsGannMap.size} strikes...")
            val tradingEngine = PaperTradingEngine(symbolsGannMap, quantitiesMap)
            tradingEngine.printStatus()

            println("\n[12] Connecting to Fyers WebSocket for live streaming of the collected strikes...")
            /*val wsClient = OkHttpFyersWebSocketClient(appId, token, symbolsGannMap.keys.toList(), tradingEngine)
            wsClient.start()*/
            val wsListener = TradingWebSocketListener(tradingEngine, symbolsGannMap.keys.toList())
            wsListener.start()
        } else {
            println("\nNo strikes collected for paper trading.")
        }
    }

    private suspend fun processIndexWorkflow(
        appId: String,
        token: String,
        indexName: String,
        indexSymbol: String,
        targetDateStr: String,
        strikeStep: Double,
        lotQuantity: Int
    ) {
        println("\n==================================================")
        println("PROCESSING INDEX: $indexName ($indexSymbol)")
        println("==================================================")

        delay(200) // Rate limiting safeguard against status 429
        val indexHistory = viewModel.fetchHistoricalData(
            appId = appId,
            accessToken = token,
            symbol = indexSymbol,
            resolution = "D",
            rangeFrom = targetDateStr,
            rangeTo = targetDateStr
        )

        var openPrice = if (indexName.contains("Sensex")) 80000.0 else if (indexName.contains("Bank")) 50000.0 else 22555.0
        if (indexHistory != null && indexHistory.has("candles")) {
            val candles = indexHistory.getJSONArray("candles")
            if (candles.length() > 0) {
                val candle = candles.getJSONArray(0)
                openPrice = candle.getDouble(1)
            }
        }

        val atmStrike = round(openPrice / strikeStep) * strikeStep
        println("Calculated ATM Strike for $indexName: $atmStrike")

        delay(300) // Rate limiting safeguard
        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 20)
        
        var ceSymbol = ""
        var peSymbol = ""

        if (optionChain != null && optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            if (dataObj.has("optionsChain")) {
                val optionsArray = dataObj.getJSONArray("optionsChain")
                for (i in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(i)
                    val strike = optObj.optDouble("strike_price", 0.0)
                    val symbol = optObj.optString("symbol", "")
                    val optionType = optObj.optString("option_type", "")

                    if (Math.abs(strike - atmStrike) < 1.0) {
                        if (optionType.equals("CE", ignoreCase = true) && ceSymbol.isEmpty()) {
                            ceSymbol = symbol
                        } else if (optionType.equals("PE", ignoreCase = true) && peSymbol.isEmpty()) {
                            peSymbol = symbol
                        }
                    }
                }
            }
        }

        // Correct fallbacks per index prefix
        val prefix = if (indexName.contains("Sensex")) "BSE:SENSEX" else if (indexName.contains("Bank")) "NSE:BANKNIFTY" else "NSE:NIFTY"
        if (ceSymbol.isEmpty()) ceSymbol = "${prefix}26O01${atmStrike.toInt()}CE"
        if (peSymbol.isEmpty()) peSymbol = "${prefix}26O01${atmStrike.toInt()}PE"

        println("Target $indexName ATM CE Symbol: $ceSymbol")
        println("Target $indexName ATM PE Symbol: $peSymbol")

        val priorTradingDate = getPriorTradingDate(viewModel, appId, token, targetDateStr, indexSymbol)

        // Calculate Gann Levels & Register for Paper Trading
        delay(200)
        val ceGann = calculateGannLevelsForSymbol(viewModel, appId, token, ceSymbol, priorTradingDate)
        if (ceGann != null) {
            GannCalculator.printTable(ceSymbol, ceGann)
            synchronized(symbolsGannMap) {
                symbolsGannMap[ceSymbol] = ceGann
                quantitiesMap[ceSymbol] = lotQuantity
            }
        }

        delay(200)
        val peGann = calculateGannLevelsForSymbol(viewModel, appId, token, peSymbol, priorTradingDate)
        if (peGann != null) {
            GannCalculator.printTable(peSymbol, peGann)
            synchronized(symbolsGannMap) {
                symbolsGannMap[peSymbol] = peGann
                quantitiesMap[peSymbol] = lotQuantity
            }
        }
    }

    private suspend fun calculateGannLevelsForSymbol(viewModel: AuthViewModel, appId: String, token: String, symbol: String, priorTradingDate: String): GannLevels? {
        delay(200)
        val history = viewModel.fetchHistoricalData(
            appId = appId,
            accessToken = token,
            symbol = symbol,
            resolution = "D",
            rangeFrom = priorTradingDate,
            rangeTo = priorTradingDate
        )

        var pdc = 0.0
        if (history != null && history.has("candles")) {
            val candles = history.getJSONArray("candles")
            if (candles.length() > 0) {
                val candle = candles.getJSONArray(0)
                pdc = candle.getDouble(4)
            }
        }

        if (pdc <= 0.0) return null
        return GannCalculator.calculate(pdc)
    }

    private suspend fun getPriorTradingDate(viewModel: AuthViewModel, appId: String, token: String, targetDateStr: String, indexSymbol: String): String {
        val targetLocalDate = try { LocalDate.parse(targetDateStr) } catch (_: Exception) { LocalDate.now() }
        val startLocalDate = targetLocalDate.minusDays(7)
        val priorDayEnd = targetLocalDate.minusDays(1)

        delay(200)
        val history = viewModel.fetchHistoricalData(
            appId = appId,
            accessToken = token,
            symbol = indexSymbol,
            resolution = "D",
            rangeFrom = startLocalDate.toString(),
            rangeTo = priorDayEnd.toString()
        )

        if (history != null && history.has("candles")) {
            val candles = history.getJSONArray("candles")
            if (candles.length() > 0) {
                val lastCandle = candles.getJSONArray(candles.length() - 1)
                val timestampEpoch = lastCandle.getLong(0)
                val instant = Instant.ofEpochSecond(timestampEpoch)
                val priorTradingDate = LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate().toString()
                return priorTradingDate
            }
        }

        return priorDayEnd.toString()
    }
}

class OkHttpFyersWebSocketClient(
    private val appId: String,
    private val accessToken: String,
    private val subscribedSymbols: List<String>,
    private val tradingEngine: PaperTradingEngine
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
                    println("${json.optString("symbol", "")} ${json.optDouble("ltp", 0.0)}")
                    val symbol = json.optString("symbol", "")
                    val ltp = json.optDouble("ltp", 0.0)
                    if (symbol.isNotEmpty() && ltp > 0.0) {
                        tradingEngine.onTick(symbol, ltp)
                    }
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

    class TradingWebSocketListener(
        private val tradingEngine: PaperTradingEngine,
        private val subscribedSymbols: List<String>
    ) : FyersSocketDelegate {

        private var fyersSocket: FyersSocket? = null

        fun start() {
            try {
                fyersSocket = FyersSocket(3).apply {
                    webSocketDelegate = this@TradingWebSocketListener
                    ConnectHSM(ChannelModes.LITE)
                }
            } catch (e: Exception) {
                println("WebSocket Start Error: ${e.message}")
            }
        }

        override fun OnIndex(index: JSONObject?) {}
        override fun OnScrips(scrips: JSONObject?) {
//            println("${scrips?.optString("symbol", "")} ${scrips?.optDouble("ltp", 0.0)}")
            if (scrips != null) {
                val symbol = scrips.optString("symbol", "")
                val ltp = scrips.optDouble("ltp", 0.0)
                if (symbol.isNotEmpty() && ltp > 0.0) {
                    tradingEngine.onTick(symbol, ltp)
                }
            }
        }
        override fun OnDepth(depths: JSONObject?) {}
        override fun OnOrder(orders: JSONObject?) {}
        override fun OnTrade(trades: JSONObject?) {}
        override fun OnPosition(positions: JSONObject?) {}
        override fun OnOpen(status: String?) {
            println("WebSocket Connected: $status")
            try {
                fyersSocket?.SubscribeData(subscribedSymbols)
                println("WebSocket subscribed to symbols: $subscribedSymbols")
            } catch (e: Exception) {
                println("WebSocket Subscribe Error: ${e.message}")
            }
        }
        override fun OnClose(status: String?) {
            println("WebSocket Closed: $status")
            CoroutineScope(Dispatchers.IO).launch {
                TelegramNotifier.sendAlert("⚠️ [WebSocket Closed] $status")
            }
        }
        override fun OnError(error: JSONObject?) {
            println("WebSocket Error: $error")
            CoroutineScope(Dispatchers.IO).launch {
                TelegramNotifier.sendAlert("❌ [WebSocket Error] $error")
            }
        }
        override fun OnMessage(message: JSONObject?) {}
    }

