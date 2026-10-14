package com.example

import com.example.viewmodel.AuthViewModel
import com.example.model.GannLevels
import com.example.service.GannCalculator
import com.example.service.PaperTradingEngine
import com.example.service.TradeJournalEntry
import com.example.OptionUtils
import com.example.util.TablePrinter
import org.json.JSONObject
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun main() = runBlocking {
    val dotenv = dotenv { ignoreIfMissing = true }
    val viewModel = AuthViewModel()

    println("==================================================")
    println("📊 GANN STRATEGY MULTI-DAY HISTORICAL BACKTEST ENGINE")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: return@runBlocking
    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: return@runBlocking
    println("[Cache] Using persistent cached Access Token.")

    val defaultStartDate = System.getenv("START_DATE") ?: "2026-10-01"
    val defaultEndDate = System.getenv("END_DATE") ?: "2026-10-14"

    val startDate = LocalDate.parse(defaultStartDate)
    val endDate = LocalDate.parse(defaultEndDate)

    println("\n[Backtest] Running Multi-Day Backtest from $startDate to $endDate...")

    val grandTotalTrades = mutableListOf<TradeJournalEntry>()
    var currentDate = startDate

    while (!currentDate.isAfter(endDate)) {
        if (currentDate.dayOfWeek == DayOfWeek.SATURDAY || currentDate.dayOfWeek == DayOfWeek.SUNDAY) {
            currentDate = currentDate.plusDays(1)
            continue
        }

        val backtestDate = currentDate.toString()
        println("\n📅 Processing Backtest Date: $backtestDate")

        val indices = listOf(
            Triple("Nifty 50", "NSE:NIFTY50-INDEX", 50.0),
//            Triple("Bank Nifty", "NSE:NIFTYBANK-INDEX", 100.0),
            Triple("Sensex", "BSE:SENSEX-INDEX", 100.0)
        )

        val symbolsGannMap = mutableMapOf<String, GannLevels>()
        val quantitiesMap = mutableMapOf<String, Int>()
        val symbolCandlesMap = mutableMapOf<String, List<CandleTick>>()

        try {
            for ((indexName, indexSymbol, strikeStep) in indices) {
                val indexHistory = viewModel.fetchHistoricalData(appId, token, indexSymbol, "D", backtestDate, backtestDate, false)
                val openPrice = indexHistory?.optJSONArray("candles")?.optJSONArray(0)?.optDouble(1, 0.0) ?: 0.0
                if (openPrice <= 0.0) continue

                val priorDate = getLatestTradingDay(viewModel, appId, token, indexSymbol, backtestDate)

                for (optionType in listOf("CE", "PE")) {
                    val isHistorical = LocalDate.parse(backtestDate).isBefore(LocalDate.now().minusDays(7))
                    val derivativeSymbol: String
                    val expiryDate: String
                    val candlesList: List<Candle>

                    if (isHistorical) {
                        val expiredRes = OptionUtils.resolveAndFetchExpiredDerivativeCandles(
                            viewModel = viewModel,
                            appId = appId,
                            token = token,
                            indexSymbol = indexSymbol,
                            spotPrice = openPrice,
                            optionType = optionType,
                            tradeDate = backtestDate,
                            expiryIndex = 0
                        )
                        derivativeSymbol = expiredRes.symbol
                        expiryDate = expiredRes.expiryDate
                        candlesList = expiredRes.candles
                    } else {
                        val resolvedOpt = OptionUtils.resolveWeeklyOptionWithExpiry(
                            viewModel = viewModel,
                            appId = appId,
                            token = token,
                            indexSymbol = indexSymbol,
                            spotPrice = openPrice,
                            optionType = optionType,
                            expiryIndex = 0
                        )
                        derivativeSymbol = resolvedOpt.symbol
                        expiryDate = resolvedOpt.expiryDate
                        val history = viewModel.fetchHistoricalDataInChunks(appId, token, derivativeSymbol, "5S", backtestDate, backtestDate)
                            ?: viewModel.fetchHistoricalData(appId, token, derivativeSymbol, "5S", backtestDate, backtestDate)
                        candlesList = parseCandlesFromHistory(history)
                    }

                    if (derivativeSymbol.isEmpty() || candlesList.isEmpty()) continue

                    val firstOpen = candlesList.firstOrNull()?.open ?: 0.0
                    val gann = fetchGannForSymbol(viewModel, appId, token, derivativeSymbol, priorDate, firstOpen)
                    GannCalculator.printTable(derivativeSymbol, gann)
                    symbolsGannMap[derivativeSymbol] = gann
                    quantitiesMap[derivativeSymbol] = if (indexName.contains("Bank")) 60 else if (indexName.contains("Sensex")) 40 else 130

                    symbolCandlesMap[derivativeSymbol] = candlesList.map { c ->
                        val time = LocalDateTime.parse(c.timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalTime()
                        CandleTick(derivativeSymbol, c.epoch, time, c.open, c.high, c.low, c.close)
                    }

                    val firstCandleTime = if (candlesList.isNotEmpty()) candlesList.first().timestamp else "N/A"
                    println("🎯 [DERIVATIVE RESOLVED] Symbol: $derivativeSymbol | Expiry Date: $expiryDate | First Candle Time: $firstCandleTime")
                }
            }

            if (symbolsGannMap.isNotEmpty()) {
                val dayTrades = mutableListOf<TradeJournalEntry>()
                val engine = PaperTradingEngine(symbolsGannMap, quantitiesMap).apply {
                    onTradeCompleted = { trade -> dayTrades.add(trade) }
                }

                val allTicks = symbolCandlesMap.values.flatten().sortedBy { it.timestamp }
                for (tick in allTicks) {
                    engine.onCandleTick(tick.symbol, tick.openPrice, tick.highPrice, tick.lowPrice, tick.closePrice, tick.time, tick.timestamp * 1000)
                }

                val runningCumPnl = grandTotalTrades.sumOf { it.pnl } + dayTrades.sumOf { it.pnl }
                printDetailedBacktestReport(backtestDate, dayTrades, runningCumPnl)
                grandTotalTrades.addAll(dayTrades)
            }
        } catch (e: Exception) {
            println("❌ Error on $backtestDate: ${e.message}")
        }

        currentDate = currentDate.plusDays(1)
    }

    printOverallBacktestSummary(grandTotalTrades)
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

suspend fun getLatestTradingDay(viewModel: AuthViewModel, appId: String, token: String, indexSymbol: String, targetDateStr: String): String {
    var date = LocalDate.parse(targetDateStr).minusDays(1)
    repeat(11) {
        val dateStr = date.toString()
        try {
            val history = viewModel.fetchHistoricalData(appId, token, indexSymbol, "D", dateStr, dateStr, false)
            if (history != null && history.has("candles") && history.getJSONArray("candles").length() > 0) {
                return dateStr
            }
        } catch (_: Exception) {}
        date = date.minusDays(1)
    }
    return LocalDate.parse(targetDateStr).minusDays(1).toString()
}

suspend fun fetchGannForSymbol(viewModel: AuthViewModel, appId: String, token: String, symbol: String, date: String, fallbackPdc: Double = 0.0): GannLevels {
    val history = viewModel.fetchHistoryFNOExpired(appId = appId, accessToken = token, fnoSymbol = symbol, rangeFrom = date, rangeTo = date, resolution = "D")
        ?: viewModel.fetchHistoricalData(appId, token, symbol, "D", date, date)
    val pdc = history?.optJSONArray("candles")?.optJSONArray(0)?.optDouble(4, 0.0) ?: 0.0
    val validPdc = if (pdc > 0.0) pdc else if (fallbackPdc > 0.0) fallbackPdc else 100.0
    return GannCalculator.calculate(validPdc)
}

fun parseCandlesFromHistory(json: JSONObject?): List<Candle> {
    val candles = mutableListOf<Candle>()
    if (json == null) return candles
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
        println("Error parsing candles: ${e.message}")
    }
    return candles.distinctBy { if (it.epoch > 0) it.epoch else it.timestamp }
        .sortedBy { if (it.epoch > 0) it.epoch else 0L }
}

fun printDetailedBacktestReport(dateStr: String, trades: List<TradeJournalEntry>, cumulativePnl: Double) {
    val dayPnl = trades.sumOf { it.pnl }
    val headers = listOf("Trade #", "Status", "Time Range", "Symbol", "Exit Reason", "TP Level", "P&L")

    val sortedTrades = trades.sortedWith(compareBy({ it.entryTime }, { it.exitTime }))
    val tradeNumberMap = mutableMapOf<Pair<Long, String>, Int>()
    var nextTradeNum = 1

    val rows = sortedTrades.map { t ->
        val key = Pair(t.entryTime, t.symbol)
        val tradeNum = tradeNumberMap.getOrPut(key) { nextTradeNum++ }

        val entryTimeStr = if (t.entryTime > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(t.entryTime), ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("HH:mm:ss")) else "N/A"
        val exitTimeStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.exitTime), ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val emoji = if (t.pnl >= 0) "🟢" else "🔴"
        val tpLabel = if (t.tpLevel > 0) "TP${t.tpLevel}" else "TP0"
        listOf(
            "#$tradeNum",
            emoji,
            "$entryTimeStr ➜ $exitTimeStr",
            t.symbol,
            t.exitReason,
            tpLabel,
            String.format("%.2f", t.pnl)
        )
    }
    TablePrinter.printTable(
        "BACKTEST REPORT [ $dateStr ] | Day P&L: ${String.format("%.2f", dayPnl)} | Cum P&L: ${String.format("%.2f", cumulativePnl)}",
        headers,
        rows
    )
}

