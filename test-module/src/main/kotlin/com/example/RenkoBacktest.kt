package com.example/*
 * RENKO BACKTESTER (Kotlin)  -  ONE FILE  (FYERS login + FYERS data API + Renko strategies)
 * ================================================================================================
 * Rules come from "Renko Charts" by Mukta Dhamankar (Renko.pdf).  Everything is in this single file and
 * uses ONLY the JDK (11+) and the Kotlin standard library - no Gradle dependencies, no JSON/HTTP libraries:
 *   1. FYERS authentication (auth-code login, token cache, refresh-token renewal)
 *   2. FYERS Data API client (history, expired F&O contracts) with rate limiting + disk cache
 *   3. Renko brick builder + indicators (EMA, DEMA, TEMA, RSI/ARSI, Donchian, SuperTrend)
 *   4. Pattern scanner (swing breakout, One-Back, Two-Back, multi-swing + targets)
 *   5. Trade simulator (next-bar-open fills, slippage, Indian cost model)
 *   6. Strategies S1-S7, S9-S13 and a performance report (win %, drawdown, recovery, PF ...)
 *   7. --selftest (no credentials needed) and --probe (checks your FYERS access + API reply shapes)
 *
 * BUILD + RUN (command line)
 *   kotlinc RenkoBacktest.kt -include-runtime -d renko.jar
 *   java -jar renko.jar --selftest
 *   java -jar renko.jar --probe
 *   java -jar renko.jar --strategies S1,S7 --start 2024-01-01 --end 2026-10-10
 *
 * BUILD + RUN (Gradle / IntelliJ / Android Studio "plain JVM" module) - put this file in src/main/kotlin/ :
 *   build.gradle.kts :  plugins { kotlin("jvm") version "2.0.21"; application }
 *                       repositories { mavenCentral() }
 *                       kotlin { jvmToolchain(17) }
 *                       application { mainClass.set("RenkoBacktestKt") }      // <FileName>Kt
 *   run              :  gradle run --args="--selftest"
 *
 * CREDENTIALS (environment variables or a .env file next to where you run it; asked at run time if missing)
 *   FYERS_APP_ID="XXXXXXXXXX-100"   FYERS_SECRET_KEY="xxxx"   FYERS_REDIRECT_URI="http://127.0.0.1:5000/"
 *   FYERS_PIN="1234"   (optional - lets the refresh token renew the login for ~15 days without a browser)
 *
 * LOGIN FLOW  : the program prints a FYERS login URL -> you log in in the browser -> FYERS redirects to your
 *               redirect URI with ?auth_code=...  -> if the redirect URI is http://127.0.0.1:PORT/ the program
 *               catches it automatically, otherwise (or with --manual-login) you paste the redirected URL.
 *               The access token is cached in fyers_token.json (valid until early next morning).
 *
 * STRATEGIES  : S1 Nifty DMA intraday | S2 Nifty DMA positional | S3 Nifty SuperTrend positional
 *               S4 BankNifty DMA intraday | S5 BankNifty DMA positional | S6 BankNifty SuperTrend positional
 *               S7 Multi-swing breakout (Nifty; S7B = BankNifty) | S9 TEMA+RSI stocks (daily, --symbols)
 *               S10 Donchian + ATM option | S11 Option buying (Two-Back + ARSI) | S12 Option selling (swing breakdown + ARSI)
 *               S13 ETFs (daily, --symbols).   (S8 "top-down trading" has no mechanical rule in the PDF.)
 *
 * MODEL (read before trusting results)
 *   * Renko: percentage box of the last brick close, continuous across days, 2-box reversal (TradingView style).
 *   * Signals are evaluated when a brick prints; orders fill at the NEXT 1-min bar open +/- slippage (--fill brick_close
 *     fills at the brick level instead).  A stop = a brick CLOSING beyond the swing low/high ("one brick below" in the PDF).
 *   * Intraday strategies enter 09:20-15:00 and square off at 15:15 (the PDF gives no times: --entry-start/--entry-end/--squareoff).
 *   * S10/S11/S12 assumptions (not spelled out in the PDF): ATM strike of the nearest expiry (S11/S12 pick it at --opt-atm-time,
 *     S10 at the Donchian signal), ARSI = EMA(9) of RSI(14) on bricks with threshold 50, S10 must find the option swing
 *     breakout within --arm-minutes of the spot signal.
 *   * Costs: see COSTS (approximate - edit to your contract note).  Lot size is constant over the whole test (--lot-size).
 *   * FYERS expired-contract endpoints are in beta: expect missing expiries / truncated days; skipped days are counted in the log.
 */

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.system.exitProcess

// =====================================================================================================
// 0. CONSTANTS / PRESETS / SMALL UTILITIES
// =====================================================================================================
const val API_BASE = "https://api-t1.fyers.in/api/v3"
const val DATA_BASE = "https://api-t1.fyers.in/data"
const val EPS = 1e-9
const val IST_SHIFT = 19800L                       // all bar times are kept as "local IST clock" epoch seconds
val TOKEN_ERROR_CODES = setOf(-8, -15, -16, -17)   // FYERS "token expired / invalid" codes
val NAN = Double.NaN
fun rint(x: Double): Double = Math.rint(x)       // round-half-even, like Python's round()

class Instr(val spot: String, val expiredUnd: String, val optPrefix: String, val step: Int, val lot: Int)

// NIFTY lot 65 is taken from your earlier backtest files; BANKNIFTY 30 is an ASSUMPTION - override with --lot-size
val INSTR = mapOf(
    "NIFTY" to Instr("NSE:NIFTY50-INDEX", "NSE:NIFTY50", "NSE:NIFTY", 50, 65),
    "BANKNIFTY" to Instr("NSE:NIFTYBANK-INDEX", "NSE:NIFTYBANK", "NSE:BANKNIFTY", 100, 30)
)

/** pct = Renko box in % of price (PDF page 13 + strategy pages), res = candle resolution used to build the bricks. */
class Preset(
    val desc: String, val und: String?, val kind: String, val pct: Double, val res: String,
    val intraday: Boolean, val warm: Int, val crossExit: Boolean = false, val st: Pair<Int, Double> = Pair(40, 12.0)
)

val PRESETS: Map<String, Preset> = linkedMapOf(
    "S1" to Preset("Nifty DMA intraday (20/40 brick EMA, 0.05% box)", "NIFTY", "dma", 0.05, "1", true, 10),
    "S2" to Preset("Nifty DMA positional (0.10% box)", "NIFTY", "dma", 0.10, "1", false, 60, crossExit = true),
    "S3" to Preset("Nifty SuperTrend(40,12) positional (0.10% box)", "NIFTY", "st", 0.10, "1", false, 60),
    "S4" to Preset("BankNifty DMA intraday (0.08% box)", "BANKNIFTY", "dma", 0.08, "1", true, 10),
    "S5" to Preset("BankNifty DMA positional (0.15% box)", "BANKNIFTY", "dma", 0.15, "1", false, 60, crossExit = true),
    "S6" to Preset("BankNifty SuperTrend(40,12) positional (0.15% box)", "BANKNIFTY", "st", 0.15, "1", false, 60),
    "S7" to Preset("Nifty Multi-swing breakout intraday (0.05% box)", "NIFTY", "multi", 0.05, "1", true, 10),
    "S7B" to Preset("BankNifty Multi-swing breakout intraday (0.08% box)", "BANKNIFTY", "multi", 0.08, "1", true, 10),
    "S9" to Preset("TEMA 20>30>40 + RSI>50, 1% box, 2% SL (daily, --symbols)", null, "s9", 1.0, "1D", false, 500),
    "S10" to Preset("Nifty: Donchian(40) on 0.075% box -> ATM option, 3% box swing breakout + DEMA 20>40", "NIFTY", "s10", 0.075, "1", true, 10),
    "S11" to Preset("Option buying: Two-Back bullish on 3% option box + ARSI(14,9)", "NIFTY", "s11", 3.0, "1", true, 0),
    "S12" to Preset("Option selling: swing breakdown on 3% option box + ARSI(14,9)", "NIFTY", "s12", 3.0, "1", true, 0),
    "S13" to Preset("ETFs: 0.25% daily box, EMA 20>40, SuperTrend(40,14) (--symbols)", null, "s13", 0.25, "1D", false, 500, st = Pair(40, 14.0))
)

/** Charges.  Brokerage Rs20/order, 0.05% STT on sell and GST 18% match your earlier notes; the rest are APPROXIMATIONS. */
class CostProfile(val brokerage: Double, val sttSell: Double, val sttBuy: Double, val exch: Double, val sebi: Double, val stampBuy: Double)

val COSTS = mapOf(
    "futures" to CostProfile(20.0, 0.0005, 0.0, 0.0000183, 0.000001, 0.00002),
    "options" to CostProfile(20.0, 0.0015, 0.0, 0.0003503, 0.000001, 0.00003),
    "equity" to CostProfile(20.0, 0.001, 0.001, 0.0000297, 0.000001, 0.00015)
)
const val GST = 0.18

fun hhmmToMin(s: String): Int {
    val p = s.trim().split(":")
    return p[0].toInt() * 60 + p[1].toInt()
}

fun log(vararg a: Any?) {
    println(a.joinToString(" ") { it.toString() })
    System.out.flush()
}

fun r2(x: Double) = if (x.isNaN() || x.isInfinite()) x else Math.round(x * 100.0) / 100.0
fun fmtNum(x: Double, d: Int = 0): String = when {
    x.isNaN() -> "nan"
    x.isInfinite() -> if (x > 0) "inf" else "-inf"
    d == 0 -> String.format("%.0f", x)
    else -> String.format("%.${d}f", x)
}

fun fmtT(t: Long): String = LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
fun dayOf(t: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(t, 86400L))
fun localEpoch(d: LocalDate): Long = d.toEpochDay() * 86400L
fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")
fun urlEncode(m: Map<String, String>): String = m.entries.joinToString("&") { urlEnc(it.key) + "=" + urlEnc(it.value) }

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { String.format("%02x", it) }

/** Growable primitive lists (no boxing). */
class DL {
    var a = DoubleArray(1024); var n = 0
    fun add(x: Double) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
    fun toArray(): DoubleArray = a.copyOf(n)
}

class IL {
    var a = IntArray(1024); var n = 0
    fun add(x: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
    fun toArray(): IntArray = a.copyOf(n)
}

// =====================================================================================================
// 0b. MINIMAL JSON + HTTP (JDK only)
// =====================================================================================================
object Json {
    fun parse(s: String): Any? = P(s).run { ws(); value() }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(m: String): Nothing = throw IllegalArgumentException("JSON: $m at $i")
        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i] != ':') fail("expected ':'")
                i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected ',' or '}'") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> fail("expected ',' or ']'") }
            }
        }
        fun str(): String {
            if (s[i] != '"') fail("expected string")
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                var c = s[i++]
                if (c == '\\') {
                    c = s[i++]
                    when (c) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(c)
                    }
                } else sb.append(c)
            }
            i++
            return sb.toString()
        }
        fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (st == i) fail("bad token '${s[i]}'")
            val t = s.substring(st, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }

    fun write(o: Any?): String = when (o) {
        null -> "null"
        is String -> "\"" + o.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Number, is Boolean -> o.toString()
        is Map<*, *> -> o.entries.joinToString(",", "{", "}") { write(it.key.toString()) + ":" + write(it.value) }
        is List<*> -> o.joinToString(",", "[", "]") { write(it) }
        else -> write(o.toString())
    }
}

/** Visits every leaf value with the name of the nearest enclosing key. */
fun walk(o: Any?, key: String = "", f: (String, Any?) -> Unit) {
    when (o) {
        is Map<*, *> -> o.forEach { (k, v) -> walk(v, k.toString(), f) }
        is List<*> -> o.forEach { walk(it, key, f) }
        else -> f(key, o)
    }
}

class Resp(val status: Int, val body: Any?) {
    @Suppress("UNCHECKED_CAST")
    val map: Map<String, Any?> get() = (body as? Map<String, Any?>) ?: mapOf("s" to "error", "message" to body.toString())
}

object Net {
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build() }

    private fun parse(raw: String): Any? = try { Json.parse(raw) } catch (e: Exception) { mapOf("s" to "error", "message" to raw.take(300)) }

    var getImpl: (String, Map<String, String>) -> Resp = { url, headers ->
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).GET()
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        Resp(r.statusCode(), parse(r.body()))
    }
    var postImpl: (String, String) -> Resp = { url, body ->
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        Resp(r.statusCode(), parse(r.body()))
    }

    fun get(url: String, headers: Map<String, String>) = getImpl(url, headers)
    fun post(url: String, body: Map<String, Any?>) = postImpl(url, Json.write(body))
}

// =====================================================================================================
// 1. FYERS AUTHENTICATION
// =====================================================================================================
class AuthError(msg: String) : RuntimeException(msg)

object IO {
    var readLineFn: (String) -> String = { p -> print(p); System.out.flush(); readLine() ?: "" }
    var readSecretFn: (String) -> String = { p ->
        val c = System.console()
        if (c != null) String(c.readPassword(p) ?: CharArray(0)) else readLineFn(p)
    }
}

val DOTENV = HashMap<String, String>()

/** Tiny .env reader (KEY=VALUE per line). */
fun loadDotenv(path: String = ".env") {
    val f = File(path)
    if (!f.exists()) return
    f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }.forEach {
        val k = it.substringBefore("=").trim()
        val v = it.substringAfter("=").trim().trim('"', '\'')
        DOTENV.putIfAbsent(k, v)
    }
}

fun envValue(name: String): String = (System.getenv(name) ?: DOTENV[name] ?: "").trim()

fun getCred(name: String, prompt: String, secret: Boolean = false, required: Boolean = true): String {
    var v = envValue(name)
    if (v.isEmpty() && (required || System.console() != null)) {
        v = try { (if (secret) IO.readSecretFn("$prompt: ") else IO.readLineFn("$prompt: ")).trim() } catch (e: Exception) { "" }
    }
    return v
}

