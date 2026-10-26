package com.example

import com.example.viewmodel.AuthViewModel
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

data class Candle(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val epoch: Long
)

data class ExpiredDerivativeResult(
    val symbol: String,
    val expiryDate: String,
    val candles: List<Candle>
)

data class ResolvedOption(
    val symbol: String,
    val expiryDate: String
)

object OptionUtils {

    /**
     * Identifies the At-The-Money (ATM) strike price given the current underlying spot price and strike interval.
     * - NIFTY strike interval: 50
     * - BANKNIFTY strike interval: 100
     * - SENSEX strike interval: 100
     *
     * @param spotPrice Current underlying spot price
     * @param strikeInterval Strike interval
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
     * Resolves the exact weekly option symbol strictly via live Fyers option chain lookup.
     * @param expiryIndex 0 for current week (1st), 1 for bi-weekly (2nd), 2 for 3rd week, etc.
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
        return resolveWeeklyOptionWithExpiry(viewModel, appId, token, indexSymbol, spotPrice, optionType, expiryIndex).symbol
    }

    suspend fun resolveWeeklyOptionWithExpiry(
        viewModel: AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        expiryIndex: Int = 0
    ): ResolvedOption {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("NIFTYBANK", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        var optionChain: JSONObject? = null
        var attempts = 0
        while (attempts < 3 && optionChain == null) {
            if (attempts > 0) kotlinx.coroutines.delay(500L * attempts)
            optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 10)
            attempts++
        }
        if (optionChain == null) {
            throw IllegalStateException("Failed to fetch option chain from Fyers API for symbol $indexSymbol after retries (Rate limit 429)")
        }

        var expiryDateStr: String? = null
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val expiryArray = dataObj.optJSONArray("expiryData") ?: dataObj.optJSONArray("expiry_dates")
            if (expiryArray != null && expiryIndex in 0 until expiryArray.length()) {
                val expiryObj = expiryArray.optJSONObject(expiryIndex)
                expiryDateStr = expiryObj?.optString("date", expiryObj.optString("expiry", ""))
                    ?: expiryArray.optString(expiryIndex, "")
            }
        }

        if (expiryDateStr.isNullOrEmpty() || expiryDateStr.length < 10) {
            throw IllegalStateException("Could not find expiry date for expiryIndex $expiryIndex in option chain response")
        }

        val day = expiryDateStr.substring(0, 2)
        val monthNum = expiryDateStr.substring(3, 5)
        val fullYear = if (expiryDateStr.length >= 10) expiryDateStr.substring(6, 10) else "20" + expiryDateStr.substring(8, 10)
        val shortYear = fullYear.substring(2, 4)
        val isoDate = "$fullYear-$monthNum-$day"

        val monthCode = when (monthNum) {
            "01" -> "1"
            "02" -> "2"
            "03" -> "3"
            "04" -> "4"
            "05" -> "5"
            "06" -> "6"
            "07" -> "7"
            "08" -> "8"
            "09" -> "9"
            "10" -> "O"
            "11" -> "N"
            "12" -> "D"
            else -> ""
        }

        if (monthCode.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val monthAbbr = when (monthNum) {
            "01" -> "JAN"
            "02" -> "FEB"
            "03" -> "MAR"
            "04" -> "APR"
            "05" -> "MAY"
            "06" -> "JUN"
            "07" -> "JUL"
            "08" -> "AUG"
            "09" -> "SEP"
            "10" -> "OCT"
            "11" -> "NOV"
            "12" -> "DEC"
            else -> ""
        }

        if (monthCode.isEmpty()) {
            throw IllegalStateException("Invalid month number $monthNum in expiry date $expiryDateStr")
        }

        val weeklyExpiryCode = "$shortYear$monthCode$day"
        val monthlyExpiryCode = "$shortYear$monthAbbr"

        // First attempt: Search optionsChain array for exact matching contract with expiry codes or isoDate
        if (optionChain.has("data")) {
            val dataObj = optionChain.getJSONObject("data")
            val optionsArray = dataObj.optJSONArray("optionsChain") ?: dataObj.optJSONArray("options")
            if (optionsArray != null) {
                for (i in 0 until optionsArray.length()) {
                    val optObj = optionsArray.getJSONObject(i)
                    val strike = optObj.optDouble("strike_price", optObj.optDouble("strikePrice", optObj.optDouble("strike", 0.0)))
                    val symbol = optObj.optString("symbol", "")
                    val type = optObj.optString("option_type", optObj.optString("optionType", ""))
                    val optExpiry = optObj.optString("expiry", optObj.optString("expiry_date", ""))

                    val isStrikeMatch = abs(strike - atmStrike.toDouble()) < 1.0
                    val isTypeMatch = type.equals(optionType, ignoreCase = true) || symbol.endsWith(optionType)
                    val isExpiryMatch = symbol.contains(weeklyExpiryCode) || symbol.contains(monthlyExpiryCode) ||
                            (optExpiry.isNotEmpty() && (optExpiry == isoDate || optExpiry == expiryDateStr || optExpiry.contains(isoDate)))

                    if (isStrikeMatch && isTypeMatch && isExpiryMatch && symbol.isNotEmpty()) {
                        return ResolvedOption(symbol, isoDate)
                    }
                }
            }
        }

        return ResolvedOption(buildFyersOptionSymbol(indexSymbol, isoDate, atmStrike, optionType), isoDate)
    }

    /**
     * Resolves and fetches historical candle data for an EXPIRED weekly option contract
     * using Fyers' dedicated expired F&O APIs with in-memory caching.
     */
    suspend fun resolveAndFetchExpiredDerivativeCandles(
        viewModel: AuthViewModel,
        appId: String,
        token: String,
        indexSymbol: String,
        spotPrice: Double,
        optionType: String,
        tradeDate: String,
        expiryIndex: Int = 0
    ): ExpiredDerivativeResult {
        val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("NIFTYBANK", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true)) 100 else 50
        val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

