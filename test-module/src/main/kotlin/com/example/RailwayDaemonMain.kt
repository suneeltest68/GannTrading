package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.TelegramNotifier
import com.example.service.FyersSdkWebSocketClient
import com.sun.net.httpserver.HttpServer
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Duration
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

data class StrangleConfig(
    val qty: Int,
    val sellPESymbol: String,
    val sellPEEntry: Double,
    val sellCESymbol: String,
    val sellCEEntry: Double,
    val targetProfit: Double
)

var activeStrangle: StrangleConfig? = null
var peLTP: Double = 0.0
var ceLTP: Double = 0.0
var strangleStatus: String = "IDLE" // IDLE, MONITORING, TARGET_HIT, RATIO_1_3_HIT
var ratioAlertSent: Boolean = false
var targetHitCount: Int = 0
var ratioHitCount: Int = 0
var wsConnectionStatus: String = "IDLE"
var activeWsClient: FyersSdkWebSocketClient? = null

val strangleFile = File("active_strangle.json")

fun saveStrangleState(config: StrangleConfig) {
    try {
        val json = JSONObject().apply {
            put("qty", config.qty)
            put("sellPESymbol", config.sellPESymbol)
            put("sellPEEntry", config.sellPEEntry)
            put("sellCESymbol", config.sellCESymbol)
            put("sellCEEntry", config.sellCEEntry)
            put("targetProfit", config.targetProfit)
        }
        strangleFile.writeText(json.toString(4))
    } catch (_: Exception) {}
}

fun loadStrangleState(): StrangleConfig? {
    if (!strangleFile.exists()) return null
    try {
        val json = JSONObject(strangleFile.readText())
        return StrangleConfig(
            qty = json.optInt("qty", 50),
            sellPESymbol = json.optString("sellPESymbol"),
            sellPEEntry = json.optDouble("sellPEEntry"),
            sellCESymbol = json.optString("sellCESymbol"),
            sellCEEntry = json.optDouble("sellCEEntry"),
            targetProfit = json.optDouble("targetProfit")
        )
    } catch (_: Exception) {
        return null
    }
}

fun clearStrangleState() {
    if (strangleFile.exists()) strangleFile.delete()
    activeStrangle = null
    strangleStatus = "IDLE"
    ratioAlertSent = false
    targetHitCount = 0
    ratioHitCount = 0
    activeWsClient?.stop()
    activeWsClient = null
    wsConnectionStatus = "CLOSED"
}

val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
fun isMonthlyExpirySymbol(symbol: String): Boolean {
    if (!symbol.contains("NIFTY")) return false
    return MONTH_NAMES.any { symbol.contains(it) } && (symbol.endsWith("PE") || symbol.endsWith("CE"))
}

fun sendStrangleStartAlert(config: StrangleConfig) {
    val peF = String.format(java.util.Locale.US, "%.2f", peLTP)
    val ceF = String.format(java.util.Locale.US, "%.2f", ceLTP)
    val msg = """
        🟢 [Short Strangle Positional Bot - Monitoring Started]
        • Quantity: ${config.qty} | Target: ₹${config.targetProfit}
        • Sell PE (${config.sellPESymbol}): Entry ₹${config.sellPEEntry} | Live LTP ₹$peF
        • Sell CE (${config.sellCESymbol}): Entry ₹${config.sellCEEntry} | Live LTP ₹$ceF
        Status: WebSocket connected & streaming live ticks.
    """.trimIndent()
    CoroutineScope(Dispatchers.IO).launch { TelegramNotifier.sendAlert(msg) }
}

