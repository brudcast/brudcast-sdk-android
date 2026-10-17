package com.brudcast.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's own backend, which signs users in and holds what the app must never contain: the
 * identity secret and the Brudcast API key. The README lists the endpoints it needs.
 *
 * @property baseUrl The backend's URL, such as `http://10.0.2.2:4000` for one on the host.
 */
class SampleBackend(private val baseUrl: String) {
    /** What the backend returns at sign-in, ready for `Brudcast.identify`. */
    class SignedIn(val externalId: String, val userHash: String, val email: String?)

    /**
     * Signs a user in. The backend computes the user hash with the identity secret.
     *
     * @param userId The user's id.
     * @param email The user's email, for the contact Brudcast creates.
     * @return The external id and user hash to identify with.
     * @throws IOException When the backend cannot be reached or refuses.
     */
    suspend fun login(userId: String, email: String?): SignedIn {
        val response = post("/login", JSONObject().put("userId", userId).put("email", email ?: JSONObject.NULL))
        return SignedIn(
            externalId = response.getString("externalId"),
            userHash = response.getString("userHash"),
            email = response.optString("email").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /**
     * Asks the backend to send the user a welcome in-app message through the Brudcast API.
     *
     * @param externalId The user's external id.
     * @throws IOException When the backend cannot be reached or Brudcast refuses the message.
     */
    suspend fun sendWelcome(externalId: String) {
        post("/welcome", JSONObject().put("externalId", externalId))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) throw IOException(json.optString("message", "HTTP $status"))
            json
        } finally {
            connection.disconnect()
        }
    }
}
