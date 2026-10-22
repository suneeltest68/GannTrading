package com.example

import com.example.viewmodel.AuthViewModel
import com.example.util.AuthUtils
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.util.Scanner

fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("=== Fyers API v3 Expired F&O Workflow (MVVM) ===")
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
        val success = runBlocking {
            fetchUserDataAndHistory(viewModel, appId, tokenToUse)
        }
        if (success) {
            return
        }
    }

    performLoginFlow(viewModel, scanner, appId, secretKey, redirectUri)
}

fun performLoginFlow(viewModel: AuthViewModel, scanner: Scanner, appId: String, secretKey: String, redirectUri: String) {
    viewModel.prepareLoginUrl(appId, redirectUri)

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
            fetchUserDataAndHistory(viewModel, appId, finalState.accessToken)
        }
    } else {
        println("\nFAILED: ${finalState.errorMessage}")
    }
}

suspend fun fetchUserDataAndHistory(viewModel: AuthViewModel, appId: String, token: String): Boolean {
    val targetDateStr = "2026-10-01"

    // Step 4: Get Nifty Sep 1st 2026 data & Open Price
    println("\n[4] Fetching Nifty 50 Index Data for $targetDateStr...")
    val niftyHistory = viewModel.fetchHistoricalData(
        appId = appId,
        accessToken = token,
        symbol = "NSE:NIFTY50-INDEX",
        resolution = "D",
        rangeFrom = targetDateStr,
        rangeTo = targetDateStr
    )

    var openPrice = 22555.0 // Default fallback based on user example
    if (niftyHistory != null && niftyHistory.has("candles")) {
        val candles = niftyHistory.getJSONArray("candles")
        if (candles.length() > 0) {
            val candle = candles.getJSONArray(0)
            openPrice = candle.getDouble(1)
            println("Nifty $targetDateStr OPEN PRICE: $openPrice")
        }
    } else {
        println("Using default Open Price: $openPrice")
    }

    // Step 5: Calculate ATM Strike (nearest 50)
    val atmStrike = Math.round(openPrice / 50.0) * 50
    println("Calculated ATM Strike: $atmStrike")

    // Step 6: Get Expiry Dates (Note: Fyers requires range_to to be strictly in the past)
    println("\n[6] Fetching Expiry Dates for Nifty...")
    val expiryDatesJson = viewModel.fetchHistoryExpiryDates(
        appId = appId,
        accessToken = token,
        symbol = "NSE:NIFTY50-INDEX",
        rangeFrom = "2026-08-01",
        rangeTo = "2026-09-30"
    )

    var targetExpiry = "2026-09-03"
    if (expiryDatesJson != null && expiryDatesJson.has("data")) {
        val dataObj = expiryDatesJson.getJSONObject("data")
        if (dataObj.has("expiry_dates")) {
            val optionsExpiries = dataObj.getJSONObject("expiry_dates").optJSONArray("options")
            if (optionsExpiries != null) {
                var found = false
                for (i in 0 until optionsExpiries.length()) {
                    val expDate = optionsExpiries.getString(i)
                    if (expDate >= targetDateStr) {
                        targetExpiry = expDate
                        found = true
                        break
                    }
                }
                if (!found && optionsExpiries.length() > 0) {
                    targetExpiry = optionsExpiries.getString(optionsExpiries.length() - 1)
                }
                println("Selected Nearest Subsequent Expiry Date: $targetExpiry (for data date: $targetDateStr)")
            }
        }
    }

    // Step 7: Get Expired Contracts for Target Expity
    println("\n[7] Fetching Underlying Expired Contracts for Expiry: $targetExpiry...")
    val contractsJson = viewModel.fetchHistoryUnderlyingSymbols(
        appId = appId,
        accessToken = token,
        symbol = "NSE:NIFTY50-INDEX",
        expiryDate = targetExpiry
    )

    var ceSymbol = ""
    var peSymbol = ""
    if (contractsJson != null && contractsJson.has("data")) {
        val dataObj = contractsJson.getJSONObject("data")
        if (dataObj.has("contracts")) {
            val optionsArray = dataObj.getJSONObject("contracts").optJSONArray("options")
            if (optionsArray != null) {
                val strikeStr = atmStrike.toInt().toString()
                for (i in 0 until optionsArray.length()) {
                    val sym = optionsArray.getString(i)
                    if (sym.contains(strikeStr)) {
                        if (sym.endsWith("CE") && ceSymbol.isEmpty()) ceSymbol = sym
                        if (sym.endsWith("PE") && peSymbol.isEmpty()) peSymbol = sym
                    }
                }
            }
        }
    }

    if (ceSymbol.isEmpty()) ceSymbol = "NSE:NIFTY26930${atmStrike.toInt()}CE"
    if (peSymbol.isEmpty()) peSymbol = "NSE:NIFTY26930${atmStrike.toInt()}PE"

    println("Target ATM CE Symbol: $ceSymbol")
    println("Target ATM PE Symbol: $peSymbol")

    // Step 8: Fetch Historical Data for ATM PE on Sep 1st, 2026
    println("\n[8] Fetching Expired F&O Historical Data for ATM PE ($peSymbol) on $targetDateStr...")
    val peHistory = viewModel.fetchHistoryFNOExpired(
        appId = appId,
        accessToken = token,
        fnoSymbol = peSymbol,
        rangeFrom = targetDateStr,
        rangeTo = targetDateStr,
        resolution = "1"
    )
    if (peHistory != null) {
        println("\nATM PE EXPIRED HISTORICAL DATA:")
        println(peHistory.toString(4))
    } else {
        println("\nFailed to fetch ATM PE expired historical data.")
    }

    return true
}
