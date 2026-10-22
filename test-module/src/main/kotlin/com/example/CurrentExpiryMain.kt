package com.example

import com.example.viewmodel.AuthViewModel
import com.example.service.TradingWorkflowService
import com.example.service.TelegramNotifier
import com.example.util.AuthUtils
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.util.Scanner

fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val workflowService = TradingWorkflowService(viewModel)
    val scanner = Scanner(System.`in`)

    println("=== Fyers API v3 Current Expiry ATM Options Flow (MVVM) ===")
    val appId =
        System.getenv("FYERS_APP_ID")
            ?: dotenv["FYERS_APP_ID"]
    val secretKey =
        System.getenv("FYERS_SECRET_KEY")
            ?: dotenv["FYERS_SECRET_KEY"]
    val redirectUri =
        System.getenv("FYERS_REDIRECT_URI")
            ?: dotenv["FYERS_REDIRECT_URI"]

    val cachedToken = viewModel.getCachedToken()
    val tokenToUse = cachedToken ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]
    if (!tokenToUse.isNullOrEmpty()) {
        println("\n[Cache/Env] Using valid Access Token.")
        runBlocking {
            workflowService.executeCurrentExpiryWorkflow(appId, tokenToUse)
        }
        return
    }

    viewModel.prepareLoginUrl(appId, redirectUri)
    val loginUrl = viewModel.authState.value.loginUrl

    // Send Telegram alert with login URL
    runBlocking {
        TelegramNotifier.sendAlert("⚠️ [Fyers Bot] Please click the link below to login and authorize:\n$loginUrl")
    }

    print("Paste either the full redirect URL or the 'auth_code': ")
    var input = scanner.nextLine().trim()
    while (input.isEmpty()) {
        print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
        input = scanner.nextLine().trim()
    }

    val authCode = AuthUtils.extractAuthCode(input)

    println("\n[2] Exchanging auth code for access token...")
    runBlocking {
        viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
    }

    val finalState = viewModel.authState.value
    if (finalState.accessToken != null) {
        println("\nSUCCESS! Access Token acquired.")
        runBlocking {
            TelegramNotifier.sendAlert("✅ [Fyers Bot] Access Token acquired successfully! Starting trading workflow...")
            workflowService.executeCurrentExpiryWorkflow(appId, finalState.accessToken)
        }
    } else {
        val errorMsg = finalState.errorMessage ?: "Unknown error"
        println("\nFAILED: $errorMsg")
        runBlocking {
            TelegramNotifier.sendAlert("❌ [Fyers Bot] Authentication failed: $errorMsg")
        }
    }
}
