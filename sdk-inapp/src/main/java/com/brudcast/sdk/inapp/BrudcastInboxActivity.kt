package com.brudcast.sdk.inapp

import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brudcast.sdk.BrudcastLogger
import kotlinx.coroutines.launch

/**
 * The default inbox screen, opened with [BrudcastInbox.present].
 */
public class BrudcastInboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Inbox"
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setContent {
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                InboxScreen(title, InAppTheme(MaterialTheme.colorScheme.primary, isDark)) { finish() }
            }
        }
    }

    internal companion object {
        const val EXTRA_TITLE = "com.brudcast.sdk.inapp.TITLE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(title: String, theme: InAppTheme, onClose: () -> Unit) {
    val inbox = InAppModule.inbox
    val messages = remember { mutableStateListOf<BrudcastInAppMessage>() }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<BrudcastInAppMessage?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun load(before: Long?) {
        if (loading) return
        loading = true
        inbox.list(before = before, limit = 20) { result ->
            loading = false
            refreshing = false
            result.onSuccess { page ->
                if (before == null) messages.clear()
                messages.addAll(page.messages.filter { message -> messages.none { it.id == message.id } })
                hasMore = page.hasMore
            }.onFailure { BrudcastLogger.warning("Inbox failed to load: ${it.message}") }
        }
    }

    LaunchedEffect(Unit) { load(null) }

    val current = opened
    if (current != null) {
        BackHandler { opened = null }
        val design = inbox.entry(current.id)?.design
        Scaffold(topBar = {
            TopAppBar(title = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                TextButton(onClick = { opened = null }) { Text("Back") }
            })
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (design != null) {
                    InAppBackground(design.layout, theme, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(design.layout.padding.dp)) {
                            InAppBlockList(design.blocks, theme, { buttonId, action -> inbox.buttonTapped(current.id, buttonId, action) })
                        }
                    }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, actions = { TextButton(onClick = onClose) { Text("Done") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; load(null) }, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (messages.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(messages, key = { it.id }) { message ->
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            val index = messages.indexOf(message)
                            messages.remove(message)
                            scope.launch {
                                val result = snackbar.showSnackbar("Message deleted", actionLabel = "Undo")
                                if (result == SnackbarResult.ActionPerformed) {
                                    messages.add(index.coerceIn(0, messages.size), message)
                                } else {
                                    inbox.delete(message.id)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    })
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)) },
                    ) {
                        InboxRow(message) {
                            inbox.markRead(message.id)
                            val index = messages.indexOf(message)
                            if (index >= 0) messages[index] = message.copy(isRead = true)
                            opened = message
                        }
                    }
                    HorizontalDivider()
                    if (message == messages.lastOrNull() && hasMore) {
                        LaunchedEffect(message.id) { load(message.createdAt) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(message: BrudcastInAppMessage, onClick: () -> Unit) {
    val time = DateUtils.getRelativeTimeSpanString(message.createdAt).toString()
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(if (message.isRead) null else "Unread", message.title, message.preview, time).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (message.isRead) Color.Transparent else MaterialTheme.colorScheme.primary))
        message.thumbnailUrl?.let { url ->
            var ready by remember(url) { mutableStateOf(InAppImageLoader.image(url) != null) }
            LaunchedEffect(url) { if (!ready) InAppImageLoader.load(url) { ready = it != null } }
            if (ready) InAppImage(url, "", 8f, Modifier.size(40.dp)) else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(message.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
