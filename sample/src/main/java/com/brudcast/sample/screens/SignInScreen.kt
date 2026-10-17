package com.brudcast.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brudcast.sample.BuildConfig
import com.brudcast.sample.SampleBackend
import com.brudcast.sample.SampleSession
import com.brudcast.sample.ui.Field
import com.brudcast.sample.ui.Notice
import com.brudcast.sample.ui.PrimaryButton
import com.brudcast.sample.ui.TrackScreen
import com.brudcast.sample.ui.tokens
import kotlinx.coroutines.launch

/**
 * Signs the user in. With a sample backend configured it signs in the way a real app does: the
 * backend checks the user and returns the user hash, so the identity secret never reaches the
 * device. Without one it takes a pasted hash.
 */
@Composable
fun SignInScreen() {
    TrackScreen("SignIn")
    val backendUrl = BuildConfig.SAMPLE_BACKEND_URL
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Brudcast Sample", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.tokens.brand)
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (backendUrl.isBlank()) {
                "Paste the user hash your server computed. Without a backend, this is how you try identity verification."
            } else {
                "Your backend checks who you are and returns the user hash Brudcast.identify needs."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (BuildConfig.BRUDCAST_PUBLISHABLE_KEY.isBlank()) {
            Notice(
                "Set brudcast.publishableKey in local.properties, then rebuild. The SDK is not running.",
                MaterialTheme.tokens.warning,
                MaterialTheme.tokens.warningForeground,
            )
        }
        Spacer(Modifier.size(8.dp))
        if (backendUrl.isBlank()) ManualSignIn() else BackendSignIn(remember(backendUrl) { SampleBackend(backendUrl) })
        Spacer(Modifier.size(8.dp))
        Text(
            if (backendUrl.isBlank()) "Environment: ${BuildConfig.BRUDCAST_ENVIRONMENT}" else "Environment: ${BuildConfig.BRUDCAST_ENVIRONMENT}, backend: $backendUrl",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.tokens.foregroundSubtle,
        )
    }
}

@Composable
private fun BackendSignIn(backend: SampleBackend) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Field("User id", userId, { userId = it }, placeholder = "e.g. ada-lovelace", tag = "userIdField")
    Field("Email", email, { email = it }, placeholder = "ada@example.com", keyboardType = KeyboardType.Email, tag = "emailField")
    error?.let { Notice(it, MaterialTheme.tokens.destructive, MaterialTheme.tokens.destructiveForeground) }
    PrimaryButton(
        text = if (busy) "Signing in…" else "Sign in",
        onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val user = backend.login(userId.trim(), email.trim().ifEmpty { null })
                    SampleSession.signIn(user.externalId, user.userHash, user.email)
                } catch (failure: Exception) {
                    error = "Sign-in failed: ${failure.message}"
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = userId.isNotBlank() && !busy,
        tag = "signInButton",
    )
}

@Composable
private fun ManualSignIn() {
    var externalId by remember { mutableStateOf("") }
    var userHash by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    Field("External id", externalId, { externalId = it })
    Field("User hash", userHash, { userHash = it }, placeholder = "From your server")
    Field("Email", email, { email = it }, keyboardType = KeyboardType.Email)
    PrimaryButton(
        text = "Sign in",
        onClick = { SampleSession.signIn(externalId.trim(), userHash.trim().ifEmpty { null }, email.trim().ifEmpty { null }) },
        modifier = Modifier.fillMaxWidth(),
        enabled = externalId.isNotBlank(),
    )
}