        val rangeFrom = LocalDate.parse(tradeDate).minusMonths(1).toString()
        val rangeTo = LocalDate.parse(tradeDate).plusDays(7).toString()

        val expiryJson = viewModel.fetchHistoryExpiryDates(appId, token, indexSymbol, rangeFrom, rangeTo)

        var targetExpiry = tradeDate
        var optionsExpiriesArr: org.json.JSONArray? = null
        if (expiryJson != null && expiryJson.has("data")) {
            val dataObj = expiryJson.getJSONObject("data")
            if (dataObj.has("expiry_dates")) {
                optionsExpiriesArr = dataObj.getJSONObject("expiry_dates").optJSONArray("options")
            }
        }

        val allExpiries = mutableListOf<String>()
        if (optionsExpiriesArr != null) {
            for (i in 0 until optionsExpiriesArr.length()) {
                allExpiries.add(optionsExpiriesArr.getString(i))
            }
        }

        val validExpiries = allExpiries.filter { it >= tradeDate }.distinct().sorted()
        targetExpiry = if (expiryIndex in validExpiries.indices) {
            validExpiries[expiryIndex]
        } else {
            validExpiries.firstOrNull() ?: tradeDate
        }

        val contractsJson = viewModel.fetchHistoryUnderlyingSymbols(appId, token, indexSymbol, targetExpiry)

        var optionSymbol = ""
        if (contractsJson != null && contractsJson.has("data")) {
            val dataObj = contractsJson.getJSONObject("data")
            if (dataObj.has("contracts")) {
                val optionsArray = dataObj.getJSONObject("contracts").optJSONArray("options")
                if (optionsArray != null) {
                    val strikeStr = atmStrike.toString()
                    for (i in 0 until optionsArray.length()) {
                        val sym = optionsArray.getString(i)
                        if (sym.contains(strikeStr) && sym.endsWith(optionType)) {
                            optionSymbol = sym
                            break
                        }
                    }
                }
            }
        }

        if (optionSymbol.isEmpty()) {
            optionSymbol = buildFyersOptionSymbol(indexSymbol, targetExpiry, atmStrike, optionType)
        }

        val fnoHistory = viewModel.fetchHistoryFNOExpired(
            appId = appId,
            accessToken = token,
            fnoSymbol = optionSymbol,
            rangeFrom = tradeDate,
            rangeTo = tradeDate,
            resolution = "5S"
        )

        if (fnoHistory == null || !fnoHistory.has("candles") || fnoHistory.optJSONArray("candles")?.length() == 0) {
            throw IllegalStateException("Failed to fetch FNO expired history for $optionSymbol on $tradeDate.")
        }

        val parsed = parseExpiredCandles(fnoHistory)
        return ExpiredDerivativeResult(optionSymbol, targetExpiry, parsed)
    }

    private fun buildFyersOptionSymbol(indexSymbol: String, targetExpiry: String, atmStrike: Int, optionType: String): String {
        val underlyingPrefix = when {
            indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("NIFTYBANK", ignoreCase = true) -> "NSE:BANKNIFTY"
            indexSymbol.contains("SENSEX", ignoreCase = true) -> "BSE:SENSEX"
            else -> "NSE:NIFTY"
        }
        if (targetExpiry.length < 10) return "$underlyingPrefix$targetExpiry$atmStrike$optionType"

        val year = targetExpiry.substring(0, 4)
        val shortYear = year.substring(2, 4)
        val month = targetExpiry.substring(5, 7)

        val monthAbbr = when (month) {
            "01" -> "JAN"
            "02" -> "FEB"
            "03" -> "MAR"
            "04" -> "APR"
            "05" -> "MAY"
            "06" -> "JUN"
            "07" -> "JUL"
            "08" -> "AUG"
            "09" -> "SEP"
            "10" -> "OCT"
            "11" -> "NOV"
            "12" -> "DEC"
            else -> "OCT"
        }

        return "$underlyingPrefix$shortYear$monthAbbr$atmStrike$optionType"
    }

    private fun parseExpiredCandles(json: JSONObject): List<Candle> {
        val candles = mutableListOf<Candle>()
        try {
            val candlesArray = json.optJSONArray("candles") ?: return candles
            for (i in 0 until candlesArray.length()) {
                val item = candlesArray.getJSONArray(i)
                val epoch = item.optLong(0, 0L)
                val open = item.optDouble(1, 0.0)
                val high = item.optDouble(2, 0.0)
                val low = item.optDouble(3, 0.0)
                val close = item.optDouble(4, 0.0)
                val volume = item.optDouble(5, 0.0)

                val zdt = Instant.ofEpochSecond(epoch).atZone(ZoneId.of("Asia/Kolkata"))
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume, epoch = epoch))
            }
        } catch (e: Exception) {
            throw RuntimeException("Error parsing expired F&O candles: ${e.message}")
        }
        return candles.distinctBy { if (it.epoch > 0) it.epoch else it.timestamp }
            .sortedBy { if (it.epoch > 0) it.epoch else 0L }
    }

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