/**
 * FYERS API v3 login.  Order of attempts (first that works wins):
 *   1. cached access token in fyers_token.json (checked live with /profile)
 *   2. refresh token + PIN  ->  POST /validate-refresh-token       (no browser)
 *   3. browser login        ->  auth_code  ->  POST /validate-authcode
 * appIdHash = SHA-256("APP_ID:SECRET_KEY")  (same as the official SDK).
 */
class FyersAuth(
    val appId: String, secret: String, val redirectUri: String, val pin: String = "",
    val tokenFile: File = File("fyers_token.json"), val manual: Boolean = false
) {
    private val secretKey = secret.trim()
    var cachedToken: String? = null

    val appHash: String get() = sha256Hex("${appId.trim()}:$secretKey")

    fun loginUrl(state: String = "renko_bt"): String =
        "$API_BASE/generate-authcode?" + urlEncode(linkedMapOf("client_id" to appId, "redirect_uri" to redirectUri, "response_type" to "code", "state" to state))

    fun isValid(token: String): Boolean = try {
        Net.get("$API_BASE/profile", mapOf("Authorization" to "$appId:$token", "version" to "3")).map["s"] == "ok"
    } catch (e: Exception) { false }

    @Suppress("UNCHECKED_CAST")
    private fun load(): Map<String, Any?> = try { Json.parse(tokenFile.readText()) as Map<String, Any?> } catch (e: Exception) { emptyMap() }

    private fun save(access: String, refresh: String?) {
        val old = load()
        val data = linkedMapOf<String, Any?>(
            "app_id" to appId, "access_token" to access,
            "refresh_token" to (refresh ?: old["refresh_token"] ?: ""),
            "saved_at" to LocalDateTime.now().withNano(0).toString()
        )
        tokenFile.writeText(Json.write(data))
        try { tokenFile.setReadable(false, false); tokenFile.setReadable(true, true); tokenFile.setWritable(true, true) } catch (e: Exception) { /* ignore */ }
    }

    private fun post(path: String, body: Map<String, Any?>): Map<String, Any?> = try {
        Net.post(API_BASE + path, body).map
    } catch (e: Exception) { mapOf("s" to "error", "message" to (e.message ?: e.toString())) }

    fun exchangeCode(code: String) = post("/validate-authcode", linkedMapOf("grant_type" to "authorization_code", "appIdHash" to appHash, "code" to code))
    fun refresh(rt: String) = post("/validate-refresh-token", linkedMapOf("grant_type" to "refresh_token", "appIdHash" to appHash, "refresh_token" to rt, "pin" to pin))

    /** If the redirect URI is http://127.0.0.1:PORT/..., listen on it and grab ?auth_code= automatically. */
    fun captureCode(timeoutSec: Long = 180): String {
        val u = try { URI.create(redirectUri) } catch (e: Exception) { return "" }
        if (u.scheme != "http" || (u.host != "127.0.0.1" && u.host != "localhost") || u.port <= 0) return ""
        var code = ""
        val latch = CountDownLatch(1)
        val srv = try { HttpServer.create(InetSocketAddress(u.host, u.port), 0) } catch (e: Exception) { return "" }
        srv.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery ?: ""
            q.split("&").map { it.split("=", limit = 2) }.firstOrNull { it[0] == "auth_code" && it.size == 2 }?.let { code = URLDecoder.decode(it[1], "UTF-8") }
            val body = "<h3>FYERS login captured - you can close this tab.</h3>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            if (code.isNotEmpty()) latch.countDown()
        }
        srv.start()
        try { latch.await(timeoutSec, TimeUnit.SECONDS) } finally { srv.stop(0) }
        return code
    }

    /** Fast path used by every data request: no network call once a token is loaded. */
    fun token(): String = cachedToken ?: getToken()

    fun getToken(forceLogin: Boolean = false): String {
        cachedToken?.let { if (!forceLogin && isValid(it)) return it }
        val cached = if (forceLogin) emptyMap() else load()
        val at = cached["access_token"] as? String
        if (!at.isNullOrEmpty() && cached["app_id"] == appId && isValid(at)) {
            cachedToken = at; log("[auth] cached FYERS access token is still valid"); return at
        }
        val rt = cached["refresh_token"] as? String
        if (!rt.isNullOrEmpty() && pin.isNotEmpty() && !forceLogin) {
            val r = refresh(rt)
            val na = r["access_token"] as? String
            if (!na.isNullOrEmpty()) {
                save(na, r["refresh_token"] as? String); cachedToken = na
                log("[auth] access token renewed with refresh token + PIN"); return na
            }
            log("[auth] refresh failed (" + (r["message"] ?: r).toString().take(120) + ") -> browser login needed")
        }
        val url = loginUrl()
        log("\n[auth] Open this URL, log in and approve the app:\n\n  $url\n")
        if (!manual) try { java.awt.Desktop.getDesktop().browse(URI.create(url)) } catch (e: Throwable) { /* headless: ignore */ }
        var code = if (manual) "" else captureCode()
        if (code.isEmpty()) code = extractCode(IO.readLineFn("Paste the full redirected URL (or the auth_code): "))
        val r = exchangeCode(code)
        val na = r["access_token"] as? String
        if (na.isNullOrEmpty()) throw AuthError("FYERS token exchange failed: $r")
        save(na, r["refresh_token"] as? String); cachedToken = na
        log("[auth] login OK, token cached in $tokenFile")
        return na
    }

    companion object {
        /** Accepts the full redirected URL, a query string, or the raw auth_code. */
        fun extractCode(text: String): String {
            val t = text.trim()
            val m = Regex("auth_code=([^&\\s]+)").find(t)
            return if (m != null) URLDecoder.decode(m.groupValues[1], "UTF-8") else t
        }
    }
}

// =====================================================================================================
// 2. BARS + FYERS DATA API CLIENT (history + expired F&O) with rate limit + cache
// =====================================================================================================
/** Time-ordered candles.  t = "local IST clock" epoch seconds (UTC epoch + 19800); daily bars sit at local midnight. */
class Bars(val t: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val v: DoubleArray) {
    val n: Int get() = t.size
    fun minute(i: Int): Int = (Math.floorMod(t[i], 86400L) / 60L).toInt()
    fun dayNum(i: Int): Int = Math.floorDiv(t[i], 86400L).toInt()

    fun filter(pred: (Int) -> Boolean): Bars {
        val idx = (0 until n).filter(pred)
        return Bars(LongArray(idx.size) { t[idx[it]] }, DoubleArray(idx.size) { o[idx[it]] }, DoubleArray(idx.size) { h[idx[it]] },
            DoubleArray(idx.size) { l[idx[it]] }, DoubleArray(idx.size) { c[idx[it]] }, DoubleArray(idx.size) { v[idx[it]] })
    }

    fun toCsv(): String {
        val sb = StringBuilder(n * 48)
        for (i in 0 until n) sb.append(t[i]).append(',').append(o[i]).append(',').append(h[i]).append(',').append(l[i]).append(',').append(c[i]).append(',').append(v[i]).append('\n')
        return sb.toString()
    }

    companion object {
        val EMPTY = Bars(LongArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))

        /** rows: [t, o, h, l, c, v]; sorts by time and drops duplicate timestamps. */
        fun fromRows(rows: List<DoubleArray>): Bars {
            if (rows.isEmpty()) return EMPTY
            val s = rows.sortedBy { it[0] }
            val keep = ArrayList<DoubleArray>(s.size)
            for (r in s) if (keep.isEmpty() || keep.last()[0] != r[0]) keep.add(r)
            return Bars(LongArray(keep.size) { keep[it][0].toLong() }, DoubleArray(keep.size) { keep[it][1] }, DoubleArray(keep.size) { keep[it][2] },
                DoubleArray(keep.size) { keep[it][3] }, DoubleArray(keep.size) { keep[it][4] }, DoubleArray(keep.size) { keep[it][5] })
        }

        fun concat(parts: List<Bars>): Bars {
            val rows = ArrayList<DoubleArray>()
            for (p in parts) for (i in 0 until p.n) rows.add(doubleArrayOf(p.t[i].toDouble(), p.o[i], p.h[i], p.l[i], p.c[i], p.v[i]))
            return fromRows(rows)
        }

        fun fromCsv(text: String): Bars {
            val rows = ArrayList<DoubleArray>()
            for (ln in text.lineSequence()) {
                if (ln.isBlank()) continue
                val p = ln.split(',')
                rows.add(DoubleArray(6) { p[it].toDouble() })
            }
            return fromRows(rows)
        }

        /** FYERS candle rows: [epochUTC, o, h, l, c, v]. */
        fun fromApi(candles: Any?): Bars {
            val rows = ArrayList<DoubleArray>()
            for (r in (candles as? List<*>).orEmpty()) {
                val x = r as? List<*> ?: continue
                if (x.size < 5) continue
                fun d(k: Int) = (x.getOrNull(k) as? Number)?.toDouble() ?: 0.0
                rows.add(doubleArrayOf(d(0) + IST_SHIFT, d(1), d(2), d(3), d(4), d(5)))
            }
            return fromRows(rows)
        }
    }
}

class FyersError(msg: String) : RuntimeException(msg)

/** FYERS allows 10 req/s and 200 req/min.  Stay below: 8/s and 160/min. */
class RateLimiter(private val perSec: Int = 8, private val perMin: Int = 160) {
    private val t = ArrayDeque<Long>()
    fun waitTurn() {
        while (true) {
            val now = System.currentTimeMillis()
            while (t.isNotEmpty() && now - t.first() > 60_000) t.removeFirst()
            val lastSec = t.count { now - it < 1000 }
            if (lastSec < perSec && t.size < perMin) { t.addLast(now); return }
            Thread.sleep(150)
        }
    }
}

/** Schema-tolerant: finds every date in an expiry-dates reply (ISO, dd-mm-yyyy, or epoch under an expiry/date key). */
fun parseDates(resp: Any?): List<LocalDate> {
    val out = sortedSetOf<LocalDate>()
    walk(resp) { key, v ->
        val s = (if (v is Double && v == Math.floor(v)) v.toLong().toString() else v.toString()).trim()
        when {
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(s) -> out.add(LocalDate.parse(s))
            Regex("\\d{2}-\\d{2}-\\d{4}").matches(s) -> out.add(LocalDate.of(s.substring(6).toInt(), s.substring(3, 5).toInt(), s.substring(0, 2).toInt()))
            Regex("\\d{10}").matches(s) && (key.lowercase().contains("expiry") || key.lowercase().contains("date")) ->
                out.add(dayOf(s.toLong() + IST_SHIFT))
        }
    }
    return out.toList()
}

val MONTH_CODE = mapOf(10 to "O", 11 to "N", 12 to "D")

fun buildOptionSymbol(prefix: String, expiry: LocalDate, strike: Int, cp: String, monthly: Boolean): String {
    val yy = String.format("%02d", expiry.year % 100)
    val mid = if (monthly) expiry.month.name.substring(0, 3) else (MONTH_CODE[expiry.monthValue] ?: expiry.monthValue.toString()) + String.format("%02d", expiry.dayOfMonth)
    return "$prefix$yy$mid$strike$cp"
}

/** Returns (strike, 'CE'/'PE') if `sym` is an option of `prefix` expiring on `expiry` (weekly or monthly format). */
fun parseOptionSymbol(sym: String, prefix: String, expiry: LocalDate): Pair<Int, String>? {
    if (!sym.startsWith(prefix) || !(sym.endsWith("CE") || sym.endsWith("PE"))) return null
    val rest = sym.substring(prefix.length, sym.length - 2)
    val yy = String.format("%02d", expiry.year % 100)
    val weekly = (MONTH_CODE[expiry.monthValue] ?: expiry.monthValue.toString()) + String.format("%02d", expiry.dayOfMonth)
    for (mid in listOf(weekly, expiry.month.name.substring(0, 3))) {
        val pre = yy + mid
        if (rest.startsWith(pre) && rest.length > pre.length && rest.substring(pre.length).all { it.isDigit() })
            return Pair(rest.substring(pre.length).toInt(), sym.substring(sym.length - 2))
    }
    return null
}

/** Only used when the expiry API gives nothing.  NIFTY weekly: Thursday until Aug-2025, Tuesday after (holiday shifts NOT modelled). */
fun fallbackExpiryRule(d: LocalDate, und: String): LocalDate? {
    if (und != "NIFTY") return null
    val wd = if (d < LocalDate.of(2025, 9, 1)) 4 else 2          // java DayOfWeek: Tue=2, Thu=4
    val delta = Math.floorMod(wd - d.dayOfWeek.value, 7)
    return d.plusDays(delta.toLong())
}

val CONTRACT_RE = Regex("^[A-Z]+:[A-Z0-9]+(CE|PE)$")

open class FyersData(val auth: FyersAuth?, cacheDir: String, val debug: Boolean = false) {
    val cache = File(cacheDir).also { it.mkdirs() }
    private val rl = RateLimiter()
    var calls = 0

    private fun headers(): Map<String, String> {
        val a = auth ?: throw AuthError("No FYERS credentials available (and no cached data for this request).")
        return mapOf("Authorization" to "${a.appId}:${a.token()}", "Content-Type" to "application/json", "version" to "3")
    }

    /** Raw GET against the FYERS data API with retry, rate limit and token-error handling. */
    open fun get(path: String, params: Map<String, String>): Map<String, Any?> {
        val url = DATA_BASE + path + "?" + urlEncode(params)
        var last: String? = null
        for (attempt in 0 until 4) {
            rl.waitTurn()
            val resp: Resp
            try { calls++; resp = Net.get(url, headers()) }
            catch (e: AuthError) { throw e }
            catch (e: Exception) { last = e.message ?: e.toString(); Thread.sleep(1500L * (attempt + 1)); continue }
            val j = resp.map
            if (debug) log("[api] $path $params -> ${j.toString().take(300)}")
            val code = (j["code"] as? Number)?.toInt()
            if (resp.status == 429 || code == 429 || code == -429) { Thread.sleep(3000L * (attempt + 1)); continue }
            if (code != null && code in TOKEN_ERROR_CODES) {
                auth?.let { it.cachedToken = null; it.tokenFile.delete() }
                throw AuthError("FYERS rejected the token (${j["message"]}). Re-run to log in again.")
            }
            return j
        }
        throw FyersError("request failed after retries: $last")
    }

