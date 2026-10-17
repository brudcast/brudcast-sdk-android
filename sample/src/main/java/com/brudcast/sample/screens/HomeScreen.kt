package com.brudcast.sample.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brudcast.sample.ActionLog
import com.brudcast.sample.BuildConfig
import com.brudcast.sample.SampleBackend
import com.brudcast.sample.SampleSession
import com.brudcast.sample.ui.MetricLedger
import com.brudcast.sample.ui.OutlineButton
import com.brudcast.sample.ui.PrimaryButton
import com.brudcast.sample.ui.Rule
import com.brudcast.sample.ui.Section
import com.brudcast.sample.ui.TrackScreen
import com.brudcast.sample.ui.tokens
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.inapp.inApp
import kotlinx.coroutines.launch

/**
 * Where a signed-in user lands: their message counts, a welcome message their backend sends, and a
 * way into the shop to set off campaigns.
 *
 * @param activity The activity, to present the inbox from.
 * @param user The signed-in user.
 * @param onShop Opens the shop.
 */
@Composable
fun HomeScreen(activity: Activity, user: SampleSession.User, onShop: () -> Unit) {
    TrackScreen("Home")
    val unread by Brudcast.inApp.inbox.unreadCount.collectAsState()
    val actions by ActionLog.actions.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text("Welcome back", style = MaterialTheme.typography.headlineSmall)
            Text(user.email ?: user.externalId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetricLedger("Unread messages" to unread, "Actions received" to actions.size)
        WelcomeSection(user)
        Rule()
        Section("Inbox", "Messages sent with an inbox copy stay here after they close.") {
            OutlineButton("Open inbox", onClick = { Brudcast.inApp.inbox.present(activity) })
        }
        Rule()
        Section("Set off a campaign", "Campaigns triggered by the checkout_started event or the Checkout screen show while you shop.") {
            PrimaryButton("Go to the shop", onClick = onShop)
        }
    }
}

/**
 * Asks the app's backend to send the user a welcome message through the Brudcast API, the way a
 * product sends transactional messages.
 */
@Composable
private fun WelcomeSection(user: SampleSession.User) {
    val backendUrl = BuildConfig.SAMPLE_BACKEND_URL
    val backend = remember(backendUrl) { backendUrl.takeIf { it.isNotBlank() }?.let(::SampleBackend) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Section("Welcome message", "Your backend sends it through the Brudcast API and it shows here at once.") {
        if (backend == null) {
            Text(
                "Add brudcast.sampleBackendUrl to local.properties to send one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Send me a welcome",
                    onClick = {
                        scope.launch {
                            failed = try {
                                backend.sendWelcome(user.externalId)
                                status = "Sent. It shows as soon as it arrives."
                                false
                            } catch (error: Exception) {
                                status = "Sending failed: ${error.message}"
                                true
                            }
                        }
                    },
                    tag = "sendWelcomeButton",
                )
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.tokens.destructiveForeground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