fun printOverallBacktestSummary(trades: List<TradeJournalEntry>) {
    val totalPnl = trades.sumOf { it.pnl }
    val wins = trades.count { it.pnl > 0 }
    val losses = trades.count { it.pnl < 0 }
    val breakEvens = trades.count { it.pnl == 0.0 }

    val actualSlCount = trades.count { it.exitReason == "ACTUAL_SL" }
    val c2cCount = trades.count { it.exitReason == "COST_TO_COST" }
    val trailingSlCount = trades.count { it.exitReason == "TRAILING_SL" }
    val partialTp1Count = trades.count { it.exitReason == "PARTIAL_TP1" }
    val autoSquareOffCount = trades.count { it.exitReason == "AUTO_SQUARE_OFF" }

    val winRate = if (trades.isNotEmpty()) (wins.toDouble() / trades.size) * 100 else 0.0

    val headers = listOf("Metric", "Value")
    val rows = listOf(
        listOf("Overall Net P&L", "${String.format("%.2f", totalPnl)} ${if (totalPnl >= 0) "🟢" else "🔴"}"),
        listOf("Total Trades", trades.size),
        listOf("Wins / Losses / Break-Even", "$wins / $losses / $breakEvens"),
        listOf("Win Rate", "${String.format("%.1f", winRate)}%"),
        listOf("Partial Exit at TP1 (Lot 1)", partialTp1Count),
        listOf("Trailing SLs (Profit Lock)", trailingSlCount),
        listOf("Cost to Cost (C2C)", c2cCount),
        listOf("Actual SLs Hit (Full Loss)", actualSlCount),
        listOf("Auto Square-Off (EOD)", autoSquareOffCount)
    )
    TablePrinter.printTable("OVERALL MULTI-DAY BACKTEST SUMMARY REPORT", headers, rows)
}