    private fun fetchRange(symbol: String, res: String, a: LocalDate, b: LocalDate, expired: Boolean): Bars {
        val key = symbol.replace(':', '_').replace('&', '_') + "_${res}_${a}_${b}" + (if (expired) "_x" else "") + ".bars"
        val fp = File(cache, key)
        if (fp.exists()) return Bars.fromCsv(fp.readText())
        val params = linkedMapOf("symbol" to symbol, "resolution" to res, "date_format" to "1", "range_from" to a.toString(), "range_to" to b.toString())
        if (!expired) params["cont_flag"] = "1"
        val path = if (expired) "/history/fno/expired/historical-data" else "/history"
        val j = get(path, params)
        val s = j["s"]
        val df = when {
            s == "ok" -> Bars.fromApi(j["candles"])
            s == "no_data" || j["message"].toString().lowercase().contains("no_data") -> Bars.EMPTY
            else -> throw FyersError("$path $symbol $a..$b: $j")
        }
        if (b < LocalDate.now()) fp.writeText(df.toCsv())          // never cache a window that is still in progress
        return df
    }

    /** res: "1","5","15",... or "1D".  Minute data: max 100 days / request; daily: max 366 days / request. */
    fun candles(symbol: String, res: String, start: LocalDate, end: LocalDate, expired: Boolean = false): Bars {
        val daily = res.uppercase() == "1D" || res.uppercase() == "D"
        val chunk = if (daily) 360 else 95
        val parts = ArrayList<Bars>()
        if (expired) parts.add(fetchRange(symbol, res, start, end, true))
        else {
            val anchor = LocalDate.of(2015, 1, 1)
            var k = (start.toEpochDay() - anchor.toEpochDay()) / chunk
            while (true) {
                val s = anchor.plusDays(k * chunk)
                if (s > end) break
                val e = minOf(s.plusDays(chunk - 1L), LocalDate.now())
                parts.add(fetchRange(symbol, res, s, e, false))
                k++
            }
        }
        var df = Bars.concat(parts)
        if (df.n > 0) {
            val lo = localEpoch(start); val hi = localEpoch(end.plusDays(1))
            df = df.filter { df.t[it] in lo until hi }
            df = if (!daily) df.filter { df.minute(it) in (9 * 60 + 15) until (15 * 60 + 30) }
            else Bars(LongArray(df.n) { df.t[it] - Math.floorMod(df.t[it], 86400L) }, df.o, df.h, df.l, df.c, df.v)
        }
        return df
    }

    // ---- expired F&O catalogue ----
    fun expiryDates(undSymbol: String, start: LocalDate, end: LocalDate): List<LocalDate> {
        val fp = File(cache, "expiries_${undSymbol.replace(':', '_')}_${start}_${end}.json")
        if (fp.exists()) return (Json.parse(fp.readText()) as List<*>).map { LocalDate.parse(it.toString()) }
        val j = get("/history/fno/expired/expiry-dates", linkedMapOf("symbol" to undSymbol, "range_from" to start.toString(), "range_to" to end.toString(), "date_format" to "1"))
        val ds = if (j["s"] == "ok") parseDates(j).filter { it >= start.minusDays(1) && it <= end.plusDays(45) } else emptyList()
        if (ds.isNotEmpty() && end < LocalDate.now()) fp.writeText(Json.write(ds.map { it.toString() }))
        return ds
    }

    fun expiredContracts(undSymbol: String, expiry: LocalDate): List<String> {
        val fp = File(cache, "contracts_${undSymbol.replace(':', '_')}_${expiry}.json")
        if (fp.exists()) return (Json.parse(fp.readText()) as List<*>).map { it.toString() }
        val j = get("/history/fno/expired/underlying-symbols", linkedMapOf("symbol" to undSymbol, "expiry_date" to expiry.toString()))
        val out = sortedSetOf<String>()
        if (j["s"] == "ok") walk(j) { _, v -> if (v is String && CONTRACT_RE.matches(v)) out.add(v) }
        val syms = out.toList()
        if (syms.isNotEmpty()) fp.writeText(Json.write(syms))
        return syms
    }
}

/** Resolves ATM contracts and loads their 1-minute candles (expired endpoint first, normal history as fallback). */
class OptionBook(val fd: FyersData, val und: String, start: LocalDate, end: LocalDate) {
    val meta = INSTR.getValue(und)
    var expiries: List<LocalDate> = emptyList()
    private val contracts = HashMap<LocalDate, Map<Pair<Int, String>, String>>()
    var skipped = 0

    init {
        try { expiries = fd.expiryDates(meta.expiredUnd, start.minusDays(10), end) } catch (e: FyersError) { log("[options] expiry list failed: ${e.message}") }
        log("[options] ${expiries.size} expiry dates from API" + (if (expiries.isEmpty()) " -> using weekday rule (approx.)" else ""))
    }

    fun nearestExpiry(d: LocalDate, minDte: Int = 0): LocalDate? {
        for (e in expiries) {
            val dd = (e.toEpochDay() - d.toEpochDay()).toInt()
            if (dd >= minDte) { if (dd <= 12) return e; break }
        }
        return fallbackExpiryRule(d.plusDays(minDte.toLong()), und)
    }

    private fun isMonthly(e: LocalDate): Boolean {
        val same = expiries.filter { it.year == e.year && it.month == e.month }
        return same.isNotEmpty() && e == same.maxOrNull()
    }

    fun contract(e: LocalDate, strike: Int, cp: String): String {
        val m = contracts.getOrPut(e) {
            val mm = HashMap<Pair<Int, String>, String>()
            try { for (s in fd.expiredContracts(meta.expiredUnd, e)) parseOptionSymbol(s, meta.optPrefix, e)?.let { mm[it] = s } } catch (ex: FyersError) { /* fall through to built symbol */ }
            mm
        }
        return m[Pair(strike, cp)] ?: buildOptionSymbol(meta.optPrefix, e, strike, cp, isMonthly(e))
    }

    fun candles(sym: String, start: LocalDate, end: LocalDate): Bars {
        var df = try { fd.candles(sym, "1", start, end, expired = true) } catch (e: FyersError) { Bars.EMPTY }
        if (df.n == 0) df = try { fd.candles(sym, "1", start, end, expired = false) } catch (e: FyersError) { Bars.EMPTY }   // contract may still be live
        return if (df.n > 0) df.filter { df.minute(it) in (9 * 60 + 15) until (15 * 60 + 30) } else df
    }
}

// ---- CSV loader for your own 1-minute files ----
private val DT_FORMATS = listOf(
    "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
    "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyyMMdd HH:mm:ss", "yyyyMMdd HH:mm"
).map { DateTimeFormatter.ofPattern(it) }

/** Returns local IST clock epoch seconds. Accepts epoch s/ms, ISO with offset, and common date formats. */
fun parseLocalEpoch(raw: String): Long {
    val s = raw.trim().trim('"')
    s.toLongOrNull()?.let { return (if (s.length >= 13) it / 1000 else it) + IST_SHIFT }
    try { return OffsetDateTime.parse(s.replace(' ', 'T')).withOffsetSameInstant(ZoneOffset.ofHoursMinutes(5, 30)).toLocalDateTime().toEpochSecond(ZoneOffset.UTC) } catch (e: Exception) { /* next */ }
    try { return LocalDateTime.parse(s.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC) } catch (e: Exception) { /* next */ }
    for (f in DT_FORMATS) try { return LocalDateTime.parse(s, f).toEpochSecond(ZoneOffset.UTC) } catch (e: Exception) { /* next */ }
    throw IllegalArgumentException("cannot parse date/time '$raw'")
}

