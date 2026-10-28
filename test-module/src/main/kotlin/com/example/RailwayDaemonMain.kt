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

data class SpreadConfig(
    val sellSymbol: String,
    val sellEntry: Double,
    val sellSL: Double,
    val buySymbol: String,
    val buyEntry: Double,
    val targetProfit: Double,
    val quantity: Int
)

var activeSpread: SpreadConfig? = null
var sellLTP: Double = 0.0
var buyLTP: Double = 0.0
var spreadStatus: String = "IDLE" // IDLE, MONITORING, SL_HIT, TARGET_HIT, CLOSED_12PM
var wsConnectionStatus: String = "IDLE" // IDLE, WAITING, CONNECTED, FAILED, CLOSED
var activeWsClient: FyersSdkWebSocketClient? = null

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
    println("🚀 OPTIONS SPREAD MONITOR + WS STATUS TOAST")
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

    fun endSessionAndCleanup() {
        println("[Daemon] Session ended. Closing all WebSockets, clearing tokens, and deleting cache files...")
        activeWsClient?.stop()
        activeWsClient = null
        wsConnectionStatus = "CLOSED"
        viewModel.clearToken()
        File("token.json").takeIf { it.exists() }?.delete()
        File("fyers_token.json").takeIf { it.exists() }?.delete()
        activeSpread = null
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
                println("[StartSpread] Received POST request to /api/start-spread")
                if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    println("[StartSpread] Raw body: $body")
                    val json = JSONObject(body)
                    val config = SpreadConfig(
                        sellSymbol = json.optString("sellSymbol"),
                        sellEntry = json.optDouble("sellEntry"),
                        sellSL = json.optDouble("sellSL"),
                        buySymbol = json.optString("buySymbol"),
                        buyEntry = json.optDouble("buyEntry"),
                        targetProfit = json.optDouble("targetProfit"),
                        quantity = json.optInt("quantity", 25)
                    )
                    println("[StartSpread] Parsed config successfully: $config")

                    activeSpread = config
                    sellLTP = config.sellEntry
                    buyLTP = config.buyEntry
                    spreadStatus = "MONITORING"
                    wsConnectionStatus = "WAITING"

                    // Start WebSocket streaming for both symbols using current active token
                    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                    println("[StartSpread] Access token present: ${token.isNotEmpty()} (AppId: $appId)")

                    if (token.isEmpty()) {
                        wsConnectionStatus = "FAILED"
                        val errBytes = "Error: Fyers access token is missing. Please click 'Fyers Auth' at the top right to login and authorize first.".toByteArray(StandardCharsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                        exchange.sendResponseHeaders(400, errBytes.size.toLong())
                        exchange.responseBody.use { it.write(errBytes) }
                        println("[StartSpread] Rejected 400: Access token is empty.")
                        return@createContext
                    }

                    if (token.isNotEmpty()) {
                        activeWsClient?.stop()
                        activeWsClient = FyersSdkWebSocketClient(
                            appId = appId,
                            accessToken = token,
                            subscribedSymbols = listOf(config.sellSymbol, config.buySymbol),
                            onTick = { symbol, ltp ->
                                println("[Tick Received] $symbol -> LTP: $ltp")
                                val spread = activeSpread ?: return@FyersSdkWebSocketClient
                                if (spreadStatus == "SL_HIT" || spreadStatus == "TARGET_HIT" || spreadStatus == "CLOSED_12PM") return@FyersSdkWebSocketClient

                                if (symbol == spread.sellSymbol) {
                                    sellLTP = ltp
                                    // Check Sell Strike SL
                                    if (ltp >= spread.sellSL) {
                                        spreadStatus = "SL_HIT"
                                        val msg = "🚨 [Spread SL HIT!] Sell Leg (${spread.sellSymbol}) hit Stop Loss!\nEntry: ₹${spread.sellEntry} | SL: ₹${spread.sellSL} | Current LTP: ₹$ltp"
                                        println(msg)
                                        CoroutineScope(Dispatchers.IO).launch {
                                            TelegramNotifier.sendAlert(msg)
                                        }
                                        endSessionAndCleanup()
                                    }
                                } else if (symbol == spread.buySymbol) {
                                    buyLTP = ltp
                                }

                                // Calculate spread PnL scaled by quantity
                                val sellPnl = (spread.sellEntry - sellLTP) * spread.quantity
                                val buyPnl = (buyLTP - spread.buyEntry) * spread.quantity
                                val totalPnl = sellPnl + buyPnl

                                // Check Target Profit
                                if (totalPnl >= spread.targetProfit) {
                                    spreadStatus = "TARGET_HIT"
                                    val msg = "🎯 [Spread Target REACHED!] Target profit hit!\nTarget: ₹${spread.targetProfit} | Current PnL: ₹$totalPnl (Qty: ${spread.quantity})"
                                    println(msg)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        TelegramNotifier.sendAlert(msg)
                                    }
                                    endSessionAndCleanup()
                                }
                            },
                            onOpen = { 
                                wsConnectionStatus = "CONNECTED"
                                println("[WebSocket] Connected successfully!")
                            },
                            onError = { 
                                wsConnectionStatus = "FAILED"
                                println("[WebSocket] Connection error / failure!")
                            },
                            onClose = { 
                                wsConnectionStatus = "CLOSED"
                                println("[WebSocket] Connection closed.")
                            }
                        )
                        activeWsClient?.start()
                        println("[StartSpread] WebSocket client started successfully for symbols: ${config.sellSymbol}, ${config.buySymbol}")
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
                val qty = spread?.quantity ?: 1
                val pnl = if (spread != null) ((spread.sellEntry - sellCurrent) + (buyCurrent - spread.buyEntry)) * qty else 0.0

                val respJson = JSONObject().apply {
                    put("active", spread != null)
                    put("sellSymbol", spread?.sellSymbol ?: "")
                    put("sellEntry", spread?.sellEntry ?: 0.0)
                    put("sellSL", spread?.sellSL ?: 0.0)
                    put("sellLTP", sellCurrent)
                    put("buySymbol", spread?.buySymbol ?: "")
                    put("buyEntry", spread?.buyEntry ?: 0.0)
                    put("buyLTP", buyCurrent)
                    put("targetProfit", spread?.targetProfit ?: 0.0)
                    put("quantity", qty)
                    put("pnl", pnl)
                    put("status", spreadStatus)
                    put("wsStatus", wsConnectionStatus)
                }.toString()

                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = respJson.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/api/search-symbols") {
                val query = exchange.requestURI.query ?: ""
                val queryParams = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) {
                        try { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) } catch (_: Exception) { "" to "" }
                    } else "" to ""
                }
                val q = queryParams["q"] ?: ""
                val strategy = queryParams["strategy"] ?: "BullPut"
                val requiredSuffix = if (strategy.equals("BearCall", ignoreCase = true)) "CE" else "PE"

                val istNow = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                val dayOfWeek = istNow.dayOfWeek
                // Monday, Tuesday -> Nifty; Wednesday, Thursday -> Sensex
                val indexSymbol = when (dayOfWeek) {
                    java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY -> "NSE:NIFTY50-INDEX"
                    java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY -> "BSE:SENSEX-INDEX"
                    else -> "NSE:NIFTY50-INDEX"
                }

                val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""
                val symbolsList = org.json.JSONArray()

                println("[SearchSymbols] Query: '$q', Strategy: $strategy (Suffix: $requiredSuffix), Index: $indexSymbol, Token present: ${token.isNotEmpty()}")

                if (token.isNotEmpty() && appId.isNotEmpty()) {
                    val chainJson = runBlocking { viewModel.fetchOptionChain(appId, token, indexSymbol, 40) }
                    println("[SearchSymbols] ChainJson response: ${chainJson?.toString()?.take(300)}")
                    if (chainJson != null && chainJson.has("data")) {
                        val dataObj = chainJson.getJSONObject("data")
                        
                        fun extractSymbols(jsonObj: JSONObject) {
                            val keys = jsonObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = jsonObj.opt(key)
                                if (value is org.json.JSONArray) {
                                    for (i in 0 until value.length()) {
                                        val item = value.opt(i)
                                        if (item is JSONObject) {
                                            val sym = item.optString("symbol", "")
                                            if (sym.isNotEmpty() && (q.isEmpty() || sym.contains(q, ignoreCase = true)) && sym.endsWith(requiredSuffix, ignoreCase = true)) {
                                                var exists = false
                                                for (j in 0 until symbolsList.length()) {
                                                    if (symbolsList.getString(j) == sym) exists = true
                                                }
                                                if (!exists) symbolsList.put(sym)
                                            } else if (sym.isEmpty()) {
                                                extractSymbols(item)
                                            }
                                        } else if (item is String && item.contains(":") && (q.isEmpty() || item.contains(q, ignoreCase = true)) && item.endsWith(requiredSuffix, ignoreCase = true)) {
                                            var exists = false
                                            for (j in 0 until symbolsList.length()) {
                                                if (symbolsList.getString(j) == item) exists = true
                                            }
                                            if (!exists) symbolsList.put(item)
                                        }
                                    }
                                } else if (value is JSONObject) {
                                    extractSymbols(value)
                                }
                            }
                        }
                        extractSymbols(dataObj)
                    }
                }

                println("[SearchSymbols] Found ${symbolsList.length()} symbols matching '$q' for $requiredSuffix")

                val respJson = symbolsList.toString()
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = respJson.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val fileName = if (path == "spread-monitor") "journal-web/spread-monitor.html" else "journal-web/index.html"
                val file = listOf(
                    File(fileName),
                    File("../$fileName"),
                    File("test-module/$fileName")
                ).firstOrNull { it.exists() } ?: File(fileName)
                val htmlStr = if (file.exists()) file.readText() else "<h1>Page Not Found</h1>"
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

    // Daily IST 9:00 AM Authentication & 12:00 PM EoD Session Loop
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
            println("\n[Daemon] Next IST session scheduled for: $nextRun (${delayMillis / 1000 / 60} minutes)")
            delay(delayMillis)
        } else {
            println("\n[Daemon] First startup: Running IST session immediately...")
            isFirstRun = false
        }

        // Daily 9:00 AM IST Trigger: Clear token & reset active spread
        endSessionAndCleanup()

        var activeToken = System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]

        if (activeToken.isNullOrEmpty()) {
            println("[Auth] Daily 9 AM IST: Access token cleared. Waiting for OAuth login...")
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            TelegramNotifier.sendAlert("⚠️ [Fyers Railway Daemon] Daily 9:00 AM IST Auth Required! Access token cleared. Please login & authorize:\n$loginUrl")

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

        // Monitor session strictly in IST until 12:00 PM IST or until SL / Target hits
        val morningCheck = ZonedDateTime.now(istZone)
        val noonToday = morningCheck.withHour(12).withMinute(0).withSecond(0).withNano(0)

        if (morningCheck.isBefore(noonToday)) {
            val waitTillNoon = Duration.between(morningCheck, noonToday).toMillis()
            println("[Daemon] IST Session active until 12:00 PM IST (${waitTillNoon / 1000 / 60} minutes remaining)...")

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < waitTillNoon) {
                // If SL or Target hit, stop WebSocket immediately and sleep for the rest of the day
                if (spreadStatus == "SL_HIT" || spreadStatus == "TARGET_HIT") {
                    println("[Daemon] Spread triggered ($spreadStatus). Closing WebSocket and sleeping until tomorrow 9 AM IST...")
                    endSessionAndCleanup()
                    break
                }

                // If 12:00 PM IST reached, stop WebSocket, send EoD alert with final P&L, and sleep until tomorrow 9 AM IST
                val currentCheck = ZonedDateTime.now(istZone)
                if (currentCheck.isAfter(noonToday) || currentCheck.isEqual(noonToday)) {
                    println("[Daemon] 12:00 PM IST reached. Closing WebSocket, sending EoD alert, and sleeping until tomorrow 9:00 AM IST...")
                    
                    val spread = activeSpread
                    val sellCurrent = if (sellLTP > 0.0) sellLTP else (spread?.sellEntry ?: 0.0)
                    val buyCurrent = if (buyLTP > 0.0) buyLTP else (spread?.buyEntry ?: 0.0)
                    val qty = spread?.quantity ?: 1
                    val finalPnl = if (spread != null) ((spread.sellEntry - sellCurrent) + (buyCurrent - spread.buyEntry)) * qty else 0.0

                    spreadStatus = "CLOSED_12PM"

                    if (spread != null) {
                        val eodMsg = """
                            📊 [EoD Alert - 12:00 PM IST] Market session closed.
                            Spread Status: Closed at 12 PM (SL/Target not triggered)
                            Final PnL: ₹$finalPnl (Qty: $qty)
                            Sell Leg (${spread.sellSymbol}): ₹${spread.sellEntry} -> ₹$sellCurrent
                            Buy Leg (${spread.buySymbol}): ₹${spread.buyEntry} -> ₹$buyCurrent
                        """.trimIndent()

                        CoroutineScope(Dispatchers.IO).launch {
                            TelegramNotifier.sendAlert(eodMsg)
                        }
                    }
                    endSessionAndCleanup()
                    break
                }

                delay(2000L)
            }
        }
    }
}
