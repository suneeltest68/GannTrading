package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.TradingWorkflowService
import com.example.service.TelegramNotifier
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
    val workflowService = TradingWorkflowService(viewModel)
    val istZone = ZoneId.of("Asia/Kolkata")

    println("==================================================")
    println("🚀 FYERS ALGO TRADING BOT - RAILWAY DAEMON (24/7 WEB & CALLBACK)")
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
            } catch (_: Exception) {
                // Ignore network errors during polling
            }
            delay(5000L)
        }
    }

    var authCodeDeferred: CompletableDeferred<String>? = null

    // Start 24/7 Background HTTP Server for Website Dashboard, API, and OAuth Callback
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path?.removeSuffix("/") ?: ""
        try {
            if (path == "/api/journal" || path == "/journal") {
                val file = listOf(
                    File("trading_journal.json"),
                    File("../trading_journal.json"),
                    File("test-module/trading_journal.json")
                ).firstOrNull { it.exists() } ?: File("trading_journal.json")
                val jsonStr = if (file.exists()) file.readText() else "{\"trades\":[],\"stats\":{}}"
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/api/active-trades") {
                val file = listOf(
                    File("active_trades.json"),
                    File("../active_trades.json"),
                    File("test-module/active_trades.json")
                ).firstOrNull { it.exists() } ?: File("active_trades.json")
                val jsonStr = if (file.exists()) file.readText() else "{}"
                exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else if (path == "/callback") {
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
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: green;'>✅ Fyers Authentication Successful!</h1><p>Authorization code received. You can now close this tab and return to Railway.</p></body></html>"
                } else {
                    "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1 style='color: red;'>❌ Authentication Failed</h1><p>No authorization code found in callback query.</p></body></html>"
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
                val html = "<html><body style='font-family: Arial; text-align: center; margin-top: 50px;'><h1>🔐 Fyers Bot Authentication</h1><p>Click below to authorize your Fyers account:</p><a href='$loginUrl' style='background: #0052FF; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block; margin-top: 20px;'>Login with Fyers</a></body></html>"
                exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                val bytes = html.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                // Serve Gann Trading Journal Dashboard (journal-web/index.html)
                val file = listOf(
                    File("journal-web/index.html"),
                    File("../journal-web/index.html"),
                    File("test-module/journal-web/index.html")
                ).firstOrNull { it.exists() } ?: File("journal-web/index.html")
                val htmlStr = if (file.exists()) file.readText() else "<h1>Gann Trading Journal</h1><p>index.html not found.</p>"
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
    println("[HTTP Server] Running 24/7 on port $port (Dashboard: /, API: /api/journal, OAuth: /callback)")

    var isFirstRun = true

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
            println("\n[Daemon] Next daily startup scheduled for: $nextRun (in ${delayMillis / 1000 / 60} minutes)")
            
            delay(delayMillis)
        } else {
            println("\n[Daemon] First startup: Running trading session immediately...")
            isFirstRun = false
        }

        println("\n--------------------------------------------------")
        println("🌅 [Daily Routine] Starting Trading Session...")
        println("--------------------------------------------------")

        // Clear old token at 9:00 AM IST trigger so live trading never uses cached tokens
        viewModel.clearToken()

        // For live trading, do not use cached token. Only allow environment token if explicitly provided, otherwise force fresh auth.
        var activeToken: String? = System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]

        if (activeToken != null) {
            println("[Env] Using Access Token from environment.")
        }

        if (activeToken.isNullOrEmpty()) {
            println("[Auth] Access token missing. Clearing old token & waiting for OAuth callback...")
            
            viewModel.prepareLoginUrl(appId, redirectUri)
            val loginUrl = viewModel.authState.value.loginUrl
            
            TelegramNotifier.sendAlert("⚠️ [Fyers Railway Daemon] Daily Auth Required! Access token cleared. Please login & authorize:\n$loginUrl")

            authCodeDeferred = CompletableDeferred()

            // Wait until callback receives auth code (or timeout after 1 hour)
            val authCode = try {
                withTimeout(60 * 60 * 1000L) {
                    authCodeDeferred?.await()
                }
            } catch (_: Exception) {
                null
            }

            authCodeDeferred = null

            if (authCode != null) {
                println("[Auth] Exchanging received auth code for access token...")
                viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
                val finalState = viewModel.authState.value
                if (finalState.accessToken != null) {
                    activeToken = finalState.accessToken
                    TelegramNotifier.sendAlert("✅ [Fyers Railway Daemon] Access Token acquired successfully via web callback!")
                } else {
                    TelegramNotifier.sendAlert("❌ [Fyers Railway Daemon] Token exchange failed: ${finalState.errorMessage}")
                }
            } else {
                TelegramNotifier.sendAlert("❌ [Fyers Railway Daemon] Authentication timed out waiting for web callback.")
                delay(10 * 60 * 1000L)
                continue
            }
        }

        if (activeToken != null) {
            try {
                workflowService.executeCurrentExpiryWorkflow(appId, activeToken)

                // Keep daemon alive during market hours until 3:35 PM IST today
                val now = ZonedDateTime.now(istZone)
                val marketCloseTimeToday = now.withHour(15).withMinute(35).withSecond(0).withNano(0)
                if (now.isBefore(marketCloseTimeToday)) {
                    val waitDuration = Duration.between(now, marketCloseTimeToday).toMillis()
                    println("[Daemon] Trading session active. Waiting until market close at 3:35 PM IST (${waitDuration / 1000 / 60} minutes remaining)...")
                    delay(waitDuration)
                }
            } catch (e: Exception) {
                println("[Daemon Error] Workflow exception: ${e.message}")
                TelegramNotifier.sendAlert("❌ [Fyers Daemon] Error during execution: ${e.message}")
            }
        }

        // Sleep 10 minutes before looping to ensure we don't double-trigger on the same day
        delay(10 * 60 * 1000L)
    }
}
