package com.wxgy.checkin

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

class ApiError(message: String) : Exception(message)

data class Today(val checkFlag: Int, val leaveFlag: Int, val dormitoryFlag: Int, val date: String) {
    val done: Boolean get() = checkFlag == 1
    val leave: Boolean get() = leaveFlag == 1
    val free: Boolean get() = dormitoryFlag == 1
}

data class Student(
    val name: String,
    val no: String,
    val grade: String,
    val lat: Double,
    val lon: Double,
)

data class CheckResult(val ok: Boolean, val msg: String)

/**
 * 学校接口封装。逻辑与桌面版 server.js 一一对应：
 * CAS OAuth2 登录 -> accessToken -> 签名请求 -> preCheckIn -> 提交照片 -> 轮询核验结果。
 */
object Api {

    private const val KAOQ = "https://kaoq.wxgyxy.edu.cn"
    private const val CAS = "http://ca.wxgyxy.edu.cn"
    private const val CLIENT_ID = "dpJ3yifnJEKhZO2cHN"
    private const val APP_ID = "wxgycq"
    private const val SECRET = "wxgycq#2026"
    private const val HARD_CHECK_TOKEN = "041l1iHa1QOqHL06TcJa1GrNig3l1iH1"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.44"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    /** 学校站点证书链不完整，桌面版直接放行校验，这里保持一致（仅作用于本应用的请求） */
    private val client: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    var token: String = ""

    /** 登录态失效时用它重新登录，由上层注入（读取本地保存的账密） */
    var relogin: (suspend () -> Pair<String, String>?)? = null

    /** 拿到新 token 后回写 */
    var onToken: ((String) -> Unit)? = null

    var student: Student? = null
        private set

    // ------------------------------------------------------------ 基础请求

    private class Jar {
        private val map = LinkedHashMap<String, String>()

        fun header(): String? =
            if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }

