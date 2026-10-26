package com.example.service

import com.example.model.GannLevels
import com.example.model.TradeState
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PaperTradingEngine(
    private val symbolsGannMap: Map<String, GannLevels>,
    private val quantitiesMap: Map<String, Int>,
    private val enablePartialExit: Boolean = true
) {
    private val stateFile = File("active_trades.json")
    private val tradesMap = ConcurrentHashMap<String, TradeState>()
    private val scope = CoroutineScope(Dispatchers.IO)

    var onTradeCompleted: ((TradeJournalEntry) -> Unit)? = null

    private fun isBacktestMode(): Boolean = onTradeCompleted != null

    private fun logEvent(msg: String) {
        if (!isBacktestMode()) {
            println(msg)
            scope.launch { TelegramNotifier.sendAlert(msg) }
        }
    }

    private fun recordTrade(entry: TradeJournalEntry) {
        if (onTradeCompleted != null) {
            onTradeCompleted?.invoke(entry)
        } else {
            TradingJournalService.logTrade(entry)
        }
    }

    fun getTrade(symbol: String): TradeState? = tradesMap[symbol]

    init {
        // Start each trading session fresh by clearing any previous day's state file
        if (stateFile.exists()) {
            stateFile.delete()
        }
        loadState()
        for ((symbol, gann) in symbolsGannMap) {
            val existing = tradesMap[symbol]
            val qty = quantitiesMap[symbol] ?: 1
            if (existing == null) {
                tradesMap[symbol] = TradeState(
                    symbol = symbol,
                    status = "IDLE",
                    stopLoss = gann.stopLoss,
                    initialStopLoss = gann.stopLoss,
                    entryPrice = gann.buyPrice,
                    targets = gann.targets,
                    quantity = qty
                )
            } else {
                existing.entryPrice = gann.buyPrice
                if (existing.status == "IDLE") {
                    existing.stopLoss = gann.stopLoss
                    existing.initialStopLoss = gann.stopLoss
                }
                existing.targets = gann.targets
            }
        }
    }

    private fun formatEntryMessage(symbol: String, entryPrice: Double, quantity: Int, dateStr: String, timeStr: String): String {
        return """
            🚀 ENTRY · GANN
            Direction: LONG
            Instrument: BUY $symbol
              Entry: ₹${String.format("%.2f", entryPrice)}
            Qty: $quantity
            [PAPER] $dateStr $timeStr IST
        """.trimIndent()
    }

    private fun formatExitMessage(symbol: String, exitType: String, entryPrice: Double, exitPrice: Double, quantity: Int, pnl: Double, dateStr: String, timeStr: String): String {
        val emoji = when {
            pnl > 0 -> "🟢"
            pnl < 0 -> "🔴"
            else -> "⚪"
        }
        val pnlSign = if (pnl > 0) "+₹" else if (pnl < 0) "-₹" else "₹"
        val pnlFormatted = "$pnlSign${String.format("%.2f", kotlin.math.abs(pnl))}"

        return """
            🏁 EXIT · GANN ($exitType)
            Direction: LONG
            Instrument: SELL $symbol
              Entry: ₹${String.format("%.2f", entryPrice)} → Exit: ₹${String.format("%.2f", exitPrice)}
            Qty: $quantity
            $emoji P&L: $pnlFormatted
            [PAPER] $dateStr $timeStr IST
        """.trimIndent()
    }

    private fun formatReEntryMessage(symbol: String, entryPrice: Double, dateStr: String, timeStr: String): String {
        return """
            🔄 RE-ENTRY · GANN
            Direction: LONG
            Instrument: BUY $symbol
              Buy Price: ₹${String.format("%.2f", entryPrice)}
            [PAPER] $dateStr $timeStr IST
        """.trimIndent()
    }

    fun onTick(symbol: String, ltp: Double, testTime: LocalTime? = null, testTimestamp: Long = 0L) {
        onCandleTick(symbol, ltp, ltp, ltp, ltp, testTime, testTimestamp)
    }

    fun onCandleTick(symbol: String, open: Double, high: Double, low: Double, close: Double, testTime: LocalTime? = null, testTimestamp: Long = 0L) {
        val trade = tradesMap[symbol] ?: return
        val gann = symbolsGannMap[symbol] ?: return
        val ts = if (testTimestamp > 0) testTimestamp else System.currentTimeMillis()

        val istZone = ZoneId.of("Asia/Kolkata")
        val now = testTime ?: LocalTime.now(istZone)
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val dateStr = LocalDate.now(istZone).toString()

        // Do not process order placement or trade triggers before 9:20 AM IST
        val marketTradingStart = LocalTime.of(9, 20)
        if (now.isBefore(marketTradingStart)) {
            return
        }

        val squareOffTime = LocalTime.of(15, 25)
        if (now.isAfter(squareOffTime) || now == squareOffTime) {
            try {
                if (trade.status == "ACTIVE") {
                    val pnl = (close - trade.entryPrice) * trade.quantity
                    val msg = formatExitMessage(symbol, "AUTO_SQUARE_OFF", trade.entryPrice, close, trade.quantity, pnl, dateStr, timeStr)
                    logEvent(msg)
                    
                    trade.exitTime = ts
                    recordTrade(TradeJournalEntry(symbol, trade.entryPrice, close, trade.quantity, pnl, "AUTO_SQUARE_OFF", entryTime = trade.entryTime, exitTime = trade.exitTime, tpLevel = trade.currentTpLevel, stopLoss = trade.stopLoss, initialStopLoss = trade.initialStopLoss, targets = trade.targets))

                    trade.status = "EXITED"
                    saveState()
                    checkAndSendEodSummary()
                }
            } catch (e: Exception) {
                TradingJournalService.logError("Auto Square-off error for $symbol: ${e.message}")
            }
            return
        }

        when (trade.status) {
            "IDLE" -> {
                if (open > trade.entryPrice) {
                    trade.status = "PENDING_LIMIT"
                    saveState()
                    if (low <= trade.entryPrice) {
                        triggerActiveEntry(symbol, trade, now, ts, timeStr, dateStr)
                    }
                } else if (open < trade.entryPrice) {
                    trade.status = "PENDING_STOP_BUY"
                    saveState()
                    if (high >= trade.entryPrice) {
                        triggerActiveEntry(symbol, trade, now, ts, timeStr, dateStr)
                    }
                } else {
                    triggerActiveEntry(symbol, trade, now, ts, timeStr, dateStr)
                }
            }
            "PENDING_LIMIT" -> {
                if (low <= trade.entryPrice) {
                    triggerActiveEntry(symbol, trade, now, ts, timeStr, dateStr)
                }
            }
            "PENDING_STOP_BUY" -> {
                if (high >= trade.entryPrice) {
                    triggerActiveEntry(symbol, trade, now, ts, timeStr, dateStr)
                }
            }
            "ACTIVE" -> {
                val targets = trade.targets
                while (trade.currentTpLevel < targets.size) {
                    val nextTarget = targets[trade.currentTpLevel]
                    if (high >= nextTarget) {
                        trade.currentTpLevel++
                        if (trade.currentTpLevel == 1) {
                            if (enablePartialExit && trade.quantity >= 2 && !trade.partialExitTaken) {
                                val exitQty = trade.quantity / 2
                                trade.quantity -= exitQty
                                trade.partialExitTaken = true
                                val partialPnl = (nextTarget - trade.entryPrice) * exitQty
                                val msg = formatExitMessage(symbol, "PARTIAL_TP1", trade.entryPrice, nextTarget, exitQty, partialPnl, dateStr, timeStr)
                                logEvent(msg)
                                recordTrade(TradeJournalEntry(symbol, trade.entryPrice, nextTarget, exitQty, partialPnl, "PARTIAL_TP1", entryTime = trade.entryTime, exitTime = ts, tpLevel = 1, stopLoss = trade.entryPrice, initialStopLoss = trade.initialStopLoss, targets = trade.targets))
                            }
                            trade.stopLoss = trade.entryPrice
                        } else {
                            trade.stopLoss = targets[trade.currentTpLevel - 2]
                        }
                        saveState()
                    } else {
                        break
                    }
                }

                if (low <= trade.stopLoss) {
                    try {
                        val pnl = (trade.stopLoss - trade.entryPrice) * trade.quantity
                        
                        val exitType = when {
                            trade.currentTpLevel == 0 -> "ACTUAL_SL"
                            trade.currentTpLevel == 1 -> "COST_TO_COST"
                            else -> "TRAILING_SL"
                        }

                        val msg = formatExitMessage(symbol, exitType, trade.entryPrice, trade.stopLoss, trade.quantity, pnl, dateStr, timeStr)
                        logEvent(msg)

                        trade.exitTime = ts
                        recordTrade(TradeJournalEntry(symbol, trade.entryPrice, trade.stopLoss, trade.quantity, pnl, exitType, entryTime = trade.entryTime, exitTime = trade.exitTime, tpLevel = trade.currentTpLevel, stopLoss = trade.stopLoss, initialStopLoss = trade.initialStopLoss, targets = trade.targets))

                        trade.status = "EXITED"
                        trade.priceBelowEntrySeen = (low < trade.entryPrice)
                        saveState()
                    } catch (e: Exception) {
                        TradingJournalService.logError("Stop loss error for $symbol: ${e.message}")
                    }
                    return
                }
            }
            "EXITED" -> {
                // Cooldown: enforce a mandatory 1-minute (60,000 ms) wait window after exit
                val cooldownMillis = 60_000L
                val timeSinceExit = ts - trade.exitTime
                if (trade.exitTime > 0 && timeSinceExit < cooldownMillis) {
                    return
                }

                // 1. First, require a dip below entry AFTER the cooldown period has expired
                if (!trade.priceBelowEntrySeen && low < trade.entryPrice) {
                    trade.priceBelowEntrySeen = true
                    saveState()
                    return // Wait for subsequent tick/candle to cross back up above entry
                }

                // 2. Then, trigger re-entry when price crosses back up to entry
                if (trade.priceBelowEntrySeen && high >= trade.entryPrice) {
                    val msg = formatReEntryMessage(symbol, trade.entryPrice, dateStr, timeStr)
                    logEvent(msg)

                    trade.status = "ACTIVE"
                    trade.stopLoss = gann.stopLoss
                    trade.currentTpLevel = 0
                    trade.entryTime = ts
                    trade.priceBelowEntrySeen = false
                    trade.quantity = quantitiesMap[symbol] ?: trade.quantity
                    trade.partialExitTaken = false
                    saveState()
                }
            }
        }
    }

    private fun triggerActiveEntry(symbol: String, trade: TradeState, now: LocalTime, ts: Long, timeStr: String, dateStr: String) {
        val marketTradingStart = LocalTime.of(9, 20)
        if (now.isBefore(marketTradingStart)) {
            return
        }

        val msg = formatEntryMessage(symbol, trade.entryPrice, trade.quantity, dateStr, timeStr)
        logEvent(msg)

        trade.status = "ACTIVE"
        trade.currentTpLevel = 0
        trade.entryTime = ts
        saveState()
    }

    private fun checkAndSendEodSummary() {
        if (isBacktestMode()) return
        val allExited = tradesMap.values.all { it.status == "EXITED" }
        if (allExited) {
            val journalReport = TradingJournalService.getJournalReport()
            val summary = buildString {
                append("📊 *END OF DAY P&L SUMMARY REPORT*\n\n")
                var totalPnl = 0.0
                for ((sym, tr) in tradesMap) {
                    val pnl = (tr.stopLoss - tr.entryPrice) * tr.quantity
                    totalPnl += pnl
                    append("• $sym: Entry ${String.format("%.2f", tr.entryPrice)} | Qty ${tr.quantity} | Final SL/Exit ${String.format("%.2f", tr.stopLoss)} | P&L: ${String.format("%.2f", pnl)}\n")
                }
                append("\n💰 *TOTAL P&L: ${String.format("%.2f", totalPnl)}*\n\n")
                append(journalReport)
            }
            println(summary)
            scope.launch { TelegramNotifier.sendAlert(summary) }
        }
    }

    private fun loadState() {
        try {
            if (stateFile.exists()) {
                val jsonStr = stateFile.readText()
                val json = JSONObject(jsonStr)
                for (key in json.keys()) {
                    val obj = json.getJSONObject(key)
                    val trade = TradeState(
                        symbol = obj.getString("symbol"),
                        status = obj.getString("status"),
                        entryPrice = obj.getDouble("entryPrice"),
                        stopLoss = obj.getDouble("stopLoss"),
                        currentTpLevel = obj.getInt("currentTpLevel"),
                        quantity = obj.getInt("quantity"),
                        entryTime = obj.getLong("entryTime"),
                        priceBelowEntrySeen = obj.optBoolean("priceBelowEntrySeen", false),
                        partialExitTaken = obj.optBoolean("partialExitTaken", false)
                    )
                    tradesMap[trade.symbol] = trade
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveState() {
        try {
            val json = JSONObject()
            for ((symbol, trade) in tradesMap) {
                val obj = JSONObject().apply {
                    put("symbol", trade.symbol)
                    put("status", trade.status)
                    put("entryPrice", trade.entryPrice)
                    put("stopLoss", trade.stopLoss)
                    put("currentTpLevel", trade.currentTpLevel)
                    put("quantity", trade.quantity)
                    put("entryTime", trade.entryTime)
                    put("priceBelowEntrySeen", trade.priceBelowEntrySeen)
                    put("partialExitTaken", trade.partialExitTaken)
                }
                json.put(symbol, obj)
            }
            stateFile.writeText(json.toString(4))
        } catch (_: Exception) {}
    }

    fun printStatus() {
        if (!isBacktestMode()) {
            println("\n=== PAPER TRADING ENGINE STATUS ===")
            for ((symbol, trade) in tradesMap) {
                println("Symbol: $symbol | Status: ${trade.status} | Entry: ${String.format("%.2f", trade.entryPrice)} | SL: ${String.format("%.2f", trade.stopLoss)} | Active TP Level: ${trade.currentTpLevel}/10 | Qty: ${trade.quantity}")
            }
            println("=====================================")
        }
    }
}