/** 1-minute CSV with a datetime (or timestamp / date+time) column and open,high,low,close[,volume]. */
fun loadCsvBars(path: String): Bars {
    val lines = File(path).readLines().filter { it.isNotBlank() }
    val head = lines[0].split(',').map { it.trim().trim('"').lowercase() }
    fun col(vararg names: String) = names.map { head.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
    val iDt = col("datetime", "timestamp"); val iDate = col("date"); val iTime = col("time")
    val iO = col("open"); val iH = col("high"); val iL = col("low"); val iC = col("close"); val iV = col("volume")
    require(iO >= 0 && iH >= 0 && iL >= 0 && iC >= 0) { "$path: need open,high,low,close columns, found $head" }
    require(iDt >= 0 || iDate >= 0) { "$path: no datetime/timestamp/date column in $head" }
    val rows = ArrayList<DoubleArray>(lines.size)
    for (k in 1 until lines.size) {
        val p = lines[k].split(',').map { it.trim().trim('"') }
        val ts = when {
            iDt >= 0 -> parseLocalEpoch(p[iDt])
            iTime >= 0 && iDate >= 0 -> parseLocalEpoch(p[iDate] + " " + p[iTime])
            else -> parseLocalEpoch(p[iDate])
        }
        rows.add(doubleArrayOf(ts.toDouble(), p[iO].toDouble(), p[iH].toDouble(), p[iL].toDouble(), p[iC].toDouble(), if (iV >= 0) p[iV].toDoubleOrNull() ?: 0.0 else 0.0))
    }
    val all = Bars.fromRows(rows)
    return all.filter { all.minute(it) in (9 * 60 + 15) until (15 * 60 + 30) }
}

// =====================================================================================================
// 3. RENKO + INDICATORS + PATTERN SCANNER
// =====================================================================================================
class Bricks(
    val bar: IntArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray,
    val dir: IntArray, val box: DoubleArray
) { val n: Int get() = bar.size }

/**
 * Percentage Renko (box = pct% of the last brick close, rounded to `tick`), continuous across days.
 * reversal=2 -> TradingView/classic (a reversal brick opens at the previous brick's OPEN, price must move 2 boxes);
 * reversal=1 -> reversal brick opens at the previous CLOSE.  Inside every 1-min bar the price path is
 * O->L->H->C (green bar) or O->H->L->C (red bar) when path="ohlc", or only the close when path="close".
 * fixedBox > 0 uses a fixed box in points instead of a percentage.
 */
fun buildRenko(b: Bars, pct: Double, tick: Double = 0.05, path: String = "ohlc", reversal: Int = 2, fixedBox: Double = 0.0): Bricks {
    fun boxf(p: Double) = if (fixedBox > 0) fixedBox else max(tick, rint(p * pct / 100.0 / tick) * tick)
    val bar = IL(); val bo = DL(); val bc = DL(); val bd = IL(); val bb = DL()
    var lc = NAN; var d = 0; var box = 0.0
    for (k in 0 until b.n) {
        val seq = if (path == "close") doubleArrayOf(b.c[k])
        else if (b.c[k] >= b.o[k]) doubleArrayOf(b.o[k], b.l[k], b.h[k], b.c[k]) else doubleArrayOf(b.o[k], b.h[k], b.l[k], b.c[k])
        for (p in seq) {
            if (lc.isNaN()) { lc = if (fixedBox > 0) p else rint(p / tick) * tick; box = boxf(lc); continue }
            while (true) {
                val upThr: Double; val dnThr: Double
                when (d) {
                    1 -> { upThr = lc + box; dnThr = lc - reversal * box }
                    -1 -> { dnThr = lc - box; upThr = lc + reversal * box }
                    else -> { upThr = lc + box; dnThr = lc - box }
                }
                val o: Double; val c: Double; val nd: Int
                if (p >= upThr - EPS) { o = if (d != -1) lc else lc + (reversal - 1) * box; c = o + box; nd = 1 }
                else if (p <= dnThr + EPS) { o = if (d != 1) lc else lc - (reversal - 1) * box; c = o - box; nd = -1 }
                else break
                bar.add(k); bo.add(o); bc.add(c); bd.add(nd); bb.add(box)
                lc = c; d = nd; box = boxf(c)
            }
        }
    }
    val oA = bo.toArray(); val cA = bc.toArray()
    return Bricks(bar.toArray(), oA, DoubleArray(oA.size) { max(oA[it], cA[it]) }, DoubleArray(oA.size) { min(oA[it], cA[it]) }, cA, bd.toArray(), bb.toArray())
}

/** pandas-style ewm(adjust=False): y0 = x0, y_t = alpha*x_t + (1-alpha)*y_{t-1}.  Values before `start` stay NaN. */
fun ewm(x: DoubleArray, alpha: Double, start: Int = 0): DoubleArray {
    val out = DoubleArray(x.size) { NAN }
    if (x.size <= start) return out
    out[start] = x[start]
    for (i in start + 1 until x.size) out[i] = alpha * x[i] + (1 - alpha) * out[i - 1]
    return out
}

fun ema(x: DoubleArray, span: Int): DoubleArray = ewm(x, 2.0 / (span + 1))

class SuperTrendOut(val dir: IntArray, val line: DoubleArray)

fun superTrend(h: DoubleArray, l: DoubleArray, c: DoubleArray, period: Int, mult: Double): SuperTrendOut {
    val n = c.size
    if (n == 0) return SuperTrendOut(IntArray(0), DoubleArray(0))
    val tr = DoubleArray(n); tr[0] = h[0] - l[0]
    for (i in 1 until n) tr[i] = max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
    val atr = ewm(tr, 1.0 / period)
    val ub = DoubleArray(n) { (h[it] + l[it]) / 2.0 + mult * atr[it] }
    val lb = DoubleArray(n) { (h[it] + l[it]) / 2.0 - mult * atr[it] }
    val fu = ub.copyOf(); val fl = lb.copyOf()
    val d = IntArray(n) { 1 }; val line = DoubleArray(n) { NAN }
    for (i in 1 until n) {
        fl[i] = if (c[i - 1] > fl[i - 1]) max(lb[i], fl[i - 1]) else lb[i]
        fu[i] = if (c[i - 1] < fu[i - 1]) min(ub[i], fu[i - 1]) else ub[i]
        d[i] = if (d[i - 1] == -1 && c[i] > fu[i - 1]) 1 else if (d[i - 1] == 1 && c[i] < fl[i - 1]) -1 else d[i - 1]
        line[i] = if (d[i] == 1) fl[i] else fu[i]
    }
    return SuperTrendOut(d, line)
}

class Ind(
    val emaF: DoubleArray, val emaS: DoubleArray, val demaF: DoubleArray, val demaS: DoubleArray,
    val tema20: DoubleArray, val tema30: DoubleArray, val tema40: DoubleArray,
    val rsi: DoubleArray, val arsi: DoubleArray, val dcUp: DoubleArray, val dcDn: DoubleArray,
    val stDir: IntArray, val stLine: DoubleArray
)

fun indicators(br: Bricks, st: Pair<Int, Double>): Ind {
    val n = br.n
    val e20 = ema(br.c, 20); val e40 = ema(br.c, 40)
    val d20 = DoubleArray(n); val d40 = DoubleArray(n)
    val ee20 = ema(e20, 20); val ee40 = ema(e40, 40)
    for (i in 0 until n) { d20[i] = 2 * e20[i] - ee20[i]; d40[i] = 2 * e40[i] - ee40[i] }
    fun tema(p: Int): DoubleArray { val a = ema(br.c, p); val b = ema(a, p); val c = ema(b, p); return DoubleArray(n) { 3 * a[it] - 3 * b[it] + c[it] } }
    // RSI(14) with Wilder smoothing; ARSI = EMA(9) of RSI
    val gain = DoubleArray(n); val loss = DoubleArray(n)
    for (i in 1 until n) { val dl = br.c[i] - br.c[i - 1]; gain[i] = max(dl, 0.0); loss[i] = max(-dl, 0.0) }
    val ag = ewm(gain, 1.0 / 14, 1); val al = ewm(loss, 1.0 / 14, 1)
    val rsi = DoubleArray(n) { i -> if (i == 0 || al[i] <= 0.0) 100.0 else 100.0 - 100.0 / (1.0 + ag[i] / al[i]) }
    val arsi = ema(rsi, 9)
    val dcUp = DoubleArray(n) { NAN }; val dcDn = DoubleArray(n) { NAN }
    for (i in 40 until n) {                                     // Donchian(40) of the PREVIOUS 40 bricks
        var hi = -Double.MAX_VALUE; var lo = Double.MAX_VALUE
        for (k in i - 40 until i) { hi = max(hi, br.h[k]); lo = min(lo, br.l[k]) }
        dcUp[i] = hi; dcDn[i] = lo
    }
    val s = superTrend(br.h, br.l, br.c, st.first, st.second)
    return Ind(e20, e40, d20, d40, tema(20), tema(30), tema(40), rsi, arsi, dcUp, dcDn, s.dir, s.line)
}

const val KIND_SWING = 1
const val KIND_ONE = 2
const val KIND_TWO = 3
val KIND_NAME = mapOf(1 to "SWING_BO", 2 to "ONE_BACK", 3 to "TWO_BACK")

class Scan(n: Int, val ind: Ind) {
    val swH = DoubleArray(n) { NAN }; val swL = DoubleArray(n) { NAN }
    val newSwH = BooleanArray(n); val newSwL = BooleanArray(n)
    val bullBO = BooleanArray(n); val bearBO = BooleanArray(n)
    val bullK = IntArray(n); val bearK = IntArray(n)
    val tgtU = DoubleArray(n) { NAN }; val tgtD = DoubleArray(n) { NAN }
    val multiB = BooleanArray(n); val multiS = BooleanArray(n)
    val multiTU = DoubleArray(n) { NAN }; val multiTD = DoubleArray(n) { NAN }
}

/** Swing / pattern scanner.  Mirrors the Pine logic: runs of bricks, swing highs/lows, breakouts, multi-swing. */
fun scanBricks(br: Bricks, tol: Double = 0.5, st: Pair<Int, Double> = Pair(40, 12.0)): Scan {
    val n = br.n
    val sc = Scan(n, indicators(br, st))
    var r0 = 0; var r1 = 0; var r2 = 0; var d0 = 0
    var swH = NAN; var swL = NAN; var hRun = NAN; var lRun = NAN
    val shI = IL(); val shP = DL(); val slI = IL(); val slP = DL()
    for (i in 0 until n) {
        val di = br.dir[i]
        if (di == d0) r0++ else { r2 = r1; r1 = r0; r0 = 1; d0 = di }
        if (i > 0) {
            if (di == -1 && br.dir[i - 1] == 1) { swH = br.h[i - 1]; hRun = r1 * br.box[i]; shI.add(i - 1); shP.add(swH); sc.newSwH[i] = true }
            else if (di == 1 && br.dir[i - 1] == -1) { swL = br.l[i - 1]; lRun = r1 * br.box[i]; slI.add(i - 1); slP.add(swL); sc.newSwL[i] = true }
        }
        sc.swH[i] = swH; sc.swL[i] = swL
        if (i == 0) continue
        val pc = br.c[i - 1]; val ci = br.c[i]; val bx = br.box[i]
        if (di == 1) {
            if (!swH.isNaN() && ci > swH + EPS && pc <= swH + EPS) {
                sc.bullBO[i] = true; sc.tgtU[i] = swH + hRun
                sc.bullK[i] = if (r0 == 3 && r1 == 2 && r2 >= 3) KIND_TWO else if (r0 == 2 && r1 == 1 && r2 >= 2) KIND_ONE else KIND_SWING
            }
            if (shP.n >= 2) {
                val p1 = shP.a[shP.n - 1]; val p2 = shP.a[shP.n - 2]; val i2 = shI.a[shI.n - 2]
                if (abs(p1 - p2) <= tol * bx) {
                    val lvl = max(p1, p2)
                    if (ci > lvl + EPS && pc <= lvl + EPS) {
                        var lo = Double.MAX_VALUE
                        for (k in i2..i) lo = min(lo, br.l[k])
                        val hg = rint((lvl - lo) / bx)
                        sc.multiB[i] = true; sc.multiTU[i] = if (hg >= 1) ci + hg * bx else NAN
                    }
                }
            }
        } else {
            if (!swL.isNaN() && ci < swL - EPS && pc >= swL - EPS) {
                sc.bearBO[i] = true; sc.tgtD[i] = swL - lRun
                sc.bearK[i] = if (r0 == 3 && r1 == 2 && r2 >= 3) KIND_TWO else if (r0 == 2 && r1 == 1 && r2 >= 2) KIND_ONE else KIND_SWING
            }
            if (slP.n >= 2) {
                val p1 = slP.a[slP.n - 1]; val p2 = slP.a[slP.n - 2]; val i2 = slI.a[slI.n - 2]
                if (abs(p1 - p2) <= tol * bx) {
                    val lvl = min(p1, p2)
                    if (ci < lvl - EPS && pc >= lvl - EPS) {
                        var hi = -Double.MAX_VALUE
                        for (k in i2..i) hi = max(hi, br.h[k])
                        val hg = rint((hi - lvl) / bx)
                        sc.multiS[i] = true; sc.multiTD[i] = if (hg >= 1) ci - hg * bx else NAN
                    }
                }
            }
        }
    }
    return sc
}

/** Candles -> Renko bricks -> scan arrays, plus the index helpers the simulator needs. */
class Chart(
    val bars: Bars, pct: Double, tick: Double = 0.05, path: String = "ohlc", reversal: Int = 2,
    st: Pair<Int, Double> = Pair(40, 12.0), fixedBox: Double = 0.0
) {
    val nb = bars.n
    val barMin = IntArray(nb) { bars.minute(it) }
    val barDay = IntArray(nb) { bars.dayNum(it) }
    val br: Bricks = if (nb > 0) buildRenko(bars, pct, tick, path, reversal, fixedBox) else Bricks(IntArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), IntArray(0), DoubleArray(0))
    val n = br.n
    val sc: Scan = scanBricks(br, 0.5, st)
    val bbar = br.bar
    val bcl = br.c
    val bdir = br.dir
    val bbox = br.box
    val brDay = IntArray(n) { barDay[bbar[it]] }
    val brMin = IntArray(n) { barMin[bbar[it]] }
    private val sqCache = HashMap<Int, Map<Int, Int>>()

    /** day number -> index of the first bar at/after the square-off time (or the day's last bar). */
    fun sqBars(sqMin: Int): Map<Int, Int> = sqCache.getOrPut(sqMin) {
        val firstAfter = HashMap<Int, Int>(); val last = HashMap<Int, Int>()
        for (i in 0 until nb) {
            last[barDay[i]] = i
            if (barMin[i] >= sqMin && !firstAfter.containsKey(barDay[i])) firstAfter[barDay[i]] = i
        }
        last.mapValues { (k, v) -> firstAfter[k] ?: v }
    }
}

// =====================================================================================================
// 4. SIMULATOR
// =====================================================================================================
class Entry(val i: Int, val side: Int, val stopRef: Double, val label: String = "", val target: Double = NAN)

data class Rules(
    val intraday: Boolean = true,
    val entryStart: Int = 9 * 60 + 20,
    val entryEnd: Int = 15 * 60,
    val sqTime: Int = 15 * 60 + 15,
    val trail: Boolean = true,
    val useTarget: Boolean = false,
    val fill: String = "next_open",             // next_open | brick_close
    val slippage: Double = 1.0,                 // price points per fill
    val slippagePct: Double = 0.0,              // extra slippage as % of price (stocks / ETFs)
    val hardStopPct: Double = 0.0,
    val stopHook: ((Chart, Int, Int, Double) -> Double)? = null,
    val extraExit: ((Chart, Int, Int, Entry) -> String?)? = null
)

class Trade(
    val strategy: String, val symbol: String, val side: String, val pattern: String,
    val entryT: Long, val exitT: Long, val entryPx: Double, val exitPx: Double, val qty: Int,
    val points: Double, val gross: Double, val costs: Double, val net: Double,
    val exitReason: String, val riskPts: Double, val r: Double
)

fun tradeCosts(profile: String, qty: Int, entryPx: Double, exitPx: Double, side: Int): Double {
    val c = COSTS.getValue(profile)
    val buyVal = (if (side == 1) entryPx else exitPx) * qty
    val sellVal = (if (side == 1) exitPx else entryPx) * qty
    val brok = c.brokerage * 2
    val exch = (buyVal + sellVal) * c.exch
    val sebi = (buyVal + sellVal) * c.sebi
    val stt = sellVal * c.sttSell + buyVal * c.sttBuy
    val stamp = buyVal * c.stampBuy
    val gst = GST * (brok + exch + sebi)
    return brok + exch + sebi + stt + stamp + gst
}

/** Walks the bricks after the entry and returns (exit brick index, reason). Exit is decided on a brick CLOSE. */
fun manage(ch: Chart, e: Entry, R: Rules): Pair<Int, String> {
    val sc = ch.sc; val side = e.side
    var stop = e.stopRef
    val entDay = ch.brDay[e.i]; val entClose = ch.bcl[e.i]
    for (j in e.i + 1 until ch.n) {
        if (R.intraday && (ch.brDay[j] != entDay || ch.brMin[j] >= R.sqTime)) return Pair(j, "EOD")
        val c = ch.bcl[j]
        R.stopHook?.let { stop = it(ch, j, side, stop) }
        if (R.trail) {
            if (side == 1 && sc.newSwL[j] && sc.swL[j] > stop && sc.swL[j] < c) stop = sc.swL[j]
            else if (side == -1 && sc.newSwH[j] && sc.swH[j] < stop && sc.swH[j] > c) stop = sc.swH[j]
        }
        if ((side == 1 && c < stop - EPS) || (side == -1 && c > stop + EPS)) return Pair(j, "SL")
        if (R.hardStopPct != 0.0 &&
            ((side == 1 && c <= entClose * (1 - R.hardStopPct / 100) + EPS) || (side == -1 && c >= entClose * (1 + R.hardStopPct / 100) - EPS))) return Pair(j, "HARD_SL")
        if (R.useTarget && !e.target.isNaN()) {
            if ((side == 1 && c >= e.target - EPS) || (side == -1 && c <= e.target + EPS)) return Pair(j, "TARGET")
        }
        val why = R.extraExit?.invoke(ch, j, side, e)
        if (why != null) return Pair(j, why)
    }
    return Pair(ch.n - 1, if (R.intraday) "EOD" else "END")
}