fun handleStrangleTick(symbol: String, ltp: Double) {
    if (ltp <= 0.0) {
        println("[Bad Tick Ignored] Symbol: $symbol, LTP: $ltp")
        return
    }

    val strangle = activeStrangle ?: return
    if (strangleStatus == "TARGET_HIT") return

    if (symbol == strangle.sellPESymbol) peLTP = ltp
    else if (symbol == strangle.sellCESymbol) ceLTP = ltp

    // 1:3 LTP Difference Check (Requires 2 consecutive confirming ticks to avoid false spikes)
    if (peLTP > 0.0 && ceLTP > 0.0 && !ratioAlertSent) {
        val r1 = peLTP / ceLTP
        val r2 = ceLTP / peLTP
        if (r1 >= 3.0 || r2 >= 3.0) {
            ratioHitCount++
            println("[Ratio Check] Tentative 1:3 ratio hit count: $ratioHitCount (PE LTP: $peLTP, CE LTP: $ceLTP)")
            if (ratioHitCount >= 2) {
                ratioAlertSent = true
                strangleStatus = "RATIO_1_3_HIT"
                val peF = String.format(java.util.Locale.US, "%.2f", peLTP)
                val ceF = String.format(java.util.Locale.US, "%.2f", ceLTP)
                val msg = """
                    ⚠️ [Short Strangle Positional Bot - 1:3 LTP Ratio Reached!]
                    • Sell PE (${strangle.sellPESymbol}): LTP ₹$peF (Entry ₹${strangle.sellPEEntry})
                    • Sell CE (${strangle.sellCESymbol}): LTP ₹$ceF (Entry ₹${strangle.sellCEEntry})
                    Rebalance or adjust legs accordingly.
                """.trimIndent()
                CoroutineScope(Dispatchers.IO).launch { TelegramNotifier.sendAlert(msg) }
            }
        } else {
            ratioHitCount = 0
        }
    }

    // Calculate combined PnL for Short Strangle
    val pePnl = (strangle.sellPEEntry - peLTP) * strangle.qty
    val cePnl = (strangle.sellCEEntry - ceLTP) * strangle.qty
    val totalPnl = pePnl + cePnl

    // Target Profit Check (Requires 2 consecutive confirming ticks to prevent glitch spikes)
    if (totalPnl >= strangle.targetProfit) {
        targetHitCount++
        println("[Target Check] Tentative target hit count: $targetHitCount (P&L: ₹$totalPnl, Target: ₹${strangle.targetProfit})")
        if (targetHitCount >= 2) {
            strangleStatus = "TARGET_HIT"
            val targetF = String.format(java.util.Locale.US, "%.2f", strangle.targetProfit)
            val pnlF = String.format(java.util.Locale.US, "%.2f", totalPnl)
            val peEntryF = String.format(java.util.Locale.US, "%.2f", strangle.sellPEEntry)
            val peLtpF = String.format(java.util.Locale.US, "%.2f", peLTP)
            val ceEntryF = String.format(java.util.Locale.US, "%.2f", strangle.sellCEEntry)
            val ceLtpF = String.format(java.util.Locale.US, "%.2f", ceLTP)

            val msg = """
                🎯 [Short Strangle Positional Bot - Target REACHED!] Target profit hit!
                • Target: ₹$targetF | P&L: ₹$pnlF (Qty: ${strangle.qty})
                • Sell PE (${strangle.sellPESymbol}): Entry ₹$peEntryF -> Exit LTP ₹$peLtpF
                • Sell CE (${strangle.sellCESymbol}): Entry ₹$ceEntryF -> Exit LTP ₹$ceLtpF
            """.trimIndent()
            CoroutineScope(Dispatchers.IO).launch { TelegramNotifier.sendAlert(msg) }
            clearStrangleState()
        }
    } else {
        targetHitCount = 0
    }
}

