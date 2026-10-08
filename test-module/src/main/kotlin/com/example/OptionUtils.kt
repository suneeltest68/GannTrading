package com.example

import com.example.viewmodel.AuthViewModel
import org.json.JSONObject
import kotlin.math.round

object OptionUtils {

    /**
     * Identifies the At-The-Money (ATM) strike price given the current underlying spot price and strike interval.
     * - NIFTY strike interval: 50
     * - BANKNIFTY strike interval: 100
     *
     * @param spotPrice Current underlying spot price
     * @param strikeInterval Strike interval (default 50 for NIFTY)
     * @return Nearest ATM strike price
     */
    fun calculateAtmStrike(spotPrice: Double, strikeInterval: Int = 50): Int {
        return (round(spotPrice / strikeInterval) * strikeInterval).toInt()
    }

    /**
     * Resolves option contract details around ATM (ATM, ITM, OTM).
     */
    fun getStrikeWithOffset(spotPrice: Double, offset: Int, strikeInterval: Int = 50): Int {
        val atm = calculateAtmStrike(spotPrice, strikeInterval)
        return atm + (offset * strikeInterval)
    }

    /**
     * Resolves the exact weekly option symbol using the expiry date from the option chain's expiryData.
     * @param expiryIndex 0 for current week, 1 for next week, etc. (based on option chain expiryData)
     * Throws an exception if the option chain fails or the expiry date is not found.
     */
    suspend fun resolveWeeklyOptionSymbol(
        viewModel: AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        expiryIndex: Int = 0
    ): String {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true) ) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            ?: throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol")

        var expiryDateStr: String? = null
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            if (dataObj.has("expiryData")) {
                val expiryArray = dataObj.getJSONArray("expiryData")
                if (expiryIndex in 0 until expiryArray.length()) {
                    val expiryObj = expiryArray.getJSONObject(expiryIndex)
                    expiryDateStr = expiryObj.optString("date", "") // e.g., "13-10-2026"
                }
            }
        }

        if (expiryDateStr.isNullOrEmpty() || expiryDateStr.length < 10) {
            throw IllegalStateException("Could not find expiry date for expiryIndex $expiryIndex in option chain response")
        }

        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val year = expiryDateStr.substring(8, 10)
        val monthLetter = when (monthNum) {
            "01" -> "F"
            "02" -> "G"
            "03" -> "H"
            "04" -> "J"
            "05" -> "K"
            "06" -> "L"
            "07" -> "M"
            "08" -> "N"
            "09" -> "P"
            "10" -> "O"
            "11" -> "Q"
            "12" -> "R"
            else -> ""
        }

        if (monthLetter.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val expiryCode = "$year$monthLetter$day" // e.g. "26O13"
        val underlyingPrefix = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true)) "NSE:BANKNIFTY" else "NSE:NIFTY"

        return "$underlyingPrefix$expiryCode$atmStrike$optionType"
    }

    /**
     * Parses an option chain JSONObject to find the exact option contract symbol matching strike and type (CE/PE).
     */
    fun findOptionSymbolFromChain(optionChainJson: JSONObject, targetStrike: Int, optionType: String): String? {
        try {
            val optionsArray = optionChainJson.optJSONArray("options") ?: optionChainJson.optJSONArray("data") ?: return null
            for (i in 0 until optionsArray.length()) {
                val opt = optionsArray.getJSONObject(i)
                val strike = opt.optInt("strikePrice", opt.optInt("strike", 0))
                val symbol = opt.optString("symbol", opt.optString("fyToken", ""))
                val type = opt.optString("optionType", opt.optString("instrumentType", ""))

                if (strike == targetStrike && symbol.isNotEmpty() && (type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType))) {
                    return symbol
                }
            }
        } catch (e: Exception) {
            println("Error parsing option chain for strike $targetStrike $optionType: ${e.message}")
        }
        return null
    }
}