/** Walk the entries in order, one position at a time.  Fills at the NEXT bar open (or the brick close). */
fun simulate(
    ch: Chart, entries: List<Entry>, R: Rules, tag: String, symbol: String, qtyFn: (Double) -> Int, profile: String,
    minTime: Long? = null, maxTrades: Int = 0, cur0: Int = 0
): MutableList<Trade> {
    val trades = ArrayList<Trade>()
    if (ch.n == 0) return trades
    val sq = if (R.intraday) ch.sqBars(R.sqTime) else emptyMap()
    var cur = cur0
    val nextOpen = R.fill == "next_open"
    for (e in entries.sortedBy { it.i }) {
        if (e.i < cur) continue
        val bk = ch.bbar[e.i]
        if (minTime != null && ch.bars.t[bk] < minTime) continue
        if (R.intraday && !(R.entryStart <= ch.barMin[bk] && ch.barMin[bk] < R.entryEnd)) continue
        var eb = if (nextOpen) bk + 1 else bk
        if (eb >= ch.nb || (R.intraday && ch.barDay[eb] != ch.barDay[bk])) continue
        val (xi, why) = manage(ch, e, R)
        var xb: Int; var xPx: Double
        if (why == "END") { xb = ch.nb - 1; xPx = ch.bars.c[ch.nb - 1] }
        else if (why == "EOD" && R.intraday) {
            xb = sq[ch.barDay[bk]] ?: (ch.nb - 1)
            xPx = if (ch.barMin[xb] >= R.sqTime) ch.bars.o[xb] else ch.bars.c[xb]
        } else {
            xb = ch.bbar[xi] + (if (nextOpen) 1 else 0)
            if (R.intraday) xb = min(xb, sq[ch.barDay[bk]] ?: (ch.nb - 1))
            xb = min(xb, ch.nb - 1)
            xPx = if (nextOpen) ch.bars.o[xb] else ch.bcl[xi]
        }
        var ePx = if (nextOpen) ch.bars.o[eb] else ch.bcl[e.i]
        if (nextOpen && xb <= eb && why != "EOD" && why != "END") {      // same-minute whipsaw -> use brick prices
            ePx = ch.bcl[e.i]; xPx = ch.bcl[xi]; eb = bk; xb = ch.bbar[xi]
        }
        val s = R.slippage + R.slippagePct / 100.0 * ePx
        val eFill = ePx + e.side * s
        val xFill = xPx - e.side * s
        val qty = qtyFn(eFill)
        if (qty <= 0) continue
        val pts = e.side * (xFill - eFill)
        val gross = pts * qty
        val cost = tradeCosts(profile, qty, eFill, xFill, e.side)
        val stopLevel = e.stopRef - e.side * ch.bbox[e.i]
        val risk = abs(eFill - stopLevel)
        trades.add(Trade(tag, symbol, if (e.side == 1) "LONG" else "SHORT", e.label, ch.bars.t[eb], ch.bars.t[max(xb, eb)],
            r2(eFill), r2(xFill), qty, r2(pts), r2(gross), r2(cost), r2(gross - cost), why, r2(risk), if (risk > 1e-6) r2(pts / risk) else NAN))
        cur = xi
        if (maxTrades > 0 && trades.size >= maxTrades) break
    }
    return trades
}

// =====================================================================================================
// 5. STRATEGY ENTRY LOGIC
// =====================================================================================================
fun patternOk(kind: Int, patterns: Set<String>): Boolean =
    "swing" in patterns || (when (kind) { KIND_ONE -> "oneback"; KIND_TWO -> "twoback"; else -> "" } in patterns)

fun entriesDma(ch: Chart, patterns: Set<String>, useTarget: Boolean, warm: Int = 40): List<Entry> {
    val sc = ch.sc; val out = ArrayList<Entry>()
    for (i in 0 until ch.n) {
        if (i < warm || !(sc.bullBO[i] || sc.bearBO[i])) continue
        if (sc.bullBO[i] && sc.ind.emaF[i] > sc.ind.emaS[i] && patternOk(sc.bullK[i], patterns) && !sc.swL[i].isNaN() && sc.swL[i] < ch.bcl[i])
            out.add(Entry(i, 1, sc.swL[i], KIND_NAME.getValue(sc.bullK[i]), if (useTarget) sc.tgtU[i] else NAN))
        else if (sc.bearBO[i] && sc.ind.emaF[i] < sc.ind.emaS[i] && patternOk(sc.bearK[i], patterns) && !sc.swH[i].isNaN() && sc.swH[i] > ch.bcl[i])
            out.add(Entry(i, -1, sc.swH[i], KIND_NAME.getValue(sc.bearK[i]), if (useTarget) sc.tgtD[i] else NAN))
    }
    return out
}

fun entriesMulti(ch: Chart, useTarget: Boolean, warm: Int = 40): List<Entry> {
    val sc = ch.sc; val out = ArrayList<Entry>()
    for (i in 0 until ch.n) {
        if (i < warm || !(sc.multiB[i] || sc.multiS[i])) continue
        if (sc.multiB[i] && sc.ind.emaF[i] > sc.ind.emaS[i] && !sc.swL[i].isNaN() && sc.swL[i] < ch.bcl[i])
            out.add(Entry(i, 1, sc.swL[i], "MULTI_SWING", if (useTarget) sc.multiTU[i] else NAN))
        else if (sc.multiS[i] && sc.ind.emaF[i] < sc.ind.emaS[i] && !sc.swH[i].isNaN() && sc.swH[i] > ch.bcl[i])
            out.add(Entry(i, -1, sc.swH[i], "MULTI_SWING", if (useTarget) sc.multiTD[i] else NAN))
    }
    return out
}

val crossExitHook: (Chart, Int, Int, Entry) -> String? = { ch, j, side, _ ->
    val ind = ch.sc.ind
    if (side == 1 && ind.emaF[j] < ind.emaS[j]) "EMA_CROSS" else if (side == -1 && ind.emaF[j] > ind.emaS[j]) "EMA_CROSS" else null
}

/** S3 / S6: enter on the 2nd brick after a SuperTrend flip; stop = a counter brick closing beyond the entry level; re-enter
 *  while the trend persists when a brick closes beyond the previous entry level again. */
fun runSuperTrend(ch: Chart, R: Rules, tag: String, symbol: String, qtyFn: (Double) -> Int, profile: String, period: Int): List<Trade> {
    val sd = ch.sc.ind.stDir
    val trades = ArrayList<Trade>()
    var i = period + 2; var lastLevel = NAN; var lastDir = 0
    val R2 = R.copy(trail = false, extraExit = { c, j, s, _ -> if (c.sc.ind.stDir[j] == -s) "ST_FLIP" else null })
    while (i < ch.n) {
        var ent: Entry? = null
        for (j in i until ch.n) {
            val d = sd[j]
            if (ch.bdir[j] != d) continue
            val fresh = sd[j - 1] == d && sd[j - 2] == -d
            val again = lastDir == d && !lastLevel.isNaN() && (ch.bcl[j] - lastLevel) * d > EPS
            if (fresh || again) { ent = Entry(j, d, ch.bcl[j], if (fresh) "ST_2ND_BRICK" else "ST_REENTRY"); break }
        }
        if (ent == null) break
        val tr = simulate(ch, listOf(ent), R2, tag, symbol, qtyFn, profile, cur0 = i, maxTrades = 1)
        if (tr.isEmpty()) { i = ent.i + 1; continue }
        trades.addAll(tr)
        val (xi, why) = manage(ch, ent, R2)
        lastLevel = ch.bcl[ent.i]; lastDir = if (why == "ST_FLIP") 0 else ent.side
        i = max(xi, ent.i + 1)
    }
    return trades
}

fun entriesS9(ch: Chart): List<Entry> {
    val sc = ch.sc; val ind = sc.ind; val out = ArrayList<Entry>()
    for (i in 41 until ch.n) {
        if (ch.bdir[i] == 1 && ch.bdir[i - 1] == -1 && ind.tema20[i] > ind.tema30[i] && ind.tema30[i] > ind.tema40[i] && ind.rsi[i] > 50 &&
            !sc.swL[i].isNaN() && sc.swL[i] < ch.bcl[i]) out.add(Entry(i, 1, sc.swL[i], "BRICK_REVERSAL"))
    }
    return out
}

fun entriesS13(ch: Chart): List<Entry> {
    val sc = ch.sc; val ind = sc.ind; val out = ArrayList<Entry>()
    for (i in 42 until ch.n) {
        if (!(ind.emaF[i] > ind.emaS[i] && ind.stDir[i] == 1)) continue
        if (sc.bullBO[i]) out.add(Entry(i, 1, ind.stLine[i], "SWING_BO+ST"))
        else if (ind.stDir[i - 1] == -1 && ch.bdir[i] == 1) out.add(Entry(i, 1, ind.stLine[i], "ST_BREAKOUT"))
    }
    return out
}

val s13StopHook: (Chart, Int, Int, Double) -> Double = { ch, j, _, stop ->
    val sc = ch.sc; val ind = sc.ind
    if (ind.emaF[j] >= ind.emaS[j]) { if (ind.stDir[j] == 1 && ind.stLine[j] > stop) ind.stLine[j] else stop }       // stop follows the SuperTrend line
    else if (sc.newSwL[j] && sc.swL[j] > stop && sc.swL[j] < ch.bcl[j]) sc.swL[j] else stop                          // DMA bearish -> trail swing lows
}

// =====================================================================================================
// 6. STRATEGY RUNNERS
// =====================================================================================================
class Args {
    var strategies = "S1,S7"
    var start: LocalDate = LocalDate.now().minusDays(365)
    var end: LocalDate = LocalDate.now()
    var spotCsv = HashMap<String, String>()
    var lots = 1
    var lotSize = 0
    var slippage = 1.0
    var fill = "next_open"
    var patterns = "swing"
    var useTarget = false
    var noTrail = false
    var noCrossExit = false
    var entryStart = "09:20"
    var entryEnd = "15:00"
    var squareoff = "15:15"
    var renkoPath = "ohlc"
    var reversal = 2
    var boxPct = 0.0
    var boxFor = HashSet<String>()
    var symbols = ""
    var capitalPerTrade = 100000.0
    var optBoxPct = 3.0
    var optAtmTime = "09:20"
    var minDte = 0
    var armMinutes = 60
    var warmupDays = 4
    var arsiThr = 50.0
    var optMaxDays = 0
    var cacheDir = "fyers_cache_kt"
    var outDir = "renko_results"
    var manualLogin = false
    var forceLogin = false
    var probe = false
    var selftest = false
    var debug = false
}

class Ctx(val a: Args, val fd: FyersData, val start: LocalDate, val end: LocalDate) {
    val spot = HashMap<String, Bars>()
    val opts = HashMap<String, OptionBook>()
    val charts = HashMap<String, Chart?>()

    fun rules(intraday: Boolean, base: Rules = Rules()): Rules = base.copy(
        intraday = intraday, entryStart = hhmmToMin(a.entryStart), entryEnd = hhmmToMin(a.entryEnd), sqTime = hhmmToMin(a.squareoff),
        trail = base.trail && !a.noTrail, useTarget = a.useTarget, fill = a.fill, slippage = if (base.slippage == 1.0) a.slippage else base.slippage
    )

    fun lot(und: String): Int = (if (a.lotSize > 0) a.lotSize else INSTR.getValue(und).lot) * a.lots
}

fun spotBars(ctx: Ctx, und: String, res: String, warmDays: Int): Bars {
    val key = "$und:$res"
    val df = ctx.spot.getOrPut(key) {
        val csvp = if (res == "1") ctx.a.spotCsv[und] else null
        if (csvp != null) {
            loadCsvBars(csvp).also { log("[data] $und: ${it.n} 1-min bars from $csvp") }
        } else {
            val sym = INSTR.getValue(und).spot
            val loadWarm = max(warmDays, if (res == "1") 90 else 500)          // load once with the largest warm-up any strategy needs
            ctx.fd.candles(sym, res, ctx.start.minusDays(loadWarm.toLong()), ctx.end)
                .also { log("[data] $und $res: ${it.n} bars from FYERS ($sym), API calls so far: ${ctx.fd.calls}") }
        }
    }
    val lo = localEpoch(ctx.start.minusDays(warmDays.toLong())); val hi = localEpoch(ctx.end.plusDays(1))
    return df.filter { df.t[it] in lo until hi }
}

fun clipTrades(trades: List<Trade>, ctx: Ctx): List<Trade> {
    val lo = localEpoch(ctx.start); val hi = localEpoch(ctx.end.plusDays(1))
    return trades.filter { it.entryT in lo until hi }
}

fun runSpotFamily(tag: String, p: Preset, ctx: Ctx): List<Trade> {
    val a = ctx.a; val und = p.und!!
    val bars = spotBars(ctx, und, p.res, p.warm)
    if (bars.n < 500) { log("[$tag] not enough data (${bars.n} bars)"); return emptyList() }
    val pct = if (a.boxPct > 0 && tag in a.boxFor) a.boxPct else p.pct
    val ch = Chart(bars, pct, path = a.renkoPath, reversal = a.reversal, st = p.st)
    log("[$tag] ${bars.n} bars -> ${ch.n} bricks (box $pct%)")
    val R = ctx.rules(p.intraday, Rules(extraExit = if (p.crossExit && !a.noCrossExit) crossExitHook else null))
    val lot = ctx.lot(und)
    val qty = { _: Double -> lot }
    val tr = if (p.kind == "st") runSuperTrend(ch, R, tag, INSTR.getValue(und).spot, qty, "futures", p.st.first)
    else {
        val ents = if (p.kind == "multi") entriesMulti(ch, a.useTarget) else entriesDma(ch, a.patterns.split(",").toSet(), a.useTarget)
        simulate(ch, ents, R, tag, INSTR.getValue(und).spot, qty, "futures")
    }
    return clipTrades(tr, ctx)
}

fun runDailySymbols(tag: String, p: Preset, ctx: Ctx): List<Trade> {
    val a = ctx.a; val out = ArrayList<Trade>()
    val syms = a.symbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { if (tag == "S13") listOf("NSE:NIFTYBEES-EQ") else emptyList() }
    if (syms.isEmpty()) { log("[$tag] pass --symbols NSE:RELIANCE-EQ,NSE:TCS-EQ ..."); return out }
    for (sym in syms) {
        val df = ctx.fd.candles(sym, "1D", ctx.start.minusDays(p.warm.toLong()), ctx.end)
        if (df.n < 120) { log("[$tag] $sym: only ${df.n} daily bars, skipped"); continue }
        val ch = Chart(df, p.pct, path = a.renkoPath, reversal = a.reversal, st = p.st)
        val R: Rules; val ents: List<Entry>
        if (tag == "S9") { R = ctx.rules(false, Rules(hardStopPct = 2.0, slippage = 0.0, slippagePct = 0.05)); ents = entriesS9(ch) }
        else { R = ctx.rules(false, Rules(stopHook = s13StopHook, trail = false, slippage = 0.0, slippagePct = 0.05)); ents = entriesS13(ch) }
        val cap = a.capitalPerTrade
        out.addAll(simulate(ch, ents, R, tag, sym, { px -> (cap / px).toInt() }, "equity"))
        log("[$tag] $sym: ${ch.n} bricks, ${ents.size} entry signals")
    }
    return clipTrades(out, ctx)
}

fun tradingDays(ctx: Ctx, und: String): List<LocalDate> {
    val b = spotBars(ctx, und, "1", 0)
    return (0 until b.n).map { b.dayNum(it) }.distinct().map { LocalDate.ofEpochDay(it.toLong()) }
}

