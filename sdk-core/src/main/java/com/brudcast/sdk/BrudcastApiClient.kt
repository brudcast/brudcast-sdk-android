package com.brudcast.sdk

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService

/**
 * A failed SDK API call.
 *
 * @property statusCode The HTTP status, or null when no response arrived.
 * @property code The service's error code, when it sent one.
 * @property message What went wrong.
 */
public data class BrudcastApiError(
    val statusCode: Int?,
    val code: String?,
    override val message: String,
) : Exception(message) {
    /** True for a status worth retrying: no response, 408, 429 or any 5xx. */
    val isRetryable: Boolean
        get() = statusCode == null || statusCode == 408 || statusCode == 429 || statusCode >= 500

    override fun toString(): String =
        statusCode?.let { "HTTP $it${code?.let { c -> " $c" } ?: ""}: $message" } ?: message
}

/**
 * The outcome of an SDK API call: the envelope's `data`, or the error.
 */
public sealed class BrudcastResult {
    public data class Success(val data: JSONObject) : BrudcastResult()
    public data class Failure(val error: BrudcastApiError) : BrudcastResult()
}

public enum class BrudcastHttpMethod {
    GET, POST, PUT, DELETE
}

/**
 * Calls the SDK API and unwraps its `{ status, message, data }` envelope.
 */
public class BrudcastApiClient internal constructor(
    public val baseUrl: String,
    private val publishableKey: String,
    private val io: ExecutorService,
) {
    /**
     * Builds the URL of a path under the SDK API.
     *
     * @param path The path after `/sdk/v1`, such as `/messages`.
     * @param query Query parameters to append.
     * @return The URL.
     */
    public fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val base = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (query.isEmpty()) return base
        return base + "?" + query.entries.sortedBy { it.key }.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    /**
     * Sends a request on the IO executor.
     *
     * @param method The HTTP method.
     * @param path The path after `/sdk/v1`.
     * @param query Query parameters.
     * @param body A JSON body, or null.
     * @param token The session token, or null for the session-opening call.
     * @param completion Called on an IO thread with the result.
     */
    public fun send(
        method: BrudcastHttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        token: String? = null,
        completion: (BrudcastResult) -> Unit,
    ) {
        io.execute {
            completion(execute(method, url(path, query), body, token))
        }
    }

    private fun execute(method: BrudcastHttpMethod, url: String, body: JSONObject?, token: String?): BrudcastResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.name
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", BrudcastSdk.userAgent)
                if (token != null) {
                    setRequestProperty("Authorization", "Bearer $token")
                } else {
                    setRequestProperty("X-Brudcast-Key", publishableKey)
                }
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            decode(status, text)
        } catch (error: IOException) {
            BrudcastResult.Failure(BrudcastApiError(null, null, error.message ?: error.javaClass.simpleName))
        } finally {
            connection?.disconnect()
        }
    }

    internal companion object {
        fun decode(status: Int, text: String): BrudcastResult {
            val json = try {
                if (text.isBlank()) null else JSONObject(text)
            } catch (_: JSONException) {
                null
            }
            if (status in 200..299) {
                return BrudcastResult.Success(json?.optJSONObject("data") ?: JSONObject())
            }
            val message = json?.optString("message")?.ifEmpty { null } ?: "HTTP $status"
            val code = json?.let { if (it.isNull("code")) null else it.optString("code").ifEmpty { null } }
            return BrudcastResult.Failure(BrudcastApiError(status, code, message))
        }
    }
}
