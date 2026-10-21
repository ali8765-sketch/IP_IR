package com.v2ray.ang.ui.chapar

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * An error from the Chapar backend. [code] is the backend's `error` value (for example
 * `gmail_only`, `nickname_taken`, `signed_out`), or one of the local codes below.
 */
class ChaparApiException(val code: String, val httpStatus: Int = 0) : Exception(code) {
    companion object {
        /** The request never reached the server (no route, no DNS, connection refused). */
        const val NETWORK = "network"

        /**
         * The connection broke after the request may have reached the server (a timeout or reset while
         * waiting for the answer). The server may have acted on it, for example replaced the key.
         */
        const val NETWORK_AFTER_SEND = "network_after_send"

        /** The TLS connection to the server could not be verified: not "offline", possibly intercepted. */
        const val INSECURE = "insecure"
        const val BAD_RESPONSE = "bad_response"
    }
}

data class SignedIn(val token: String, val nickname: String)

/** The account as the backend reports it (backend/chapar/api.py, `/api/v1/me`). */
data class AccountInfo(
    val nickname: String,
    /** `active`, `over_quota`, or `disabled`. */
    val status: String,
    val usedBytes: Long,
    val quotaBytes: Long,
    /** ISO-8601 instant of the next monthly reset (UTC). */
    val resetsAt: String,
)

/**
 * The app API (D-042). Blocking calls: run them on Dispatchers.IO. Never logs tokens or responses.
 */
class ChaparApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val gson = Gson()

    val isConfigured: Boolean get() = base.startsWith("https://")

    fun signIn(idToken: String, nickname: String?): SignedIn {
        val body = JsonObject().apply {
            addProperty("id_token", idToken)
            if (nickname != null) addProperty("nickname", nickname)
        }
        val json = call(post("/api/v1/auth/google", body.toString(), token = null))
        return SignedIn(json.string("token"), json.string("nickname"))
    }

    fun account(token: String): AccountInfo {
        val json = call(Request.Builder().url(url("/api/v1/me")).bearer(token).get().build())
        return AccountInfo(
            nickname = json.string("nickname"),
            status = json.string("status"),
            usedBytes = json.long("used_bytes"),
            quotaBytes = json.long("quota_bytes"),
            resetsAt = json.string("resets_at"),
        )
    }

    /**
     * A new key for this account as share-link lines, one per mode (D-043). The server cancels the
     * previous key at the same moment, so a copied link stops working on the next Connect.
     */
    fun connect(token: String): String = execute(post("/api/v1/connect", "", token)) { it.body.string() }

    fun logout(token: String) {
        execute(post("/api/v1/logout", "", token)) { }
    }

    fun deleteAccount(token: String) {
        execute(Request.Builder().url(url("/api/v1/me")).bearer(token).delete().build()) { }
    }

    private fun post(path: String, json: String, token: String?): Request =
        Request.Builder().url(url(path)).apply { if (token != null) bearer(token) }
            .post(json.toRequestBody(JSON)).build()

    /** Checked before OkHttp parses the URL, which throws on a missing scheme. */
    private fun url(path: String): String {
        if (!isConfigured) throw ChaparApiException(ChaparApiException.NETWORK)
        return "$base$path"
    }

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun call(request: Request): JsonObject = execute(request) { response ->
        gson.fromJson(response.body.string(), JsonObject::class.java)
            ?: throw ChaparApiException(ChaparApiException.BAD_RESPONSE, response.code)
    }

    /**
     * Runs [request] and [onSuccess]. Every failure becomes a [ChaparApiException]: reading the body can fail
     * after the headers arrived, and a malformed answer must never crash the app.
     */
    private fun <T> execute(request: Request, onSuccess: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ChaparApiException(classify(e))
        }
        try {
            response.use {
                if (it.isSuccessful) return onSuccess(it)
                val code = runCatching {
                    gson.fromJson(it.body.string(), JsonObject::class.java)?.get("error")?.asString
                }.getOrNull()
                throw ChaparApiException(code ?: ChaparApiException.BAD_RESPONSE, it.code)
            }
        } catch (e: ChaparApiException) {
            throw e
        } catch (e: IOException) {
            throw ChaparApiException(ChaparApiException.NETWORK_AFTER_SEND, response.code)
        } catch (e: RuntimeException) { // JSON, number, or state errors in a malformed answer
            throw ChaparApiException(ChaparApiException.BAD_RESPONSE, response.code)
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: throw ChaparApiException(ChaparApiException.BAD_RESPONSE)

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: throw ChaparApiException(ChaparApiException.BAD_RESPONSE)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Only failures that happen before anything was sent count as "offline" ([ChaparApiException.NETWORK]). */
        internal fun classify(e: IOException): String = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> ChaparApiException.NETWORK
            is SSLException -> ChaparApiException.INSECURE
            else -> ChaparApiException.NETWORK_AFTER_SEND
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            // The API never redirects; following one could send the session token somewhere else.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