fun optionChart(ctx: Ctx, book: OptionBook, sym: String, day: LocalDate): Chart? = ctx.charts.getOrPut("$sym|$day") {
    val a = ctx.a
    val df = book.candles(sym, day.minusDays(a.warmupDays.toLong()), day)
    val ok = df.n >= 60 && (0 until df.n).any { df.dayNum(it).toLong() == day.toEpochDay() }
    if (ok) Chart(df, a.optBoxPct, path = a.renkoPath, reversal = a.reversal) else null
}

/** S11 / S12: each day pick the ATM CE and PE of the nearest expiry at --opt-atm-time, build a 3% Renko chart of each option's
 *  own price and trade it.  S11 buys on Two-Back bullish + ARSI>thr; S12 sells on a swing breakdown + ARSI<thr. */
fun runOptionDaily(tag: String, p: Preset, ctx: Ctx): List<Trade> {
    val a = ctx.a; val und = p.und!!
    val book = ctx.opts.getOrPut(und) { OptionBook(ctx.fd, und, ctx.start, ctx.end) }
    val spot = spotBars(ctx, und, "1", 0)
    var days = tradingDays(ctx, und)
    if (a.optMaxDays > 0) days = days.takeLast(a.optMaxDays)
    val out = ArrayList<Trade>(); var nOk = 0
    val atmMin = hhmmToMin(a.optAtmTime)
    val lastAtm = HashMap<Int, Int>()                                  // day number -> last bar index at/before the ATM time
    for (i in 0 until spot.n) if (spot.minute(i) <= atmMin) lastAtm[spot.dayNum(i)] = i
    log("[$tag] ${days.size} days to test (first run downloads ~2 option contracts/day; cached afterwards)")
    val lot = ctx.lot(und); val step = INSTR.getValue(und).step
    for ((k, day) in days.withIndex()) {
        val bi = lastAtm[day.toEpochDay().toInt()] ?: continue
        val exp = book.nearestExpiry(day, a.minDte)
        if (exp == null) { book.skipped++; continue }
        val strike = (Math.round(spot.c[bi] / step) * step).toInt()
        for (cp in listOf("CE", "PE")) {
            val sym = book.contract(exp, strike, cp)
            val ch = optionChart(ctx, book, sym, day)
            if (ch == null || ch.n < 45) { book.skipped++; continue }
            nOk++
            val sc = ch.sc; val ents = ArrayList<Entry>(); val lastDay = ch.barDay[ch.nb - 1]
            for (i in 41 until ch.n) {
                if (ch.brDay[i] != lastDay) continue
                if (tag == "S11" && sc.bullBO[i] && sc.bullK[i] == KIND_TWO && sc.ind.arsi[i] > a.arsiThr && sc.swL[i] < ch.bcl[i])
                    ents.add(Entry(i, 1, sc.swL[i], "TWO_BACK+ARSI", if (a.useTarget) sc.tgtU[i] else NAN))
                if (tag == "S12" && sc.bearBO[i] && sc.ind.arsi[i] < a.arsiThr && sc.swH[i] > ch.bcl[i])
                    ents.add(Entry(i, -1, sc.swH[i], "SWING_BREAKDOWN+ARSI", if (a.useTarget) sc.tgtD[i] else NAN))
            }
            out.addAll(simulate(ch, ents, ctx.rules(true), tag, sym, { lot }, "options"))
        }
        if ((k + 1) % 25 == 0) log("[$tag] ${k + 1}/${days.size} days, ${out.size} trades, API calls ${ctx.fd.calls}")
    }
    log("[$tag] option charts used: $nOk, skipped (no data/expiry): ${book.skipped}")
    return clipTrades(out, ctx)
}

/** S10: Donchian(40) breakout on the 0.075% Nifty brick chart = signal -> ATM option of that side -> enter on a bullish swing breakout
 *  of the option's 3% brick chart while DEMA 20 > DEMA 40 -> exit on swing failure / EOD. */
fun runS10(tag: String, p: Preset, ctx: Ctx): List<Trade> {
    val a = ctx.a; val und = p.und!!
    val book = ctx.opts.getOrPut(und) { OptionBook(ctx.fd, und, ctx.start, ctx.end) }
    val bars = spotBars(ctx, und, "1", p.warm)
    val sp = Chart(bars, p.pct, path = a.renkoPath, reversal = a.reversal)
    val ind = sp.sc.ind
    log("[$tag] spot: ${sp.n} bricks (${p.pct}% box)")
    val out = ArrayList<Trade>(); var lastExit = Long.MIN_VALUE
    val step = INSTR.getValue(und).step; val lot = ctx.lot(und)
    val R = ctx.rules(true)
    val sig = (41 until sp.n).filter { i ->
        (!ind.dcUp[i].isNaN() && sp.bcl[i] > ind.dcUp[i] && sp.bcl[i - 1] <= ind.dcUp[i - 1]) ||
            (!ind.dcDn[i].isNaN() && sp.bcl[i] < ind.dcDn[i] && sp.bcl[i - 1] >= ind.dcDn[i - 1])
    }
    log("[$tag] ${sig.size} Donchian signals")
    val lo = localEpoch(ctx.start); val hi = localEpoch(ctx.end.plusDays(1))
    for (i in sig) {
        val bk = sp.bbar[i]
        val tSig = bars.t[bk]
        if (tSig < lo || tSig >= hi || tSig <= lastExit) continue
        if (!(R.entryStart <= sp.barMin[bk] && sp.barMin[bk] < R.entryEnd)) continue
        val day = dayOf(tSig)
        val cp = if (!ind.dcUp[i].isNaN() && sp.bcl[i] > ind.dcUp[i]) "CE" else "PE"
        val exp = book.nearestExpiry(day, a.minDte) ?: continue
        val strike = (Math.round(bars.c[bk] / step) * step).toInt()
        val sym = book.contract(exp, strike, cp)
        val ch = optionChart(ctx, book, sym, day)
        if (ch == null || ch.n < 45) { book.skipped++; continue }
        val s = ch.sc; val deadline = tSig + a.armMinutes * 60L; val lastDay = ch.barDay[ch.nb - 1]
        val ents = (41 until ch.n).filter { k ->
            s.bullBO[k] && s.ind.demaF[k] > s.ind.demaS[k] && s.swL[k] < ch.bcl[k] && ch.brDay[k] == lastDay && ch.bars.t[ch.bbar[k]] <= deadline
        }.map { Entry(it, 1, s.swL[it], "OPT_SWING_BO+DEMA") }
        val tr = simulate(ch, ents, R, tag, sym, { lot }, "options", minTime = tSig + 60, maxTrades = 1)
        if (tr.isNotEmpty()) { out.addAll(tr); lastExit = tr[0].exitT }
    }
    log("[$tag] option charts skipped (no data): ${book.skipped}")
    return out
}

// =====================================================================================================
// 7. REPORT
// =====================================================================================================
class Perf(
    val trades: Int, val netPnl: Double, val winPct: Double, val avgWin: Double, val avgLoss: Double, val payoff: Double,
    val profitFactor: Double, val expectancy: Double, val expectancyR: Double, val maxDd: Double, val maxDdDays: Double,
    val maxConsecLoss: Int, val maxConsecWin: Int, val best: Double, val worst: Double, val costs: Double
) {
    fun rows(): List<Pair<String, String>> = listOf(
        "trades" to trades.toString(), "net_pnl" to fmtNum(netPnl), "win_pct" to fmtNum(winPct, 1), "avg_win" to fmtNum(avgWin),
        "avg_loss" to fmtNum(avgLoss), "payoff" to fmtNum(payoff, 2), "profit_factor" to fmtNum(profitFactor, 2), "expectancy" to fmtNum(expectancy),
        "expectancy_R" to fmtNum(expectancyR, 2), "max_dd" to fmtNum(maxDd), "max_dd_days" to fmtNum(maxDdDays), "max_consec_loss" to maxConsecLoss.toString(),
        "max_consec_win" to maxConsecWin.toString(), "best" to fmtNum(best), "worst" to fmtNum(worst), "costs" to fmtNum(costs)
    )
}

fun perf(all: List<Trade>): Perf? {
    if (all.isEmpty()) return null
    val t = all.sortedBy { it.exitT }
    val net = t.map { it.net }
    val wins = net.filter { it > 0 }; val losses = net.filter { it <= 0 }
    var eq = 0.0; var peakVal = 0.0; var peakT = t[0].exitT
    var maxDd = 0.0; var longest = 0L; var inDd = false
    for (x in t) {
        eq += x.net
        if (eq >= peakVal) {
            if (inDd) { longest = max(longest, x.exitT - peakT); inDd = false }
            peakVal = eq; peakT = x.exitT
        } else inDd = true
        maxDd = min(maxDd, eq - peakVal)
    }
    if (inDd) longest = max(longest, t.last().exitT - peakT)
    fun streak(cond: (Double) -> Boolean): Int { var best = 0; var cur = 0; for (x in net) { if (cond(x)) { cur++; best = max(best, cur) } else cur = 0 }; return best }
    val gp = wins.sum(); val gl = -losses.sum()
    val aw = if (wins.isNotEmpty()) wins.average() else 0.0
    val al = if (losses.isNotEmpty()) losses.average() else 0.0
    val rs = t.map { it.r }.filter { !it.isNaN() }
    return Perf(
        t.size, net.sum(), 100.0 * wins.size / t.size, aw, al, if (wins.isNotEmpty() && losses.isNotEmpty() && al != 0.0) aw / -al else NAN,
        if (gl > 0) gp / gl else Double.POSITIVE_INFINITY, net.average(), if (rs.isNotEmpty()) rs.average() else NAN, maxDd,
        Math.round(longest / 86400.0).toDouble(), streak { it <= 0 }, streak { it > 0 }, net.maxOrNull()!!, net.minOrNull()!!, t.sumOf { it.costs }
    )
}

fun tradesCsv(trades: List<Trade>): String = buildString {
    append("strategy,symbol,side,pattern,entry_time,exit_time,entry_px,exit_px,qty,points,gross,costs,net,exit_reason,risk_pts,R\n")
    for (x in trades) append("${x.strategy},${x.symbol},${x.side},${x.pattern},${fmtT(x.entryT)},${fmtT(x.exitT)},${x.entryPx},${x.exitPx},${x.qty},${x.points},${x.gross},${x.costs},${x.net},${x.exitReason},${x.riskPts},${x.r}\n")
}

fun report(all: Map<String, List<Trade>>, outDir: File) {
    outDir.mkdirs()
    val summary = ArrayList<Pair<String, Perf>>()
    for ((tag, tr) in all) {
        if (tr.isEmpty()) { log("\n[$tag] no trades"); continue }
        File(outDir, "trades_$tag.csv").writeText(tradesCsv(tr))
        val sorted = tr.sortedBy { it.exitT }
        var eq = 0.0
        File(outDir, "equity_$tag.csv").writeText("exit_time,cum_net\n" + sorted.joinToString("") { eq += it.net; "${fmtT(it.exitT)},${fmtNum(eq, 2)}\n" })
        val m = perf(tr)!!
        summary.add(tag to m)
        log("\n=== $tag: ${PRESETS[tag]?.desc ?: ""}")
        m.rows().forEach { log("  " + it.first.padEnd(16) + it.second) }
        log("  per year:\n      year  count        sum  win%")
        tr.groupBy { dayOf(it.exitT).year }.toSortedMap().forEach { (y, l) ->
            log("      " + y.toString().padEnd(6) + l.size.toString().padStart(5) + fmtNum(l.sumOf { it.net }).padStart(11) + fmtNum(100.0 * l.count { it.net > 0 } / l.size, 1).padStart(7))
        }
        log("  by exit reason:\n      reason         count        sum")
        tr.groupBy { it.exitReason }.toSortedMap().forEach { (k, l) -> log("      " + k.padEnd(14) + l.size.toString().padStart(5) + fmtNum(l.sumOf { it.net }).padStart(11)) }
    }
    if (summary.isNotEmpty()) {
        val head = listOf("strategy") + summary[0].second.rows().map { it.first }
        val lines = summary.map { (tag, m) -> listOf(tag) + m.rows().map { it.second } }
        File(outDir, "summary.csv").writeText(head.joinToString(",") + "\n" + lines.joinToString("\n") { it.joinToString(",") } + "\n")
        val w = head.map { h -> max(h.length, lines.maxOf { l -> l[head.indexOf(h)].length }) + 1 }
        log("\n=== SUMMARY\n" + head.indices.joinToString("") { head[it].padStart(w[it]) } + "\n" +
            lines.joinToString("\n") { l -> l.indices.joinToString("") { l[it].padStart(w[it]) } })
        log("\nFiles written to ${outDir.absolutePath}")
    }
}

// =====================================================================================================
// 8. MAIN
// =====================================================================================================
val BOOL_FLAGS = setOf("--use-target", "--no-trail", "--no-cross-exit", "--manual-login", "--force-login", "--probe", "--selftest", "--debug", "--no-plot")

