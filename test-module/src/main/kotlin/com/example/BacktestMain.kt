package com.example

import com.example.OptionUtils.calculateAtmStrike
import com.example.viewmodel.AuthViewModel
import com.example.model.GannLevels
import com.example.service.GannCalculator
import com.example.service.PaperTradingEngine
import com.example.service.TradeJournalEntry
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.round

fun main() = runBlocking {
    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()

    println("==================================================")
    println("📊 GANN STRATEGY HISTORICAL BACKTEST ENGINE")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"]
    if (appId.isNullOrEmpty()) {
        println("❌ Error: Missing FYERS_APP_ID environment variable.")
        return@runBlocking
    }

    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]
    if (token.isNullOrEmpty()) {
        println("❌ Error: No cached token found in token.json and FYERS_ACCESS_TOKEN not set.")
        return@runBlocking
    }
    println("[Cache] Using persistent cached Access Token.")

//    val todayStr = "2026-09-27"
    val todayStr = "2026-10-08"
    val expiredData = false
    val backtestDate = System.getenv("BACKTEST_DATE") ?: todayStr

    println("\n[Backtest] Starting backtest simulation for date: $backtestDate...")

    val indices = listOf(
        Triple("Nifty 50", "NSE:NIFTY50-INDEX", 50.0),
        Triple("Bank Nifty", "NSE:NIFTYBANK-INDEX", 100.0),
        Triple("Sensex", "BSE:SENSEX-INDEX", 100.0)
    )

    val symbolsGannMap = mutableMapOf<String, GannLevels>()
    val quantitiesMap = mutableMapOf<String, Int>()
    val symbolCandlesMap = mutableMapOf<String, List<CandleTick>>()

    for ((indexName, indexSymbol, strikeStep) in indices) {
        println("\nProcessing Index: $indexName...")
        val indexHistory = viewModel.fetchHistoricalData(
            appId = appId,
            accessToken = token,
            symbol = indexSymbol,
            resolution = "D",
            rangeFrom = backtestDate,
            rangeTo = backtestDate
        )

        var openPrice = 0.0
        if (indexHistory != null && indexHistory.has("candles")) {
            val candles = indexHistory.getJSONArray("candles")
            if (candles.length() > 0) {
                openPrice = candles.getJSONArray(0).getDouble(1)
            }
        }

        val atmStrike = round(openPrice / strikeStep) * strikeStep
        println("Calculated ATM Strike: $atmStrike (Open: $openPrice)")


        var ceSymbol = ""
        var peSymbol = ""

        if (expiredData){
            ceSymbol = resolveAndFetchExpiredDerivativeCandles(
                viewModel = viewModel,
                appId = appId,
                token = token,
                indexSymbol = indexSymbol,
                spotPrice = openPrice,
                optionType = "CE",
                tradeDate = todayStr
            )

            peSymbol = resolveAndFetchExpiredDerivativeCandles(
                viewModel = viewModel,
                appId = appId,
                token = token,
                indexSymbol = indexSymbol,
                spotPrice = openPrice,
                optionType = "CE",
                tradeDate = todayStr
            )
        }else
        {
            val optionChain = viewModel.fetchOptionChain(appId, token, indexSymbol, 20)

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

            val prefix = if (indexName.contains("Sensex")) "BSE:SENSEX" else if (indexName.contains("Bank")) "NSE:BANKNIFTY" else "NSE:NIFTY"
            if (ceSymbol.isEmpty()) ceSymbol = "${prefix}26O13${atmStrike.toInt()}CE"
            if (peSymbol.isEmpty()) peSymbol = "${prefix}26O13${atmStrike.toInt()}PE"

        }


        val priorDate = getLatestTradingDay(viewModel, appId, token, indexSymbol, backtestDate,expiredData)
        println("Resolved Latest Prior Trading Day for PDC: $priorDate")

        val ceGann = fetchGannForSymbol(viewModel, appId, token, ceSymbol, priorDate,expiredData)
        val peGann = fetchGannForSymbol(viewModel, appId, token, peSymbol, priorDate,expiredData)

        symbolsGannMap[ceSymbol] = ceGann
        quantitiesMap[ceSymbol] = if (indexName.contains("Bank")) 60 else if (indexName.contains("Sensex")) 40 else 130

        symbolsGannMap[peSymbol] = peGann
        quantitiesMap[peSymbol] = if (indexName.contains("Bank")) 60 else if (indexName.contains("Sensex")) 40 else 130

        if (expiredData){
            val ceCandles = fetchDerivativeCandlesForOptions(viewModel, appId, token, ceSymbol, backtestDate,backtestDate)
            val peCandles = fetchDerivativeCandlesForOptions(viewModel, appId, token, peSymbol, backtestDate,backtestDate)
            symbolCandlesMap[ceSymbol] = ceCandles
            symbolCandlesMap[peSymbol] = peCandles
            println("Target $indexName ATM CE Symbol: $ceSymbol ${ceCandles[0].timestamp}")
            println("Target $indexName ATM PE Symbol: $peSymbol ${peCandles[0].timestamp}")

        }else
        {
            val ceCandles = fetchIntradayCandles(viewModel, appId, token, ceSymbol, backtestDate)
            val peCandles = fetchIntradayCandles(viewModel, appId, token, peSymbol, backtestDate)
            symbolCandlesMap[ceSymbol] = ceCandles
            symbolCandlesMap[peSymbol] = peCandles
            println("Target $indexName ATM CE Symbol: $ceSymbol ${ceCandles[0].timestamp}")
            println("Target $indexName ATM PE Symbol: $peSymbol ${peCandles[0].timestamp}")
        }

    }

    if (symbolsGannMap.isEmpty()) {
        println("❌ No symbols/Gann levels collected for backtesting.")
        return@runBlocking
    }

    println("\n[Backtest] Initializing Paper Trading Engine in-memory for backtest...")
    val backtestTrades = mutableListOf<TradeJournalEntry>()
    val engine = PaperTradingEngine(symbolsGannMap, quantitiesMap).apply {
        onTradeCompleted = { trade -> backtestTrades.add(trade) }
    }
    engine.printStatus()

    val allTicks = mutableListOf<CandleTick>()
    for ((_, candles) in symbolCandlesMap) {
        allTicks.addAll(candles)
    }
    allTicks.sortBy { it.timestamp }

    println("\n[Backtest] Feeding ${allTicks.size} historical intraday ticks into Trading Engine...")
    for (tick in allTicks) {
        engine.onCandleTick(tick.symbol, tick.openPrice, tick.highPrice, tick.lowPrice, tick.closePrice, tick.time, tick.timestamp * 1000)
    }

    println("\n==================================================")
    println("📈 BACKTEST SIMULATION COMPLETED FOR $backtestDate")
    println("==================================================")

    printDetailedBacktestReport(backtestDate, backtestTrades)
}