        fun absorb(res: Response) {
            for (c in res.headers("Set-Cookie")) {
                val pair = c.substringBefore(';')
                val i = pair.indexOf('=')
                if (i > 0) map[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
        }
    }

    private fun call(
        jar: Jar?,
        url: String,
        method: String = "GET",
        body: RequestBody? = null,
        extra: Map<String, String> = emptyMap(),
    ): Response {
        val hb = Headers.Builder()
            .add("User-Agent", UA)
            .add("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .add("Accept-Language", "zh-CN,zh;q=0.9")
        extra.forEach { (k, v) -> hb.set(k, v) }
        jar?.header()?.let { hb.set("Cookie", it) }
        val req = Request.Builder().url(url).method(method, body).headers(hb.build()).build()
        val res = client.newCall(req).execute()
        jar?.absorb(res)
        return res
    }

    private fun absolute(base: String, loc: String): String =
        if (loc.startsWith("http://") || loc.startsWith("https://")) loc else URL(URL(base), loc).toString()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun nonceStr(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun signHeaders(rawBody: String): List<Pair<String, String>> {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = nonceStr(10) + nonceStr(6)
        val n = listOf("appId=$APP_ID", "timestamp=$timestamp", "nonce=$nonce").sorted().joinToString("&")
        return listOf(
            "appId" to APP_ID,
            "timestamp" to timestamp,
            "nonce" to nonce,
            "sign" to sha256(rawBody + n + SECRET),
        )
    }

    /** 带签名的业务接口调用；401 时用保存的账密自动重登一次 */
    private suspend fun api(
        method: String,
        path: String,
        body: JSONObject? = null,
        retried: Boolean = false,
    ): JSONObject {
        val raw = body?.toString() ?: ""
        val hb = Headers.Builder()
            .add("User-Agent", UA)
            .add("Origin", KAOQ)
            .add("Referer", "$KAOQ/spxyWeb/")
            .add("Authorization", "Bearer $token")
        signHeaders(raw).forEach { (k, v) -> hb.add(k, v) }
        if (body != null) hb.set("Content-Type", "application/json; charset=utf-8")

        val res = try {
            call(null, KAOQ + path, method, if (body == null) null else raw.toRequestBody(JSON), hb.build().toMap())
        } catch (e: Exception) {
            throw ApiError(friendly(e))
        }
        val text = res.use { it.body?.string().orEmpty() }
        val out = try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().put("code", res.code).put("msg", text.take(200))
        }

        if (out.optInt("code") == 401 && !retried && !path.contains("cas-login")) {
            val creds = relogin?.invoke()
            if (creds != null) {
                val d = casLogin(creds.first, creds.second)
                token = d
                onToken?.invoke(d)
                return api(method, path, body, true)
            }
            throw ApiError("登录已失效，请重新登录")
        }
        return out
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            e is ApiError -> m
            m.contains("timeout", true) -> "请求超时，学校站点没有响应"
            m.contains("Failed to connect", true) || m.contains("UnknownHost", true) ->
                "网络不可达，请检查是否连着校园网"
            m.contains("SSL", true) || m.contains("Certificate", true) -> "证书校验失败，换网络再试"
            else -> m.ifBlank { "网络异常" }
        }
    }

    // ------------------------------------------------------------ 登录

    private fun rsaEncrypt(modulusHex: String, exponentHex: String, password: String): String =
        CasCrypto.rsaEncrypt(modulusHex, exponentHex, password)

    /** 完整 CAS 登录，成功后返回 accessToken */
    suspend fun casLogin(username: String, password: String): String = withContext(Dispatchers.IO) {
        val jar = Jar()
        val redirectUri = "$KAOQ/spxyWeb/#/getCasCode"
        val authUrl = "$CAS/cas/oauth2.0/authorize?client_id=$CLIENT_ID&response_type=code" +
                "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}"

        var res = call(jar, authUrl)
        var html = ""
        for (hop in 0 until 6) {
            if (res.isRedirect) {
                val loc = res.header("Location") ?: throw ApiError("CAS 跳转异常（缺少 Location）")
                res.close()
                res = call(jar, absolute(CAS, loc))
                continue
            }
            html = res.use { it.body?.string().orEmpty() }
            break
        }

        val execution = Regex("name=\"execution\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: throw ApiError("CAS 页面解析失败，学校登录页可能已改版")

        val pkText = call(jar, "$CAS/cas/v2/getPubKey").use { it.body?.string().orEmpty() }
        val pk = try {
            JSONObject(pkText)
        } catch (_: Exception) {
            throw ApiError("获取登录公钥失败")
        }
        val encrypted = rsaEncrypt(pk.getString("modulus"), pk.getString("exponent"), password)

        val form = "username=" + URLEncoder.encode(username, "UTF-8") +
                "&password=" + URLEncoder.encode(encrypted, "UTF-8") +
                "&execution=" + URLEncoder.encode(execution, "UTF-8") +
                "&_eventId=submit"
        val service = URLEncoder.encode("$CAS/cas/oauth2.0/callbackAuthorize", "UTF-8")
        res = call(
            jar,
            "$CAS/cas/login?service=$service",
            method = "POST",
            body = form.toRequestBody(FORM),
            extra = mapOf("Referer" to "$CAS/cas/login"),
        )

        if (res.code == 200) {
            val body = res.use { it.body?.string().orEmpty() }
            val err = Regex("id=\"errormsg\"[^>]*>([\\s\\S]{0,200}?)</p>")
                .find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            throw ApiError("登录失败：" + (err?.takeIf { it.isNotEmpty() } ?: "账号或密码错误"))
        }

        var code: String? = null
        for (hop in 0 until 8) {
            if (!res.isRedirect) break
            val loc = res.header("Location").orEmpty()
            val m = Regex("[?#&]code=([^&#]+)").find(loc)
            if (m != null) {
                code = m.groupValues[1]
                res.close()
                break
            }
            res.close()
            res = call(jar, absolute(CAS, loc))
        }
        val ticket = code ?: throw ApiError("未取得 CAS 授权码")

        val outText = call(
            null,
            "$KAOQ/app-api/system/auth/cas-login-app",
            method = "POST",
            body = JSONObject().put("code", ticket).toString().toRequestBody(JSON),
            extra = mapOf("Origin" to KAOQ, "Referer" to "$KAOQ/spxyWeb/"),
        ).use { it.body?.string().orEmpty() }

        val out = try {
            JSONObject(outText)
        } catch (_: Exception) {
            throw ApiError("换取 token 失败（返回不是 JSON）")
        }
        if (out.optInt("code") != 0) throw ApiError(out.optString("msg", "换取 token 失败"))
        val data = out.optJSONObject("data") ?: throw ApiError("换取 token 失败")
        if (data.optString("userType") != "1") throw ApiError("该账号不是学生账号")
        data.optString("accessToken").takeIf { it.isNotBlank() } ?: throw ApiError("未返回 accessToken")
    }

    // ------------------------------------------------------------ 业务

    suspend fun fetchStudent(): Student {
        val info = api("GET", "/app-api/system/student/getInfo")
        if (info.optInt("code") != 0) throw ApiError(info.optString("msg", "获取学生信息失败"))
        val d = info.optJSONObject("data") ?: throw ApiError("获取学生信息失败")

        var lat = 31.3364
        var lon = 119.8347
        runCatching {
            val arr = JSONArray(d.optString("fenceRule"))
            if (arr.length() > 0) {
                var sl = 0.0
                var sg = 0.0
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    sl += p.optDouble("lat")
                    sg += p.optDouble("lng")
                }
                lat = Math.round(sl / arr.length() * 1e6) / 1e6
                lon = Math.round(sg / arr.length() * 1e6) / 1e6
            }
        }

        val s = Student(
            name = d.optString("name"),
            no = d.optString("studentNo"),
            grade = d.optString("gradeName"),
            lat = lat,
            lon = lon,
        )
        student = s
        return s
    }

    suspend fun todayStatus(): Today? {
        val st = api("GET", "/app-api/attendance/check/getCheckInTodayStatus")
        if (st.optInt("code") != 0) throw ApiError(st.optString("msg", "获取今日状态失败"))
        val d = st.optJSONObject("data") ?: return null
        return Today(
            checkFlag = d.optInt("checkFlag"),
            leaveFlag = d.optInt("leaveFlag"),
            dormitoryFlag = d.optInt("dormitoryFlag"),
            date = d.optString("date").ifBlank { d.optString("checkDate") },
        )
    }

    /** 提交现场照片打卡，onStep 用于回传进度文案 */
    suspend fun checkIn(photo: ByteArray, onStep: (String) -> Unit): CheckResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext CheckResult(false, "请先登录")

        val today = runCatching { todayStatus() }.getOrNull()
        if (today?.done == true) return@withContext CheckResult(true, "今日已打卡")
        if (today?.leave == true) return@withContext CheckResult(false, "今日已请假，无需打卡")
        if (today?.free == true) return@withContext CheckResult(false, "今日免查寝，无需打卡")

        val s = student ?: runCatching { fetchStudent() }.getOrNull()
        val lat = s?.lat ?: 31.3364
        val lon = s?.lon ?: 119.8347

        onStep("正在获取打卡凭证…")
        var checkToken = HARD_CHECK_TOKEN
        val pre = api(
            "POST",
            "/app-api/attendance/check/preCheckIn",
            JSONObject().put("longitude", lon).put("latitude", lat),
        )
        if (pre.optInt("code") == 0) {
            pre.optJSONObject("data")?.optString("checkToken")
                ?.takeIf { it.isNotBlank() }?.let { checkToken = it }
        }

        onStep("正在上传照片…")
        val b64 = "data:image/jpeg;base64," + Base64.encodeToString(photo, Base64.NO_WRAP)
        if (b64.length <= 30) return@withContext CheckResult(false, "照片无效，请重拍")
        val payload = JSONObject()
            // 学校接口在 data URL 第 30 个字符处插入这个标记，服务端据此校验
            .put("faceImageGg", b64.substring(0, 30) + "KwLiSlaC" + b64.substring(30))
            .put("addressRr", "无锡工艺职业技术学院")
            .put("longitudeK", lon)
            .put("latitudeL", lat)
            .put("deviceInfoR", "手机")
            .put("checkToken", checkToken)

        val r = api("POST", "/app-api/attendance/check/studentChecKlnSecret", payload)
        if (r.optInt("code") != 0) {
            return@withContext CheckResult(false, r.optString("msg").ifBlank { "提交失败（${r.optInt("code")}）" })
        }
        val requestId = r.optJSONObject("data")?.optString("requestId").orEmpty()
        if (requestId.isBlank()) return@withContext CheckResult(false, "未返回业务流水号")

        onStep("正在核验人脸…")
        val deadline = System.currentTimeMillis() + 100_000
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val st = runCatching { api("GET", "/app-api/attendance/check/status/$requestId") }.getOrNull()
                ?: continue
            val dd = st.optJSONObject("data") ?: continue
            when (dd.optInt("status")) {
                1 -> return@withContext CheckResult(true, "打卡成功")
                2 -> {
                    if (dd.optString("resultCode") == "ALREADY_CHECKED_IN") {
                        return@withContext CheckResult(true, "今日已打卡")
                    }
                    return@withContext CheckResult(
                        false,
                        dd.optString("resultMsg").ifBlank { "打卡失败" },
                    )
                }
            }
        }
        CheckResult(false, "核验超时，结果未知")
    }

    fun logout() {
        token = ""
        student = null
    }
}
