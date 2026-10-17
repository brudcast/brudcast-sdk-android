package com.brudcast.sample

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.BrudcastTraits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own record of who is signed in, the way any app keeps its login. Signing in and out
 * here is also where the app tells Brudcast, so the two never disagree. Brudcast restores its own
 * identity at launch; this only decides which screen the app starts on.
 */
object SampleSession {
    /** The signed-in user. */
    class User(val externalId: String, val email: String?)

    private const val PREFERENCES = "sample_session"
    private const val KEY_EXTERNAL_ID = "external_id"
    private const val KEY_EMAIL = "email"

    private lateinit var preferences: SharedPreferences
    private val state = MutableStateFlow<User?>(null)

    /** The signed-in user, or null when signed out. */
    val current: StateFlow<User?> = state.asStateFlow()

    /**
     * Restores the saved session. Call once at launch.
     *
     * @param context Any context.
     */
    fun load(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        state.value = preferences.getString(KEY_EXTERNAL_ID, null)?.let { User(it, preferences.getString(KEY_EMAIL, null)) }
    }

    /**
     * Signs a user in and identifies them to Brudcast.
     *
     * @param externalId The user's id in the app.
     * @param userHash The hash the app's backend computed with the identity secret, or null when the
     *   application does not enforce identity verification.
     * @param email The user's email, for the contact Brudcast creates.
     */
    fun signIn(externalId: String, userHash: String?, email: String?) {
        Brudcast.identify(externalId, userHash, BrudcastTraits(email = email))
        preferences.edit {
            putString(KEY_EXTERNAL_ID, externalId)
            putString(KEY_EMAIL, email)
        }
        state.value = User(externalId, email)
    }

    /** Signs the user out and resets Brudcast, which clears their messages from the device. */
    fun signOut() {
        Brudcast.reset()
        preferences.edit { clear() }
        state.value = null
    }
}
