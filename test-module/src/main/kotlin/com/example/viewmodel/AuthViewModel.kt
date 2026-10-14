package com.example.viewmodel

import com.example.model.AuthState
import com.example.repository.FyersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object DiskCache {
    private val cacheDir = File(".cache_historical_data").apply { if (!exists()) mkdirs() }

    fun get(key: String): JSONObject? {
        try {
            val safeKey = key.replace(":", "_").replace("-", "_").replace("/", "_")
            val file = File(cacheDir, "$safeKey.json")
            if (file.exists() && file.length() > 0) {
                val json = JSONObject(file.readText())
                if (json.has("candles") && json.getJSONArray("candles").length() > 0) {
                    return json
                }
                if (json.has("data")) {
                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null && dataObj.length() > 0) {
                        return json
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun put(key: String, json: JSONObject) {
        try {
            val hasValidCandles = json.has("candles") && json.getJSONArray("candles").length() > 0
            val hasValidData = json.has("data") && json.optJSONObject("data") != null && json.optJSONObject("data")!!.length() > 0
            if (hasValidCandles || hasValidData) {
                val safeKey = key.replace(":", "_").replace("-", "_").replace("/", "_")
                val file = File(cacheDir, "$safeKey.json")
                file.writeText(json.toString())
            }
        } catch (_: Exception) {}
    }
}

class AuthViewModel(private val repository: FyersRepository = FyersRepository()) {

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun prepareLoginUrl(appId: String, redirectUri: String) {
        val url = repository.getLoginUrl(appId, redirectUri)
        _authState.update { it.copy(loginUrl = url, errorMessage = null) }
    }

    suspend fun authenticateWithAuthCode(appId: String, secretKey: String, authCode: String) {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        
        val result = withContext(Dispatchers.IO) {
            repository.exchangeAuthCodeForToken(appId, secretKey, authCode)
        }

        result.fold(
            onSuccess = { token ->
                _authState.update { it.copy(accessToken = token, isLoading = false, errorMessage = null) }
            },
            onFailure = { error ->
                _authState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Unknown error") }
            }
        )
    }

    suspend fun fetchHistoricalData(appId: String, accessToken: String, symbol: String, resolution: String, rangeFrom: String, rangeTo: String, showError: Boolean = true): JSONObject? {
        val cacheKey = "hist_${symbol}_${resolution}_${rangeFrom}_${rangeTo}"
        DiskCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val result = repository.getHistoricalData(appId, accessToken, symbol, resolution, rangeFrom, rangeTo)
            if (result.isFailure) {
                if (showError)
                    println("Historical Data Error for $symbol: ${result.exceptionOrNull()?.message}")
            }
            val json = result.getOrNull()
            if (json != null) DiskCache.put(cacheKey, json)
            json
        }
    }

    suspend fun fetchHistoricalDataInChunks(appId: String, accessToken: String, symbol: String, resolution: String, rangeFrom: String, rangeTo: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val startLocalDate = java.time.LocalDate.parse(rangeFrom)
                val endLocalDate = java.time.LocalDate.parse(rangeTo)

                val allCandles = org.json.JSONArray()
                var currentStart = startLocalDate

                while (currentStart.isBefore(endLocalDate) || currentStart.isEqual(endLocalDate)) {
                    val currentEnd = currentStart.plusDays(89).let { if (it.isAfter(endLocalDate)) endLocalDate else it }

                    val chunkFrom = currentStart.toString()
                    val chunkTo = currentEnd.toString()

                    val json = fetchHistoricalData(appId, accessToken, symbol, resolution, chunkFrom, chunkTo, false)
                    val candles = json?.optJSONArray("candles")
                    if (candles != null) {
                        for (i in 0 until candles.length()) {
                            allCandles.put(candles.getJSONArray(i))
                        }
                    }

                    currentStart = currentEnd.plusDays(1)
                }

                if (allCandles.length() > 0) {
                    org.json.JSONObject().apply {
                        put("s", "ok")
                        put("candles", allCandles)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                println("Chunked historical data error: ${e.message}")
                null
            }
        }
    }

    suspend fun fetchOptionChain(appId: String, accessToken: String, symbol: String, strikeCount: Int): JSONObject? {
        return withContext(Dispatchers.IO) {
            val result = repository.getOptionChain(appId, accessToken, symbol, strikeCount)
            if (result.isFailure) {
                println("Option Chain Error for $symbol: ${result.exceptionOrNull()?.message}")
            }
            result.getOrNull()
        }
    }

    suspend fun fetchHistoryExpiryDates(appId: String, accessToken: String, symbol: String, rangeFrom: String, rangeTo: String): JSONObject? {
        val cacheKey = "expiry_${symbol}_${rangeFrom}_${rangeTo}"
        DiskCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val result = repository.getHistoryExpiryDates(appId, accessToken, symbol, rangeFrom, rangeTo)
            if (result.isFailure) {
                println("Expiry Dates Error: ${result.exceptionOrNull()?.message}")
            }
            val json = result.getOrNull()
            if (json != null) DiskCache.put(cacheKey, json)
            json
        }
    }

    suspend fun fetchHistoryUnderlyingSymbols(appId: String, accessToken: String, symbol: String, expiryDate: String): JSONObject? {
        val cacheKey = "contracts_${symbol}_${expiryDate}"
        DiskCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val result = repository.getHistoryUnderlyingSymbols(appId, accessToken, symbol, expiryDate)
            if (result.isFailure) {
                println("Underlying Symbols Error: ${result.exceptionOrNull()?.message}")
            }
            val json = result.getOrNull()
            if (json != null) DiskCache.put(cacheKey, json)
            json
        }
    }

    suspend fun fetchHistoryFNOExpired(appId: String, accessToken: String, fnoSymbol: String, rangeFrom: String, rangeTo: String, resolution: String): JSONObject? {
        val cacheKey = "fno_${fnoSymbol}_${resolution}_${rangeFrom}_${rangeTo}"
        DiskCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val result = repository.getHistoryFNOExpired(appId, accessToken, fnoSymbol, rangeFrom, rangeTo, resolution)
            if (result.isFailure) {
                println("FNO Expired History Error for $fnoSymbol: ${result.exceptionOrNull()?.message}")
            }
            val json = result.getOrNull()
            if (json != null) DiskCache.put(cacheKey, json)
            json
        }
    }

    fun getCachedToken(): String? {
        return repository.getCachedToken()
    }

    fun clearToken() {
        repository.clearToken()
    }
}