fun parseArgs(argv: Array<String>): Args {
    val a = Args()
    var i = 0
    fun need(name: String): String { if (i + 1 >= argv.size) throw IllegalArgumentException("$name needs a value"); return argv[++i] }
    while (i < argv.size) {
        val k = argv[i]
        when (k) {
            "--strategies" -> a.strategies = need(k)
            "--start" -> a.start = LocalDate.parse(need(k))
            "--end" -> a.end = LocalDate.parse(need(k))
            "--spot-csv" -> { while (i + 1 < argv.size && !argv[i + 1].startsWith("--")) { val kv = argv[++i].split("=", limit = 2); a.spotCsv[kv[0].uppercase()] = kv[1] } }
            "--lots" -> a.lots = need(k).toInt()
            "--lot-size" -> a.lotSize = need(k).toInt()
            "--slippage" -> a.slippage = need(k).toDouble()
            "--fill" -> a.fill = need(k)
            "--patterns" -> a.patterns = need(k)
            "--use-target" -> a.useTarget = true
            "--no-trail" -> a.noTrail = true
            "--no-cross-exit" -> a.noCrossExit = true
            "--entry-start" -> a.entryStart = need(k)
            "--entry-end" -> a.entryEnd = need(k)
            "--squareoff" -> a.squareoff = need(k)
            "--renko-path" -> a.renkoPath = need(k)
            "--reversal" -> a.reversal = need(k).toInt()
            "--box-pct" -> a.boxPct = need(k).toDouble()
            "--box-for" -> a.boxFor = need(k).split(",").map { it.trim().uppercase() }.toHashSet()
            "--symbols" -> a.symbols = need(k)
            "--capital-per-trade" -> a.capitalPerTrade = need(k).toDouble()
            "--opt-box-pct" -> a.optBoxPct = need(k).toDouble()
            "--opt-atm-time" -> a.optAtmTime = need(k)
            "--min-dte" -> a.minDte = need(k).toInt()
            "--arm-minutes" -> a.armMinutes = need(k).toInt()
            "--warmup-days" -> a.warmupDays = need(k).toInt()
            "--arsi-thr" -> a.arsiThr = need(k).toDouble()
            "--opt-max-days" -> a.optMaxDays = need(k).toInt()
            "--cache-dir" -> a.cacheDir = need(k)
            "--out-dir" -> a.outDir = need(k)
            "--manual-login" -> a.manualLogin = true
            "--force-login" -> a.forceLogin = true
            "--probe" -> a.probe = true
            "--selftest" -> a.selftest = true
            "--debug" -> a.debug = true
            "--no-plot" -> { /* accepted for compatibility with the Python version; equity curves are always written as CSV */ }
            "-h", "--help" -> { println(HELP); exitProcess(0) }
            else -> throw IllegalArgumentException("unknown option $k (use --help)")
        }
        i++
    }
    return a
}

const val HELP = """Renko backtester (Kotlin, single file).  Options (defaults in brackets):
  --strategies S1,S7        any of S1,S2,S3,S4,S5,S6,S7,S7B,S9,S10,S11,S12,S13   [S1,S7]
  --start / --end           yyyy-MM-dd                                            [last 365 days]
  --spot-csv NIFTY=f.csv    use your own 1-min file (cols: datetime,open,high,low,close[,volume]) instead of the API
  --lots N  --lot-size N    lots per trade [1]; lot size override [NIFTY 65, BANKNIFTY 30]
  --slippage P              points per fill [1]       --fill next_open|brick_close [next_open]
  --patterns swing,oneback,twoback   (S1/S2/S4/S5) [swing = every breakout]
  --use-target  --no-trail  --no-cross-exit
  --entry-start 09:20  --entry-end 15:00  --squareoff 15:15
  --renko-path ohlc|close  --reversal 1|2  --box-pct X --box-for S1,S4
  --symbols NSE:RELIANCE-EQ,...   (S9 / S13)   --capital-per-trade 100000
  --opt-box-pct 3  --opt-atm-time 09:20  --min-dte 0  --arm-minutes 60  --warmup-days 4  --arsi-thr 50  --opt-max-days N
  --cache-dir fyers_cache_kt  --out-dir renko_results
  --manual-login  --force-login  --probe  --selftest  --debug"""

fun makeAuth(a: Args): FyersAuth {
    loadDotenv()
    val app = getCred("FYERS_APP_ID", "FYERS APP ID (e.g. ABCD1234-100)")
    val sec = getCred("FYERS_SECRET_KEY", "FYERS secret key", secret = true)
    val red = getCred("FYERS_REDIRECT_URI", "Redirect URI registered in your FYERS app")
    val pin = getCred("FYERS_PIN", "FYERS PIN (optional, Enter to skip)", secret = true, required = false)
    if (app.isEmpty() || sec.isEmpty() || red.isEmpty()) throw AuthError("FYERS_APP_ID, FYERS_SECRET_KEY and FYERS_REDIRECT_URI are required")
    return FyersAuth(app, sec, red, pin, manual = a.manualLogin)
}

/** One call per endpoint; prints raw JSON so you can confirm the shapes this program expects. */
fun probe(fd: FyersData) {
    val today = LocalDate.now(); val u = INSTR.getValue("NIFTY")
    log("[probe] token OK: " + (fd.auth?.getToken(false)?.isNotEmpty() == true))
    var j = fd.get("/history", linkedMapOf("symbol" to u.spot, "resolution" to "1", "date_format" to "1", "range_from" to today.minusDays(5).toString(), "range_to" to today.toString(), "cont_flag" to "1"))
    log("[probe] history: " + j.toString().take(400))
    j = fd.get("/history/fno/expired/expiry-dates", linkedMapOf("symbol" to u.expiredUnd, "range_from" to today.minusDays(60).toString(), "range_to" to today.toString(), "date_format" to "1"))
    log("[probe] expiry-dates: " + j.toString().take(600))
    val ds = parseDates(j)
    log("[probe] parsed expiries: " + ds.takeLast(5))
    val e = ds.filter { it < today }.lastOrNull() ?: return
    j = fd.get("/history/fno/expired/underlying-symbols", linkedMapOf("symbol" to u.expiredUnd, "expiry_date" to e.toString()))
    log("[probe] underlying-symbols: " + j.toString().take(600))
    val syms = fd.expiredContracts(u.expiredUnd, e)
    log("[probe] ${syms.size} option symbols parsed, sample: ${syms.take(4)}")
    if (syms.isNotEmpty()) {
        j = fd.get("/history/fno/expired/historical-data", linkedMapOf("symbol" to syms[syms.size / 2], "resolution" to "1", "date_format" to "1", "range_from" to e.minusDays(3).toString(), "range_to" to e.toString(), "include_oi" to "1"))
        log("[probe] historical-data: " + j.toString().take(500))
    }
}

fun runStrategies(ctx: Ctx, tags: List<String>): Map<String, List<Trade>> {
    val results = LinkedHashMap<String, List<Trade>>()
    for (tag in tags) {
        val p = PRESETS[tag]
        if (p == null) { log("unknown strategy $tag; choose from ${PRESETS.keys}"); continue }
        log("\n##### $tag: ${p.desc}")
        results[tag] = when (p.kind) {
            "s9", "s13" -> runDailySymbols(tag, p, ctx)
            "s10" -> runS10(tag, p, ctx)
            "s11", "s12" -> runOptionDaily(tag, p, ctx)
            else -> runSpotFamily(tag, p, ctx)
        }
    }
    return results
}

fun realMain(argv: Array<String>): Int {
    val a = parseArgs(argv)
    if (a.selftest) return selftest()
    loadDotenv()
    val tags = a.strategies.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val needApi = a.probe || tags.any { t -> PRESETS[t]?.let { it.kind in setOf("s9", "s10", "s11", "s12", "s13") || (it.und == null || !a.spotCsv.containsKey(it.und)) } ?: false }
    val auth = if (needApi) makeAuth(a) else null
    val fd = FyersData(auth, a.cacheDir, a.debug)
    if (auth != null && (a.forceLogin || a.probe)) auth.getToken(a.forceLogin)
    if (a.probe) { probe(fd); return 0 }
    val ctx = Ctx(a, fd, a.start, a.end)
    report(runStrategies(ctx, tags), File(a.outDir))
    log("\nFYERS API calls made: ${fd.calls}")
    return 0
}

fun main(args: Array<String>) {
    val code = try { realMain(args) } catch (e: AuthError) { System.err.println("AUTH ERROR: ${e.message}"); 2 }
    catch (e: FyersError) { System.err.println("FYERS ERROR: ${e.message}"); 3 }
    catch (e: IllegalArgumentException) { System.err.println("ARGUMENT ERROR: ${e.message}"); 1 }
    exitProcess(code)
}

// =====================================================================================================
// 9. SELF-TEST (synthetic data + mocked FYERS replies; no credentials, no network)
// =====================================================================================================
private fun normCdf(x: Double): Double {                       // Abramowitz-Stegun 7.1.26
    val z = abs(x) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * z)
    val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
    return 0.5 * (1 + sign(x) * y)
}

private fun bs(s: Double, k: Double, t0: Double, sigma: Double, cp: String): Double {
    val t = max(t0, 1e-6)
    val d1 = (ln(s / k) + 0.5 * sigma * sigma * t) / (sigma * sqrt(t)); val d2 = d1 - sigma * sqrt(t)
    return if (cp == "CE") s * normCdf(d1) - k * normCdf(d2) else k * normCdf(-d2) - s * normCdf(-d1)
}

/** Stands in for the FYERS data API: serves a synthetic NIFTY market, expiry list, contract list and Black-Scholes option candles. */
class MockFyers(val spot: Bars, val daily: Bars, val expiries: List<LocalDate>, cacheDir: String) : FyersData(null, cacheDir, false) {
    private fun candlesJson(b: Bars, from: Int, to: Int, scale: Double): Map<String, Any?> {
        if (to <= from) return mapOf("s" to "no_data", "candles" to emptyList<Any>())
        return mapOf("s" to "ok", "candles" to (from until to).map { listOf(b.t[it] - IST_SHIFT, b.o[it] * scale, b.h[it] * scale, b.l[it] * scale, b.c[it] * scale, 0.0) })
    }

    override fun get(path: String, params: Map<String, String>): Map<String, Any?> {
        val a = localEpoch(LocalDate.parse(params["range_from"] ?: "2000-01-01"))
        val b = localEpoch(LocalDate.parse(params["range_to"] ?: "2100-01-01").plusDays(1))
        fun range(x: Bars): Pair<Int, Int> = Pair((0 until x.n).firstOrNull { x.t[it] >= a } ?: x.n, (0 until x.n).firstOrNull { x.t[it] >= b } ?: x.n)
        when {
            path == "/history" -> {
                val sym = params.getValue("symbol"); val daily1 = params["resolution"] == "1D"
                val src = if (daily1) daily else spot
                val (f, t) = range(src)
                val scale = when { sym.contains("BEES") -> 0.01; sym.contains("RELIANCE") -> 0.05; sym.startsWith("NSE:NIFTYBANK") -> 2.1; else -> 1.0 }
                return candlesJson(src, f, t, scale)
            }
            path.endsWith("expiry-dates") -> return mapOf("s" to "ok", "data" to mapOf("expiryData" to expiries.map {
                mapOf("date" to String.format("%02d-%02d-%04d", it.dayOfMonth, it.monthValue, it.year), "expiry" to (localEpoch(it) + 15 * 3600 + 1800 - IST_SHIFT).toString())
            }))
            path.endsWith("underlying-symbols") -> {
                val e = LocalDate.parse(params.getValue("expiry_date"))
                return mapOf("s" to "ok", "data" to (21000..25000 step 50).flatMap { k -> listOf("CE", "PE").map { cp -> mapOf("symbol" to buildOptionSymbol("NSE:NIFTY", e, k, cp, false)) } })
            }
            path.endsWith("historical-data") -> {
                val m = Regex("^NSE:NIFTY(\\d{2})([1-9OND])(\\d{2})(\\d+)(CE|PE)$").find(params.getValue("symbol")) ?: return mapOf("s" to "no_data", "candles" to emptyList<Any>())
                val mm = mapOf("O" to 10, "N" to 11, "D" to 12)[m.groupValues[2]] ?: m.groupValues[2].toInt()
                val e = LocalDate.of(2000 + m.groupValues[1].toInt(), mm, m.groupValues[3].toInt())
                val (f, t) = range(spot)
                if (e !in expiries || t <= f) return mapOf("s" to "no_data", "candles" to emptyList<Any>())
                val k = m.groupValues[4].toDouble(); val cp = m.groupValues[5]
                val endT = localEpoch(e) + 15 * 3600 + 1800
                return mapOf("s" to "ok", "candles" to (f until t).map { i ->
                    val tt = max((endT - spot.t[i]) / (365.0 * 86400), 1e-5)
                    val p = doubleArrayOf(spot.o[i], spot.h[i], spot.l[i], spot.c[i]).map { bs(it, k, tt, 0.14, cp) }
                    listOf(spot.t[i] - IST_SHIFT, p[0], p.maxOrNull()!!, p.minOrNull()!!, p[3], 0.0)
                })
            }
        }
        return mapOf("s" to "error", "message" to "unmocked $path")
    }
}

