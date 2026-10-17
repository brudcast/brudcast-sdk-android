package com.brudcast.sample.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brudcast.sample.ActionLog
import com.brudcast.sample.BuildConfig
import com.brudcast.sample.SampleSession
import com.brudcast.sample.ui.DetailRow
import com.brudcast.sample.ui.Field
import com.brudcast.sample.ui.OutlineButton
import com.brudcast.sample.ui.PrimaryButton
import com.brudcast.sample.ui.Rule
import com.brudcast.sample.ui.Section
import com.brudcast.sample.ui.TrackScreen
import com.brudcast.sample.ui.tokens
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.inapp.inApp
import com.brudcast.sdk.push.push

/** Whether in-app messages are held back. The SDK keeps the state; this mirrors it for the switch. */
private object MessageDisplay {
    var paused by mutableStateOf(false)
}

/**
 * The user's account: notification and message settings, the actions messages handed the app,
 * tools for sending any event, build details, and sign-out.
 *
 * @param activity The activity, to ask for notification permission from.
 * @param user The signed-in user.
 */
@Composable
fun AccountScreen(activity: Activity, user: SampleSession.User) {
    TrackScreen("Account")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text(user.externalId, style = MaterialTheme.typography.headlineSmall)
            Text(user.email ?: "No email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Rule(strong = true)
        NotificationsSection(activity)
        Rule()
        MessagesSection()
        Rule()
        ActivitySection()
        Rule()
        DeveloperSection()
        Rule()
        Section("About this build") {
            DetailRow("Environment", BuildConfig.BRUDCAST_ENVIRONMENT)
            DetailRow("SDK running", if (Brudcast.isInitialized) "Yes" else "No")
            DetailRow("Device id", Brudcast.deviceId ?: "-")
        }
        Rule()
        Column(Modifier.padding(20.dp)) {
            OutlineButton(
                "Sign out",
                onClick = SampleSession::signOut,
                modifier = Modifier.fillMaxWidth(),
                destructive = true,
                tag = "signOutButton",
            )
        }
    }
}

@Composable
private fun NotificationsSection(activity: Activity) {
    val permission by Brudcast.push.permissionStatus.collectAsState()
    Section("Notifications", "Tokens register only with a google-services.json in sample/. See the README.") {
        DetailRow("Permission", permission.wire)
        OutlineButton("Allow notifications", onClick = { Brudcast.push.requestPermission(activity) })
    }
}

@Composable
private fun MessagesSection() {
    Section("In-app messages", "Pause to hold messages back, such as during onboarding.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pause display", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = MessageDisplay.paused,
                onCheckedChange = {
                    MessageDisplay.paused = it
                    if (it) Brudcast.inApp.pauseDisplay() else Brudcast.inApp.resumeDisplay()
                },
                colors = SwitchDefaults.colors(uncheckedBorderColor = MaterialTheme.tokens.borderStrong),
            )
        }
        OutlineButton("Sync now", onClick = { Brudcast.inApp.sync() })
    }
}

@Composable
private fun ActivitySection() {
    val actions by ActionLog.actions.collectAsState()
    Section("Activity", "Buttons in messages and notification taps the app was handed, newest first.") {
        if (actions.isEmpty()) {
            Text("Nothing yet. Tap a button in a message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.tokens.foregroundSubtle)
        }
        actions.forEach { Text(it, Modifier.testTag("actionLogEntry"), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DeveloperSection() {
    var name by remember { mutableStateOf("checkout_started") }
    var property by remember { mutableStateOf("product") }
    var value by remember { mutableStateOf("sneakers") }
    var screen by remember { mutableStateOf("Checkout") }
    Section("Developer tools", "Send any event or screen to try a campaign's trigger and filters.") {
        Field("Event name", name, { name = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Field("Property", property, { property = it }) }
            Column(Modifier.weight(1f)) { Field("Value", value, { value = it }) }
        }
        PrimaryButton("Track event", enabled = Brudcast.isValidEventName(name), onClick = {
            val properties = if (property.isBlank()) emptyMap() else mapOf(property.trim() to (value.toDoubleOrNull() ?: value))
            Brudcast.track(name, properties)
        })
        Field("Screen name", screen, { screen = it })
        OutlineButton("Record screen", enabled = screen.isNotBlank(), onClick = { Brudcast.screen(screen.trim()) })
    }
}
