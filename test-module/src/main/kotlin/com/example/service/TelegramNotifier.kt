package com.example.service

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramNotifier {
    private val dotenv = dotenv {
        ignoreIfMissing = true
    }

    private val botToken: String
        get() = get("TELEGRAM_BOT_TOKEN") ?: "YOUR_BOT_TOKEN_HERE"

    private val chatId: String
        get() = get("TELEGRAM_CHAT_ID") ?: "YOUR_CHAT_ID_HERE"

    private val isEnabled: Boolean
        get() = get("TELEGRAM_ENABLED")?.toBoolean() ?: true

    fun get(key: String): String? {
        return System.getenv(key) ?: dotenv[key]
    }

    suspend fun sendAlert(message: String) {
        if (!isEnabled) {
            return
        }

        if (botToken == "YOUR_BOT_TOKEN_HERE" || chatId == "YOUR_CHAT_ID_HERE" || botToken.isEmpty() || chatId.isEmpty()) {
            println("[Telegram] Warning: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID not configured. Alert not sent: $message")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val urlString = "https://api.telegram.org/bot$botToken/sendMessage"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true

                val payload = "chat_id=$chatId&text=" + URLEncoder.encode(message, StandardCharsets.UTF_8.toString())

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    println("[Telegram] Error sending alert (HTTP $responseCode): $errorStream")
                } else {
                    println("[Telegram] Alert sent successfully.")
                }
            } catch (e: Exception) {
                println("[Telegram] Exception sending alert: ${e.message}")
            }
        }
    }
}