fun selftest(): Int {
    var ok = true
    fun check(name: String, cond: Boolean, extra: String = "") { ok = ok && cond; log("  [${if (cond) "PASS" else "FAIL"}] $name $extra") }
    fun bars(prices: List<Double>, start: String = "2025-01-01T09:15:00"): Bars {
        val t0 = LocalDateTime.parse(start).toEpochSecond(ZoneOffset.UTC)
        return Bars(LongArray(prices.size) { t0 + it * 60L }, prices.toDoubleArray(), prices.toDoubleArray(), prices.toDoubleArray(), prices.toDoubleArray(), DoubleArray(prices.size))
    }
    fun walkPath(pts: List<Double>, step: Double = 0.25): List<Double> {
        val seq = ArrayList<Double>(); var cur = pts[0]
        for (tgt in pts.drop(1)) { val s = if (tgt > cur) step else -step; while (abs(tgt - cur) > 1e-9) { cur += s; seq.add(cur) } }
        return seq
    }

    log("1) Renko builder")
    val b1 = buildRenko(bars(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 104.5, 103.5, 102.9, 103.0, 104.0, 105.0)), 0.0, fixedBox = 1.0, path = "close", reversal = 2)
    check("5 up bricks 100->105", (0 until 5).all { b1.dir[it] == 1 } && abs(b1.c[4] - 105) < 1e-9)
    check("reversal brick opens at previous brick OPEN (104) and closes 103", b1.dir[5] == -1 && abs(b1.o[5] - 104) < 1e-9 && abs(b1.c[5] - 103) < 1e-9)
    val b1r = buildRenko(bars(listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 104.5, 103.5, 102.9, 103.0, 104.0, 105.0)), 0.0, fixedBox = 1.0, path = "close", reversal = 1)
    check("reversal=1 opens at previous CLOSE", b1r.dir[5] == -1 && abs(b1r.o[5] - 105) < 1e-9)

    log("2) Pattern scanner (tested resistance 110 twice, pullback, breakout)")
    val ch2 = Chart(bars(walkPath(listOf(100.0, 110.0, 107.0, 110.0, 106.0, 116.0, 112.0))), 0.0, path = "close", fixedBox = 1.0)
    val fired = ch2.sc.multiB.count { it }
    check("multi-swing breakout fires ONCE (the old code fired on every brick)", fired == 1, "(fired $fired)")
    val i2 = ch2.sc.multiB.indexOfFirst { it }
    check("target = projection brick + pattern height x box", abs(ch2.sc.multiTU[i2] - (ch2.bcl[i2] + 3.0)) < 1e-9, "(target ${ch2.sc.multiTU[i2]}, close ${ch2.bcl[i2]})")

    log("2b) Simulator: fills, stop and costs on a hand-made path")
    val d3 = bars(walkPath(listOf(100.0, 120.0, 116.0, 126.0, 105.0)), "2025-01-02T09:20:00")
    val ch3 = Chart(d3, 0.0, path = "close", fixedBox = 1.0)
    val ents = (0 until ch3.n).filter { ch3.sc.bullBO[it] && !ch3.sc.swL[it].isNaN() }.map { Entry(it, 1, ch3.sc.swL[it], "T") }
    val R3 = Rules(intraday = false, slippage = 0.0, trail = false)
    val t3 = simulate(ch3, ents, R3, "T", "X", { 10 }, "futures")
    val (xi, why) = manage(ch3, ents[0], R3)
    check("breakout found and traded", ents.isNotEmpty() && t3.size == 1, "(${ents.size} entries)")
    check("exit reason is SL and the exit brick closed beyond the swing low", why == "SL" && ch3.bcl[xi] < ents[0].stopRef)
    check("entry fill = open of the NEXT bar after the breakout brick", abs(t3[0].entryPx - d3.o[ch3.bbar[ents[0].i] + 1]) < 1e-9)
    check("exit fill = open of the NEXT bar after the stop brick", abs(t3[0].exitPx - d3.o[ch3.bbar[xi] + 1]) < 1e-9)
    check("net = gross - costs, costs > 0", abs(t3[0].net - (t3[0].gross - t3[0].costs)) < 0.02 && t3[0].costs > 0)

    log("3) Auth helpers + login flows (HTTP mocked)")
    val au = FyersAuth("ABC-100", "SECRET", "http://127.0.0.1:5057/")
    check("appIdHash = sha256('ABC-100:SECRET')", au.appHash == sha256Hex("ABC-100:SECRET"))
    check("login URL", au.loginUrl().contains("client_id=ABC-100") && au.loginUrl().contains("generate-authcode"))
    check("auth_code from redirect URL", FyersAuth.extractCode("http://127.0.0.1:5057/?s=ok&code=200&auth_code=XYZ.123&state=a") == "XYZ.123")
    Thread { Thread.sleep(600); try { java.net.URL("http://127.0.0.1:5057/?s=ok&auth_code=CAUGHT&state=x").readText() } catch (e: Exception) { /* ignore */ } }.start()
    check("local redirect server captures auth_code", au.captureCode(10) == "CAUGHT")

    // real HTTP client (not mocked) against a throw-away local server
    val web = HttpServer.create(InetSocketAddress("127.0.0.1", 5058), 0)
    fun reply(ex: com.sun.net.httpserver.HttpExchange, text: String) { val b = text.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
    web.createContext("/get") { ex -> reply(ex, """{"s":"ok","auth":"${ex.requestHeaders.getFirst("Authorization")}","n":[1,2.5,"a\u0041"],"flag":true,"nothing":null}""") }
    web.createContext("/post") { ex -> reply(ex, Json.write(mapOf("s" to "ok", "echo" to ex.requestBody.readBytes().toString(Charsets.UTF_8)))) }
    web.createContext("/bad") { ex -> reply(ex, "<html>oops</html>") }
    web.start()
    try {
        val g = Net.get("http://127.0.0.1:5058/get", mapOf("Authorization" to "APP:TOK", "version" to "3")).map
        check("real HTTP GET: headers sent, JSON parsed (ints, doubles, escapes, bool, null)", g["s"] == "ok" && g["auth"] == "APP:TOK" && (g["n"] as List<*>)[1] == 2.5 && (g["n"] as List<*>)[0] == 1L && (g["n"] as List<*>)[2] == "aA" && g["flag"] == true && g["nothing"] == null)
        val pr = Net.post("http://127.0.0.1:5058/post", linkedMapOf("grant_type" to "x", "pin" to "1234")).map
        check("real HTTP POST: JSON body sent", pr["echo"] == """{"grant_type":"x","pin":"1234"}""")
        check("non-JSON reply becomes an error map (no crash)", Net.get("http://127.0.0.1:5058/bad", emptyMap()).map["s"] == "error")
    } finally { web.stop(0) }

    val tmp = java.nio.file.Files.createTempDirectory("renko_st").toFile()
    val valid = hashSetOf("AT1")
    val origGet = Net.getImpl; val origPost = Net.postImpl; val origRead = IO.readLineFn
    try {
        Net.getImpl = { _, h -> Resp(200, mapOf("s" to if (valid.contains(h["Authorization"]!!.substringAfter(":"))) "ok" else "error")) }
        Net.postImpl = { url, body ->
            @Suppress("UNCHECKED_CAST") val j = Json.parse(body) as Map<String, Any?>
            when {
                url.endsWith("validate-authcode") && j["code"] == "THECODE" && j["appIdHash"] == sha256Hex("A-100:S") -> { valid.add("AT_NEW"); Resp(200, mapOf("s" to "ok", "access_token" to "AT_NEW", "refresh_token" to "RT_NEW")) }
                url.endsWith("validate-refresh-token") && j["refresh_token"] == "RT_NEW" && j["pin"] == "1234" -> { valid.add("AT_REFRESHED"); Resp(200, mapOf("s" to "ok", "access_token" to "AT_REFRESHED")) }
                else -> Resp(200, mapOf("s" to "error", "message" to "bad request $body"))
            }
        }
        IO.readLineFn = { "https://example.com/cb?s=ok&code=200&auth_code=THECODE&state=renko_bt" }
        val tf = File(tmp, "tok.json")
        val a1 = FyersAuth("A-100", "S", "https://example.com/cb", "1234", tf, manual = true)
        check("interactive login stores access + refresh token", a1.getToken() == "AT_NEW" && (Json.parse(tf.readText()) as Map<*, *>)["refresh_token"] == "RT_NEW")
        check("next run reuses the cached token", FyersAuth("A-100", "S", "https://example.com/cb", "1234", tf, manual = true).getToken() == "AT_NEW")
        valid.remove("AT_NEW")                                                       // token expired overnight
        check("expired token is renewed with refresh token + PIN (no browser)", FyersAuth("A-100", "S", "https://example.com/cb", "1234", tf, manual = true).getToken() == "AT_REFRESHED"
            && (Json.parse(tf.readText()) as Map<*, *>)["refresh_token"] == "RT_NEW")
    } finally { Net.getImpl = origGet; Net.postImpl = origPost; IO.readLineFn = origRead }

    log("4) Option symbol helpers + tolerant parsers + CSV loader")
    val e = LocalDate.of(2025, 1, 9)
    val s1 = buildOptionSymbol("NSE:NIFTY", e, 23500, "CE", false)
    check("weekly symbol", s1 == "NSE:NIFTY2510923500CE", s1)
    check("Oct weekly uses O", buildOptionSymbol("NSE:NIFTY", LocalDate.of(2025, 10, 14), 25000, "PE", false) == "NSE:NIFTY25O1425000PE")
    check("monthly symbol", buildOptionSymbol("NSE:NIFTY", LocalDate.of(2025, 1, 30), 23500, "CE", true) == "NSE:NIFTY25JAN23500CE")
    check("parse weekly", parseOptionSymbol(s1, "NSE:NIFTY", e) == Pair(23500, "CE"))
    check("parse monthly", parseOptionSymbol("NSE:NIFTY25JAN23500CE", "NSE:NIFTY", LocalDate.of(2025, 1, 30)) == Pair(23500, "CE"))
    check("dates (ISO / epoch / dd-mm-yyyy)", parseDates(Json.parse("""{"data":[{"date":"09-01-2025","expiry":"1736406000"},"2025-01-16"]}""")) == listOf(LocalDate.of(2025, 1, 9), LocalDate.of(2025, 1, 16)))
    val csv1 = File(tmp, "a.csv"); csv1.writeText("Date,Time,Open,High,Low,Close,Volume\n" + (0 until 375).joinToString("\n") { m -> val t = 9 * 60 + 15 + m; String.format("2025-01-01,%02d:%02d:00,1,2,0.5,1.5,0", t / 60, t % 60) })
    val csv2 = File(tmp, "b.csv"); csv2.writeText("datetime,open,high,low,close\n" + (0 until 375).joinToString("\n") { m -> val t = 9 * 60 + 15 + m; String.format("2025-01-01 %02d:%02d:00+05:30,1,2,0.5,1.5", t / 60, t % 60) })
    check("CSV loader: date+time columns", loadCsvBars(csv1.path).n == 375 && fmtT(loadCsvBars(csv1.path).t[0]) == "2025-01-01 09:15:00")
    check("CSV loader: tz-aware datetime column", loadCsvBars(csv2.path).n == 375 && fmtT(loadCsvBars(csv2.path).t[0]) == "2025-01-01 09:15:00")

    log("5) End-to-end on a synthetic market with mocked FYERS replies")
    val rng = java.util.Random(7)
    val days = generateSequence(LocalDate.of(2025, 2, 3)) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(40).toList()
    val rows = ArrayList<DoubleArray>(); var price = 23000.0
    for ((di, d) in days.withIndex()) {
        val drift = doubleArrayOf(0.00004, -0.00004, 0.0, 0.00006)[(di / 3) % 4]
        for (m in 0 until 375) {
            val o = price; price *= 1 + drift + rng.nextGaussian() * 0.00045
            val hi = max(o, price) * (1 + abs(rng.nextGaussian() * 0.00012)); val lo = min(o, price) * (1 - abs(rng.nextGaussian() * 0.00012))
            rows.add(doubleArrayOf((localEpoch(d) + (9 * 60 + 15 + m) * 60L).toDouble(), o, hi, lo, price, 0.0))
        }
    }
    val spot = Bars.fromRows(rows)
    val expiries = days.filter { it.dayOfWeek.value == 4 }
    val dd = generateSequence(LocalDate.of(2025, 3, 28)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(700).toList().reversed()
    var dp = 20000.0; val drows = ArrayList<DoubleArray>()
    for ((k, d) in dd.withIndex()) {
        val drift = doubleArrayOf(0.003, 0.0005, -0.003, 0.0008)[(k / 60) % 4]
        val o = dp; dp *= 1 + drift + rng.nextGaussian() * 0.007
        drows.add(doubleArrayOf(localEpoch(d).toDouble(), o, max(o, dp) * 1.002, min(o, dp) * 0.998, dp, 0.0))
    }
    val mock = MockFyers(spot, Bars.fromRows(drows), expiries, File(tmp, "cache").path)
    val a = parseArgs(arrayOf("--start", "2025-02-03", "--end", "2025-03-28", "--symbols", "NSE:NIFTYBEES-EQ,NSE:RELIANCE-EQ", "--warmup-days", "3"))
    val ctx = Ctx(a, mock, LocalDate.of(2025, 2, 3), LocalDate.of(2025, 3, 28))
    val ctxDaily = Ctx(a, mock, LocalDate.of(2023, 6, 1), LocalDate.of(2025, 3, 28))      // daily strategies need a longer window
    val res = LinkedHashMap<String, List<Trade>>()
    for (tag in listOf("S1", "S2", "S3", "S4", "S7", "S9", "S10", "S11", "S12", "S13")) {
        val p0 = PRESETS.getValue(tag)
        val p = if (p0.kind == "s9" || p0.kind == "s13") Preset(p0.desc, p0.und, p0.kind, p0.pct, p0.res, p0.intraday, 600, p0.crossExit, p0.st)
        else Preset(p0.desc, p0.und, p0.kind, p0.pct, p0.res, p0.intraday, min(p0.warm, 3), p0.crossExit, p0.st)
        res[tag] = when (p.kind) { "s9", "s13" -> runDailySymbols(tag, p, ctxDaily); "s10" -> runS10(tag, p, ctx); "s11", "s12" -> runOptionDaily(tag, p, ctx); else -> runSpotFamily(tag, p, ctx) }
    }
    res.forEach { (t, l) -> log("    $t: ${l.size} trades") }
    check("spot strategies produced trades", listOf("S1", "S4", "S7").all { res.getValue(it).isNotEmpty() })
    check("daily strategies ran (S13 trades; S9 may be empty on random data)", res.getValue("S13").isNotEmpty(), "(S9 ${res.getValue("S9").size}, S13 ${res.getValue("S13").size})")
    check("option strategies ran (S10/S11/S12 produce trades)", listOf("S10", "S11", "S12").all { res.getValue(it).isNotEmpty() })
    val allT = res.values.flatten()
    check("no trade exits before it enters", allT.all { it.exitT >= it.entryT })
    val intr = allT.filter { it.strategy in setOf("S1", "S4", "S7", "S10", "S11", "S12") }
    check("intraday trades are flat by 15:15 and same-day", intr.all { dayOf(it.exitT) == dayOf(it.entryT) && Math.floorMod(it.exitT, 86400L) / 60 <= 15 * 60 + 15 })
    val sh = res.getValue("S12")
    check("S12 are short option trades", sh.isNotEmpty() && sh.all { it.side == "SHORT" })
    report(res, File(tmp, "out"))
    log("\nSELFTEST " + if (ok) "PASSED" else "FAILED")
    return if (ok) 0 else 1
}
