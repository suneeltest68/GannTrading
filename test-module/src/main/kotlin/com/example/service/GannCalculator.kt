package com.example.service

import com.example.model.GannLevels
import com.example.util.TablePrinter
import kotlin.math.sqrt
import kotlin.math.round

object GannCalculator {
    fun calculate(pdc: Double): GannLevels {
        val sqCal = sqrt(pdc)
        val rawBsl = (sqCal - 0.0625) * (sqCal - 0.0625)
        val bsl = round(maxOf(0.05, rawBsl - 2.0) * 100.0) / 100.0
        val bt2 = round(((sqCal + 0.5) * (sqCal + 0.5)) * 100.0) / 100.0
        
        val targetsList = mutableListOf<Double>()
        for (i in 1..10) {
            val t = (sqCal + (i * 0.5 + 0.5)) * (sqCal + (i * 0.5 + 0.5))
            targetsList.add(round(t * 100.0) / 100.0)
        }

        return GannLevels(
            previousClose = pdc,
            stopLoss = bsl,
            buyPrice = bt2,
            targets = targetsList
        )
    }

    fun printTable(title: String, gann: GannLevels) {
        val headers = listOf("Level Type", "Price")
        val rows = mutableListOf<List<Any>>()
        rows.add(listOf("Previous Day Close (PDC)", round(gann.previousClose * 100) / 100))
        rows.add(listOf("Stop Loss (SL)", round(gann.stopLoss * 100) / 100))
        rows.add(listOf("Buy At or Above (Entry)", round(gann.buyPrice * 100) / 100))
        gann.targets.forEachIndexed { index, target ->
            rows.add(listOf("Target ${index + 1} (TP${index + 1})", round(target * 100) / 100))
        }
        TablePrinter.printTable("GANN BUY LEVELS up to TP10 [ $title ]", headers, rows)
    }
}