data class CandleTick(
    val symbol: String,
    val timestamp: Long,
    val time: LocalTime,
    val openPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val closePrice: Double
)

suspend fun getLatestTradingDay(viewModel: AuthViewModel, appId: String, token: String, indexSymbol: String, targetDateStr: String,expiredData: Boolean): String {
    var date: LocalDate? = LocalDate.parse(targetDateStr).minusDays(1)
    repeat(11) {
        val dateStr = date.toString()
        try {
            var history : JSONObject? = null
//            if (!expiredData){
                history = viewModel.fetchHistoricalData(appId, token, indexSymbol, "D", date.toString(), date.toString(), showError = false)
//            }else{
//                history = viewModel.fetchHistoryFNOExpired(appId = appId, accessToken = token, fnoSymbol = indexSymbol, rangeFrom = targetDateStr, rangeTo = targetDateStr, resolution = "D")
//            }
            if (history != null && history.has("candles")) {
                val candles = history.getJSONArray("candles")
                if (candles.length() > 0) {
                    return dateStr
                }
            }
        } catch (_: Exception) {}
        date = date?.minusDays(1)
    }
    return LocalDate.parse(targetDateStr).minusDays(1).toString()
}

suspend fun fetchGannForSymbol(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    symbol: String,
    date: String,
    expiredData: Boolean
): GannLevels {
    var history : JSONObject? = null
    if (!expiredData){
         history = viewModel.fetchHistoricalData(appId, token, symbol, "D", date, date)
    }else{
        history = viewModel.fetchHistoryFNOExpired(appId = appId, accessToken = token, fnoSymbol = symbol, rangeFrom = date, rangeTo = date, resolution = "D")
    }

    var pdc = 0.0
    if (history != null && history.has("candles")) {
        val candles = history.getJSONArray("candles")
        if (candles.length() > 0) {
            pdc = candles.getJSONArray(0).getDouble(4)
        }
    }
    if (pdc <= 0.0) pdc = 100.0
    return GannCalculator.calculate(pdc)
}

suspend fun fetchDerivativeCandlesForOptions(
    viewModel: AuthViewModel,
    appId: String,
    token: String,
    symbol: String,
    from: String,
    to: String
): List<CandleTick> {
    val history = viewModel.fetchHistoryFNOExpired(appId = appId, accessToken = token, fnoSymbol = symbol, rangeFrom = from, rangeTo = to, resolution = "5S")

    if (history == null || !history.has("candles") || history.getJSONArray("candles").length() == 0) {
        throw IllegalStateException("❌ Error: 5-second historical candles ('5S') not available for $symbol on $from. Response: $history")
    }

    val list = mutableListOf<CandleTick>()
    val candles = history.getJSONArray("candles")
    for (i in 0 until candles.length()) {
        val c = candles.getJSONArray(i)
        val timestamp = c.getLong(0)
        val open = c.getDouble(1)
        val high = c.getDouble(2)
        val low = c.getDouble(3)
        val close = c.getDouble(4)
        val instant = Instant.ofEpochSecond(timestamp)
        val time = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata")).toLocalTime()
        list.add(CandleTick(symbol, timestamp, time, open, high, low, close))
    }
    return list
}


