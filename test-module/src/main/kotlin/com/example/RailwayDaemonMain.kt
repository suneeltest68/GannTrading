package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.TelegramNotifier
import com.example.service.OkHttpFyersWebSocketClient
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

data class SpreadConfig(
    val strategyType: String,
    val sellSymbol: String,
    val sellEntry: Double,
    val sellSL: Double,
    val buySymbol: String,
    val buyEntry: Double,
    val targetProfit: Double
)

var activeSpread: SpreadConfig? = null
var sellLTP: Double = 0.0
var buyLTP: Double = 0.0
var spreadStatus: String = "IDLE" // IDLE, MONITORING, SL_HIT, TARGET_HIT
var activeWsClient: OkHttpFyersWebSocketClient? = null

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
    println("🚀 OPTIONS SPREAD MONITOR + DAILY 9AM AUTH - RAILWAY DAEMON")
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
                                        TelegramNotifier.sendAlert("🔐 [Fyers Auth Requested via Telegram] Old token cleared. Please click below to login and authorize:\n$loginUrl")
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

    var authCodeDeferred: CompletableDeferred<String>? = null

    // Start 24/7 Background HTTP Server for Web UI Dashboard and REST APIs
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
            } else if (path == "/api/start-spread") {
                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val config = SpreadConfig(
                        strategyType = json.optString("strategyType", "BullPut"),
                        sellSymbol = json.optString("sellSymbol"),
                        sellEntry = json.optDouble("sellEntry"),
                        sellSL = json.optDouble("sellSL"),
                        buySymbol = json.optString("buySymbol"),
                        buyEntry = json.optDouble("buyEntry"),
                        targetProfit = json.optDouble("targetProfit")
                    )

                    activeSpread = config
                    sellLTP = config.sellEntry
                    buyLTP = config.buyEntry
                    spreadStatus = "MONITORING"

                    // Start WebSocket streaming for both symbols using current active token
                    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                    if (token.isNotEmpty()) {
                        activeWsClient?.stop()
                        activeWsClient = OkHttpFyersWebSocketClient(appId, token, listOf(config.sellSymbol, config.buySymbol)) { symbol, ltp ->
                            val spread = activeSpread ?: return@OkHttpFyersWebSocketClient
                            if (spreadStatus == "SL_HIT" || spreadStatus == "TARGET_HIT") return@OkHttpFyersWebSocketClient

                            if (symbol == spread.sellSymbol) {
                                sellLTP = ltp
                                // Check Sell Strike SL
                                if (ltp >= spread.sellSL) {
                                    spreadStatus = "SL_HIT"
                                    val msg = "🚨 [Spread SL HIT!] ${spread.strategyType} Sell Leg (${spread.sellSymbol}) hit Stop Loss!\nEntry: ₹${spread.sellEntry} | SL: ₹${spread.sellSL} | Current LTP: ₹$ltp"
                                    println(msg)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        TelegramNotifier.sendAlert(msg)
                                    }
                                }
                            } else if (symbol == spread.buySymbol) {
                                buyLTP = ltp
                            }

                            // Calculate spread PnL
                            val sellPnl = spread.sellEntry - sellLTP
                            val buyPnl = buyLTP - spread.buyEntry
                            val totalPnl = sellPnl + buyPnl

                            // Check Target Profit
                            if (totalPnl >= spread.targetProfit) {
                                spreadStatus = "TARGET_HIT"
                                val msg = "🎯 [Spread Target REACHED!] ${spread.strategyType} target profit hit!\nTarget: ₹${spread.targetProfit} | Current PnL: ₹$totalPnl"
                                println(msg)
                                CoroutineScope(Dispatchers.IO).launch {
                                    TelegramNotifier.sendAlert(msg)
                                }
                            }
                        }
                        activeWsClient?.start()
                    }

                    val resp = JSONObject().put("status", "success").toString()
                    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    val bytes = resp.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } else if (path == "/api/spread-status") {
                val spread = activeSpread
                val sellCurrent = if (sellLTP > 0.0) sellLTP else (spread?.sellEntry ?: 0.0)
                val buyCurrent = if (buyLTP > 0.0) buyLTP else (spread?.buyEntry ?: 0.0)
                val pnl = if (spread != null) (spread.sellEntry - sellCurrent) + (buyCurrent - spread.buyEntry) else 0.0

                val respJson = JSONObject().apply {
                    put("active", spread != null)
                    put("strategyType", spread?.strategyType ?: "")
                    put("sellSymbol", spread?.sellSymbol ?: "")
                    put("sellEntry", spread?.sellEntry ?: 0.0)
                    put("sellSL", spread?.sellSL ?: 0.0)
                    put("sellLTP", sellCurrent)
                    put("buySymbol", spread?.buySymbol ?: "")
                    put("buyEntry", spread?.buyEntry ?: 0.0)
                    put("buyLTP", buyCurrent)
                    put("targetProfit", spread?.targetProfit ?: 0.0)
                    put("pnl", pnl)
                    put("status", spreadStatus)
                }.toString()

                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = respJson.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                // Serve journal-web/index.html
                val file = listOf(
                    File("journal-web/index.html"),
                    File("../journal-web/index.html"),
                    File("test-module/journal-web/index.html")
                ).firstOrNull { it.exists() } ?: File("journal-web/index.html")
                val htmlStr = if (file.exists()) file.readText() else "<h1>Options Spread Monitor</h1>"
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
    println("[HTTP Server] Running on port $port (Spread UI: /, API: /api/start-spread, /api/spread-status)")

    var isFirstRun = true

    // Daily 9:00 AM IST Authentication Scheduler Loop
    while (true) {
        if (!isFirstRun) {
            val now = ZonedDateTime.now(istZone)
            val targetTimeToday = now.withHour(9).withMinute(0).withSecond(0).withNano(0)

            val nextRun = if (now.isAfter(targetTimeToday)) {
                targetTimeToday.plusDays(1)
            } else {
                targetTimeToday
            }

            val delayMillis = Duration.between(now, nextRun).toMillis()
            println("\n[Daemon] Next daily 9:00 AM IST auth scheduled for: $nextRun (in ${delayMillis / 1000 / 60} minutes)")
            delay(delayMillis)
        } else {
            println("\n[Daemon] First startup: Running daily auth routine immediately...")
            isFirstRun = false
        }

        // Daily 9:00 AM IST Trigger: Clear token & reset active spread
        viewModel.clearToken()
        activeSpread = null
        spreadStatus = "IDLE"

        var activeToken = System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]

        if (activeToken.isNullOrEmpty()) {
            println("[Auth] Daily 9 AM: Access token cleared. Waiting for OAuth login...")
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            TelegramNotifier.sendAlert("⚠️ [Fyers Railway Daemon] Daily 9:00 AM Auth Required! Access token cleared. Please login & authorize:\n$loginUrl")

            authCodeDeferred = CompletableDeferred()
            val authCode = try {
                withTimeout(60 * 60 * 1000L) { authCodeDeferred?.await() }
            } catch (_: Exception) { null }
            authCodeDeferred = null

            if (authCode != null) {
                println("[Auth] Exchanging received auth code for access token...")
                viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
                val finalState = viewModel.authState.value
                if (finalState.accessToken != null) {
                    TelegramNotifier.sendAlert("✅ [Fyers Railway Daemon] Daily Access Token acquired successfully via web callback!")
                } else {
                    TelegramNotifier.sendAlert("❌ [Fyers Railway Daemon] Token exchange failed: ${finalState.errorMessage}")
                }
            } else {
                TelegramNotifier.sendAlert("❌ [Fyers Railway Daemon] Authentication timed out waiting for web callback.")
            }
        }

        delay(60 * 60 * 1000L)
    }
}
