package com.example.model

data class TradeState(
    val symbol: String,
    var status: String = "IDLE", // IDLE, PENDING_LIMIT, PENDING_STOP_BUY, ACTIVE, EXITED
    var entryPrice: Double = 0.0,
    var stopLoss: Double = 0.0,
    var initialStopLoss: Double = 0.0,
    var currentTpLevel: Int = 0, // 0 to 10
    var targets: List<Double> = emptyList(),
    var quantity: Int = 0,
    var entryTime: Long = 0L,
    var exitTime: Long = 0L,
    var priceBelowEntrySeen: Boolean = false,
    var partialExitTaken: Boolean = false
)