fun main() = runBlocking {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val stackTrace = throwable.stackTraceToString().take(1500)
        val errorMessage = "🚨 [FATAL CRASH] Thread '${thread.name}' crashed:\n${throwable.message}\n\n$stackTrace"
        println(errorMessage)
        try {
            runBlocking {
                TelegramNotifier.sendAlert(errorMessage)
            }
        } catch (_: Exception) {}
    }

    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()
    val istZone = ZoneId.of("Asia/Kolkata")

    println("==================================================")
    println("🚀 SHORT STRANGLE POSITIONAL BOT + PERSISTENCE")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"]
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"]
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"]
    val portStr = System.getenv("PORT") ?: "8080"
    val port = portStr.toIntOrNull() ?: 8080

    if (appId.isNullOrEmpty() || secretKey.isNullOrEmpty() || redirectUri.isNullOrEmpty()) {
        println("❌ Error: Missing required Fyers environment variables.")
        return@runBlocking
    }

    var authCodeDeferred: CompletableDeferred<String>? = null

    // Start background Telegram Polling for /auth command
    CoroutineScope(Dispatchers.IO).launch {
        var lastUpdateId = 0L
        while (true) {
            try {
                val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: dotenv["TELEGRAM_BOT_TOKEN"]
                if (!botToken.isNullOrEmpty() && botToken != "YOUR_BOT_TOKEN_HERE") {
                    val url = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=30")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 35_000
                    conn.readTimeout = 35_000
                    if (conn.responseCode == 200) {
                        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseStr)
                        if (json.optBoolean("ok", false)) {
                            val resultArray = json.optJSONArray("result")
                            if (resultArray != null) {
                                for (i in 0 until resultArray.length()) {
                                    val update = resultArray.getJSONObject(i)
                                    val updateId = update.optLong("update_id", 0L)
                                    if (updateId > lastUpdateId) {
                                        lastUpdateId = updateId
                                    }
                                    val message = update.optJSONObject("message")
                                    val text = message?.optString("text", "")?.trim() ?: ""
                                    if (text.equals("/auth", ignoreCase = true)) {
                                        println("[Telegram] Received /auth command! Clearing token and triggering fresh authentication...")
                                        viewModel.clearToken()
                                        viewModel.prepareLoginUrl(appId, redirectUri)
                                        val loginUrl = viewModel.authState.value.loginUrl
                                        TelegramNotifier.sendAlert("🔐 [Short Strangle Positional Bot - Fyers Auth Requested] Old token cleared. Please click below to login and authorize:\n$loginUrl")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(5000L)
        }
    }

    // Start 24/7 Background HTTP Server FIRST so port is listening immediately (fixes 502 Bad Gateway)
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path?.removeSuffix("/") ?: ""
        try {
            if (path == "/callback") {
                val query = exchange.requestURI.query ?: ""
                val queryParams = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to 
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    } else {
                        "" to ""
                    }
                }
                val code = queryParams["s_code"] ?: queryParams["auth_code"] ?: queryParams["code"]
                val responseHtml = if (!code.isNullOrEmpty()) {
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: green;'>✅ Fyers Authentication Successful!</h1><p>Authorization code received. You can now close this tab.</p></body></html>"
                } else {
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: red;'>❌ Authentication Failed</h1><p>No authorization code found.</p></body></html>"
                }
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }

                if (!code.isNullOrEmpty()) {
                    authCodeDeferred?.complete(code)
                }
            } else if (path == "/auth") {
                viewModel.prepareLoginUrl(appId, redirectUri)
                val loginUrl = viewModel.authState.value.loginUrl
                val html = "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1>🔐 Fyers Authentication</h1><p>Click below to authorize:</p><a href='$loginUrl' style='background: #0052FF; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block; margin-top: 20px;'>Login with Fyers</a></body></html>"
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/api/start-strangle") {
                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val config = StrangleConfig(
                        qty = json.optInt("qty", 50),
                        sellPESymbol = json.optString("sellPESymbol"),
                        sellPEEntry = json.optDouble("sellPEEntry"),
                        sellCESymbol = json.optString("sellCESymbol"),
                        sellCEEntry = json.optDouble("sellCEEntry"),
                        targetProfit = json.optDouble("targetProfit")
                    )

                    activeStrangle = config
                    peLTP = config.sellPEEntry
                    ceLTP = config.sellCEEntry
                    strangleStatus = "MONITORING"
                    ratioAlertSent = false
                    targetHitCount = 0
                    ratioHitCount = 0
                    saveStrangleState(config)

                    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                    if (token.isNotEmpty()) {
                        activeWsClient?.stop()
                        activeWsClient = FyersSdkWebSocketClient(
                            appId = appId,
                            accessToken = token,
                            subscribedSymbols = listOf(config.sellPESymbol, config.sellCESymbol),
                            onTick = { symbol, ltp -> handleStrangleTick(symbol, ltp) },
                            onOpen = { 
                                wsConnectionStatus = "CONNECTED"
                                println("[WebSocket] Connected successfully!")
                                activeStrangle?.let { sendStrangleStartAlert(it) }
                            },
                            onError = { wsConnectionStatus = "FAILED" },
                            onClose = { wsConnectionStatus = "CLOSED" }
                        )
                        activeWsClient?.start()
                    }

                    val resp = JSONObject().put("status", "success").toString()
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    val bytes = resp.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } else if (path == "/api/stop-strangle") {
                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    clearStrangleState()
                    println("[StopStrangle] Active strangle stopped, WebSocket disconnected, and state cleared.")
                    val resp = JSONObject().put("status", "success").toString()
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    val bytes = resp.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } else if (path == "/api/update-strangle") {
                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val config = StrangleConfig(
                        qty = json.optInt("qty", activeStrangle?.qty ?: 50),
                        sellPESymbol = json.optString("sellPESymbol", activeStrangle?.sellPESymbol ?: ""),
                        sellPEEntry = json.optDouble("sellPEEntry", activeStrangle?.sellPEEntry ?: 0.0),
                        sellCESymbol = json.optString("sellCESymbol", activeStrangle?.sellCESymbol ?: ""),
                        sellCEEntry = json.optDouble("sellCEEntry", activeStrangle?.sellCEEntry ?: 0.0),
                        targetProfit = json.optDouble("targetProfit", activeStrangle?.targetProfit ?: 10000.0)
                    )

                    activeStrangle = config
                    peLTP = config.sellPEEntry
                    ceLTP = config.sellCEEntry
                    strangleStatus = "MONITORING"
                    ratioAlertSent = false
                    targetHitCount = 0
                    ratioHitCount = 0
                    saveStrangleState(config)

                    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                    if (token.isNotEmpty()) {
                        activeWsClient?.stop()
                        activeWsClient = FyersSdkWebSocketClient(
                            appId = appId,
                            accessToken = token,
                            subscribedSymbols = listOf(config.sellPESymbol, config.sellCESymbol),
                            onTick = { symbol, ltp -> handleStrangleTick(symbol, ltp) },
                            onOpen = { 
                                wsConnectionStatus = "CONNECTED"
                                println("[WebSocket] Connected successfully!")
                                activeStrangle?.let { sendStrangleStartAlert(it) }
                            },
                            onError = { wsConnectionStatus = "FAILED" },
                            onClose = { wsConnectionStatus = "CLOSED" }
                        )
                        activeWsClient?.start()
                    }

                    val msg = """
                        🔄 [Short Strangle Positional Bot - Position Adjusted & Updated]
                        • Sell PE (${config.sellPESymbol}): New Entry ₹${config.sellPEEntry}
                        • Sell CE (${config.sellCESymbol}): New Entry ₹${config.sellCEEntry}
                        • Quantity: ${config.qty} | Target: ₹${config.targetProfit}
                    """.trimIndent()
                    CoroutineScope(Dispatchers.IO).launch { TelegramNotifier.sendAlert(msg) }

                    val resp = JSONObject().put("status", "success").toString()
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    val bytes = resp.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } else if (path == "/api/strangle-status") {
                val strangle = activeStrangle
                val peCurrent = if (peLTP > 0.0) peLTP else (strangle?.sellPEEntry ?: 0.0)
                val ceCurrent = if (ceLTP > 0.0) ceLTP else (strangle?.sellCEEntry ?: 0.0)
                val qty = strangle?.qty ?: 50
                val pnl = if (strangle != null) ((strangle.sellPEEntry - peCurrent) + (strangle.sellCEEntry - ceCurrent)) * qty else 0.0

                val respJson = JSONObject().apply {
                    put("active", strangle != null)
                    put("sellPESymbol", strangle?.sellPESymbol ?: "")
                    put("sellPEEntry", strangle?.sellPEEntry ?: 0.0)
                    put("sellPELTP", peCurrent)
                    put("sellCESymbol", strangle?.sellCESymbol ?: "")
                    put("sellCEEntry", strangle?.sellCEEntry ?: 0.0)
                    put("sellCELTP", ceCurrent)
                    put("qty", qty)
                    put("targetProfit", strangle?.targetProfit ?: 0.0)
                    put("pnl", pnl)
                    put("status", strangleStatus)
                }.toString()

                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = respJson.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/api/search-monthly") {
                println("[HTTP] Received request to /api/search-monthly")
                val query = exchange.requestURI.query ?: ""
                val queryParams = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        try { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) } catch (_: Exception) { "" to "" }
                    } else "" to ""
                }
                val q = queryParams["q"] ?: ""
                val type = queryParams["type"] ?: "PE" // PE or CE

                val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                val symbolsList = org.json.JSONArray()

                println("[SearchMonthly] Query: '$q', Type: $type, Token present: ${token.isNotEmpty()}, AppId present: ${appId.isNotEmpty()}")

                if (token.isNotEmpty() && appId.isNotEmpty()) {
                    var chainJson: JSONObject? = null
                    try {
                        chainJson = runBlocking { viewModel.fetchOptionChain(appId, token, "NSE:NIFTY50-INDEX", 40) }
                        println("[SearchMonthly] NIFTY50-INDEX code: ${chainJson?.optInt("code")}")
                    } catch (e: Exception) {
                        println("[SearchMonthly] NIFTY50-INDEX exception: ${e.message}")
                    }

                    if (chainJson == null || chainJson.optInt("code") != 200) {
                        try {
                            chainJson = runBlocking { viewModel.fetchOptionChain(appId, token, "NSE:NIFTY50", 40) }
                            println("[SearchMonthly] NIFTY50 code: ${chainJson?.optInt("code")}")
                        } catch (e: Exception) {
                            println("[SearchMonthly] NIFTY50 exception: ${e.message}")
                        }
                    }

                    if (chainJson == null || chainJson.optInt("code") != 200) {
                        try {
                            chainJson = runBlocking { viewModel.fetchOptionChain(appId, token, "NSE:NIFTY", 40) }
                            println("[SearchMonthly] NIFTY code: ${chainJson?.optInt("code")}")
                        } catch (e: Exception) {
                            println("[SearchMonthly] NIFTY exception: ${e.message}")
                        }
                    }

                    if (chainJson != null && chainJson.has("data")) {
                        val dataObj = chainJson.getJSONObject("data")
                        val optionsChain = dataObj.optJSONArray("optionsChain")
                        println("[SearchMonthly] optionsChain length: ${optionsChain?.length() ?: 0}")
                        if (optionsChain != null) {
                            for (i in 0 until optionsChain.length()) {
                                val item = optionsChain.optJSONObject(i)
                                if (item != null) {
                                    val sym = item.optString("symbol", "")
                                    val optType = item.optString("option_type", "")
                                    val strike = item.optInt("strike_price", -1)
                                    
                                    val matchesType = optType.equals(type, ignoreCase = true) || sym.endsWith(type, ignoreCase = true)
                                    val matchesQuery = q.isEmpty() || sym.contains(q, ignoreCase = true) || (strike > 0 && strike.toString().contains(q))

                                    if (sym.isNotEmpty() && sym.contains("NIFTY") && matchesType && matchesQuery) {
                                        var exists = false
                                        for (j in 0 until symbolsList.length()) {
                                            if (symbolsList.getString(j) == sym) exists = true
                                        }
                                        if (!exists) symbolsList.put(sym)
                                    }
                                }
                            }
                        }
                    }
                }

                println("[SearchMonthly] Found ${symbolsList.length()} symbols matching '$q' for $type")
                val respJson = symbolsList.toString()
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = respJson.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val file = listOf(
                    File("journal-web/index.html"),
                    File("../journal-web/index.html"),
                    File("test-module/journal-web/index.html")
                ).firstOrNull { it.exists() } ?: File("journal-web/index.html")
                val htmlStr = if (file.exists()) file.readText() else "<h1>Short Strangle Bot</h1>"
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = htmlStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            val err = "Error: ${e.message}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
        }
    }
    server.setExecutor(null)
    server.start()
    println("[HTTP Server] Running on port $port (Strangle UI: /, API: /api/start-strangle, /api/strangle-status)")

    // Run startup persistence, token check, and WebSocket restart asynchronously in background (prevents 502 Bad Gateway)
    CoroutineScope(Dispatchers.IO).launch {
        val persistedStrangle = loadStrangleState()
        if (persistedStrangle != null) {
            activeStrangle = persistedStrangle
            peLTP = persistedStrangle.sellPEEntry
            ceLTP = persistedStrangle.sellCEEntry
            strangleStatus = "MONITORING"
            println("📂 Loaded active positional strangle from persistence: $persistedStrangle")
        }

        var activeToken = System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: viewModel.getCachedToken()
        if (activeToken.isNullOrEmpty()) {
            println("[Auth] Startup: Access token missing. Requesting OAuth login...")
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            TelegramNotifier.sendAlert("🔐 [Short Strangle Positional Bot - Fyers Auth Required on Startup] Access token not found. Please click below to login and authorize:\n$loginUrl")

            authCodeDeferred = CompletableDeferred()
            val authCode = try {
                withTimeout(60 * 60 * 1000L) { authCodeDeferred?.await() }
            } catch (_: Exception) { null }
            authCodeDeferred = null

            if (authCode != null) {
                println("[Auth] Exchanging received auth code for access token on startup...")
                viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
                val finalState = viewModel.authState.value
                activeToken = finalState.accessToken
                if (activeToken != null) {
                    TelegramNotifier.sendAlert("✅ [Short Strangle Positional Bot] Startup Access Token acquired successfully via web callback!")
                } else {
                    TelegramNotifier.sendAlert("❌ [Short Strangle Positional Bot] Token exchange failed: ${finalState.errorMessage}")
                }
            }
        }

        val strangle = activeStrangle
        if (strangle != null && !activeToken.isNullOrEmpty()) {
            activeWsClient?.stop()
            activeWsClient = FyersSdkWebSocketClient(
                appId = appId,
                accessToken = activeToken!!,
                subscribedSymbols = listOf(strangle.sellPESymbol, strangle.sellCESymbol),
                onTick = { symbol, ltp -> handleStrangleTick(symbol, ltp) },
                onOpen = { 
                    wsConnectionStatus = "CONNECTED"
                    println("[WebSocket] Connected successfully!")
                    activeStrangle?.let { sendStrangleStartAlert(it) }
                },
                onError = { wsConnectionStatus = "FAILED" },
                onClose = { wsConnectionStatus = "CLOSED" }
            )
            activeWsClient?.start()
            println("🔄 Resumed positional WebSocket monitoring for: ${strangle.sellPESymbol}, ${strangle.sellCESymbol}")
        }
    }
}