suspend fun fetchIntradayCandles(viewModel: AuthViewModel, appId: String, token: String, symbol: String, date: String): List<CandleTick> {
    val history = viewModel.fetchHistoricalData(appId, token, symbol, "5S", date, date)
    if (history == null || !history.has("candles") || history.getJSONArray("candles").length() == 0) {
        throw IllegalStateException("❌ Error: 5-second historical candles ('5S') not available for $symbol on $date. Response: $history")
    }

    val list = mutableListOf<CandleTick>()
    val candles = history.getJSONArray("candles")
    for (i in 0 until candles.length()) {
        val c = candles.getJSONArray(i)
        val timestamp = c.getLong(0)
        val open = c.getDouble(1)
        val high = c.getDouble(2)
        val low = c.getDouble(3)
        val close = c.getDouble(4)
        val instant = Instant.ofEpochSecond(timestamp)
        val time = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata")).toLocalTime()
        list.add(CandleTick(symbol, timestamp, time, open, high, low, close))
    }
    return list
}

fun printDetailedBacktestReport(dateStr: String, trades: List<TradeJournalEntry>) {
    println("\n==================================================")
    println("📊 BACKTEST REPORT & TRADE LOG [ $dateStr ]")
    println("==================================================")

    var dayPnl = 0.0

    for (t in trades) {
        val sym = t.symbol
        val pnl = t.pnl
        val reason = t.exitReason
        dayPnl += pnl

        val entryTimeStr = if (t.entryTime > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(t.entryTime), ZoneId.of("Asia/Kolkata")).toLocalTime().toString() else "N/A"
        val exitTimeStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.exitTime), ZoneId.of("Asia/Kolkata")).toLocalTime().toString()

        val tpsFormatted = if (t.targets.isNotEmpty()) t.targets.take(5).joinToString(", ") { String.format("%.1f", it) } else "N/A"

        val emoji = if (pnl >= 0) "🟢" else "🔴"
        println("$emoji Symbol: $sym\n  Entry Time: $entryTimeStr | Exit Time: $exitTimeStr | Reason: $reason\n  Entry Price: ${String.format("%.2f", t.entryPrice)} | Initial SL: ${String.format("%.2f", t.initialStopLoss)} | Current SL: ${String.format("%.2f", t.stopLoss)} | Exit Price: ${String.format("%.2f", t.exitPrice)} | TP Level: TP${t.tpLevel}\n  Targets (TP1-TP5): [$tpsFormatted]\n")
    }

    println("--------------------------------------------------")
    println("EOD Status: Net P&L: ${String.format("%.2f", dayPnl)} ${if (dayPnl >= 0) "🟢" else "🔴"} | Total Trades: ${trades.size}")
    println("==================================================")
}

suspend fun resolveAndFetchExpiredDerivativeCandles(
    viewModel: com.example.viewmodel.AuthViewModel,
    appId: String,
    token: String,
    indexSymbol: String,
    spotPrice: Double,
    optionType: String,
    tradeDate: String
): String {
    val strikeInterval = if (indexSymbol.contains("BANKNIFTY", ignoreCase = true) || indexSymbol.contains("SENSEX", ignoreCase = true) ) 100 else 50
    val atmStrike = calculateAtmStrike(spotPrice, strikeInterval)

    val rangeFrom = LocalDate.parse(tradeDate).minusMonths(1).toString()
    val rangeTo = LocalDate.parse(tradeDate).plusDays(7).toString()

//        println("   • [DEBUG] Resolving expiry dates for tradeDate=$tradeDate (range: $rangeFrom to $rangeTo)...")
    val expiryJson = viewModel.fetchHistoryExpiryDates(appId, token, indexSymbol, rangeFrom, rangeTo)
    var targetExpiry = tradeDate

    if (expiryJson != null && expiryJson.has("data")) {
        val dataObj = expiryJson.getJSONObject("data")
        if (dataObj.has("expiry_dates")) {
            val optionsExpiries = dataObj.getJSONObject("expiry_dates").optJSONArray("options")
            if (optionsExpiries != null) {
                var found = false
                for (i in 0 until optionsExpiries.length()) {
                    val expDate = optionsExpiries.getString(i)
                    if (expDate >= tradeDate) {
                        targetExpiry = expDate
                        found = true
                        break
                    }
                }
                if (!found && optionsExpiries.length() > 0) {
                    targetExpiry = optionsExpiries.getString(optionsExpiries.length() - 1)
                }
            }
        }
    }
//        println("   • [DEBUG] Target Expiry resolved to: $targetExpiry")

    // 2. Fetch underlying expired contracts for target expiry
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
        throw IllegalStateException("Could not resolve expired contract symbol for strike $atmStrike $optionType on expiry $targetExpiry for date $tradeDate. Contracts JSON: $contractsJson")
    }

    return optionSymbol

}

