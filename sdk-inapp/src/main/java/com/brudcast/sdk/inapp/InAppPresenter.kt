package com.brudcast.sdk.inapp

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.brudcast.sdk.BrudcastAppearance
import com.brudcast.sdk.BrudcastLogger
import com.brudcast.sdk.BrudcastOptions
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Puts messages on screen as a view above the current activity's content. */
internal object InAppPresenter : Application.ActivityLifecycleCallbacks {
    private class Presentation(
        val messageId: String,
        val design: InAppDesign,
        val options: BrudcastOptions,
        val callbacks: InAppPresentationCallbacks,
    ) {
        var activity: WeakReference<Activity>? = null
        var view: ComposeView? = null
        val visible = MutableTransitionState(false)
        var impressed = false
        var closing = false
    }

    private var current: Presentation? = null
    private var resumed: WeakReference<Activity>? = null
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Shows a message. Must be called on the main thread.
     *
     * @return False when nothing could be shown, such as with no suitable activity.
     */
    fun present(messageId: String, design: InAppDesign, options: BrudcastOptions, callbacks: InAppPresentationCallbacks): Boolean {
        if (current != null) return false
        val activity = resumed?.get() ?: return false
        if (activity.isFinishing || options.presentationFilter?.invoke(activity) == false) return false
        val presentation = Presentation(messageId, design, options, callbacks)
        if (!attach(presentation, activity, animate = true)) return false
        current = presentation
        BrudcastLogger.debug("Presented message $messageId")
        return true
    }

    private fun attach(presentation: Presentation, activity: Activity, animate: Boolean): Boolean {
        val decor = activity.window?.decorView as? FrameLayout ?: return false
        if (decor.findViewTreeLifecycleOwner() == null) {
            BrudcastLogger.logOnce("no-lifecycle-${activity.javaClass.name}", com.brudcast.sdk.BrudcastLogLevel.WARNING) {
                "${activity.javaClass.simpleName} is not a ComponentActivity, so in-app messages cannot show on it"
            }
            return false
        }
        if (!animate) presentation.visible.targetState = true
        val theme = theme(activity, presentation.options)
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { Root(presentation, theme, reducedMotion(activity)) }
        }
        val params = if (presentation.design.format == InAppFormat.BANNER) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                if (presentation.design.layout.position == InAppPosition.BOTTOM) Gravity.BOTTOM else Gravity.TOP)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        decor.addView(view, params)
        presentation.view = view
        presentation.activity = WeakReference(activity)
        if (animate) presentation.visible.targetState = true
        return true
    }

    private fun detach(presentation: Presentation) {
        val view = presentation.view ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        presentation.view = null
        presentation.activity = null
    }

    private fun close(presentation: Presentation) {
        if (presentation.closing || current !== presentation) return
        presentation.closing = true
        presentation.visible.targetState = false
    }

    private fun finished(presentation: Presentation) {
        detach(presentation)
        if (current === presentation) current = null
        presentation.callbacks.onClosed()
    }

    private fun theme(activity: Activity, options: BrudcastOptions): InAppTheme {
        val isDark = when (options.appearance) {
            BrudcastAppearance.LIGHT -> false
            BrudcastAppearance.DARK -> true
            BrudcastAppearance.SYSTEM -> (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val accent = options.accentColor ?: run {
            val value = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.colorPrimary, value, true) && value.type >= TypedValue.TYPE_FIRST_COLOR_INT) value.data else null
        }
        return InAppTheme(accent?.let { Color(it) } ?: Color(0xFF3D5AFE), isDark)
    }

    private fun reducedMotion(activity: Activity): Boolean =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    @Composable
    private fun Root(presentation: Presentation, theme: InAppTheme, reducedMotion: Boolean) {
        val scheme = if (theme.isDark) darkColorScheme(primary = theme.accent) else lightColorScheme(primary = theme.accent)
        MaterialTheme(colorScheme = scheme) {
            val state = presentation.visible
            LaunchedEffect(state.isIdle, state.currentState) {
                if (state.isIdle && state.currentState && !presentation.impressed) {
                    presentation.impressed = true
                    presentation.callbacks.onImpression()
                }
                if (state.isIdle && !state.currentState && presentation.closing) {
                    finished(presentation)
                }
            }
            val onButton: (String?, InAppAction) -> Unit = { buttonId, action ->
                presentation.callbacks.onButton(buttonId, action)
                close(presentation)
            }
            val onDismiss: (BrudcastDismissReason) -> Unit = { reason ->
                if (!presentation.closing) {
                    presentation.callbacks.onDismiss(reason)
                    close(presentation)
                }
            }
            when (presentation.design.format) {
                InAppFormat.MODAL -> Modal(presentation.design, theme, state, reducedMotion, onButton, onDismiss)
                InAppFormat.BANNER -> Banner(presentation.design, theme, state, reducedMotion, onButton, onDismiss)
                InAppFormat.FULLSCREEN, InAppFormat.INBOX_ONLY -> Fullscreen(presentation.design, theme, state, reducedMotion, onButton, onDismiss)
            }
        }
    }

    @Composable
    private fun Modal(
        design: InAppDesign,
        theme: InAppTheme,
        state: MutableTransitionState<Boolean>,
        reducedMotion: Boolean,
        onButton: (String?, InAppAction) -> Unit,
        onDismiss: (BrudcastDismissReason) -> Unit,
    ) {
        val layout = design.layout
        BackHandler(enabled = true) { if (layout.dismissible) onDismiss(BrudcastDismissReason.BACK) }
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(state, enter = fadeIn(tween(if (reducedMotion) 150 else 200)), exit = fadeOut(tween(if (reducedMotion) 150 else 200))) {
                Box(
                    Modifier.fillMaxSize()
                        .background((InAppHexColor.parse(layout.overlayColor) ?: Color.Black).copy(alpha = layout.overlayOpacity.toFloat()))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (layout.dismissible) onDismiss(BrudcastDismissReason.OVERLAY_TAP)
                        },
                )
            }
            val alignment = when (layout.position) {
                InAppPosition.TOP -> Alignment.TopCenter
                InAppPosition.BOTTOM -> Alignment.BottomCenter
                InAppPosition.CENTER -> Alignment.Center
            }
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = alignment) {
                val maxCardHeight = maxHeight
                AnimatedVisibility(
                    state,
                    enter = if (reducedMotion) fadeIn(tween(150)) else fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.96f),
                    exit = if (reducedMotion) fadeOut(tween(150)) else fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.96f),
                ) {
                    val shape = RoundedCornerShape(layout.cornerRadius.dp)
                    Surface(
                        shape = shape,
                        shadowElevation = 12.dp,
                        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().heightIn(max = maxCardHeight)
                            .semantics { paneTitle = design.title ?: "Message" },
                    ) {
                        InAppBackground(layout, theme, MaterialTheme.colorScheme.surface) {
                            Box {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(layout.padding.dp)) {
                                    InAppBlockList(design.blocks, theme, onButton)
                                }
                                if (layout.dismissible) {
                                    InAppCloseButton({ onDismiss(BrudcastDismissReason.CLOSE_BUTTON) }, Modifier.align(Alignment.TopEnd).padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Banner(
        design: InAppDesign,
        theme: InAppTheme,
        state: MutableTransitionState<Boolean>,
        reducedMotion: Boolean,
        onButton: (String?, InAppAction) -> Unit,
        onDismiss: (BrudcastDismissReason) -> Unit,
    ) {
        val layout = design.layout
        val isTop = layout.position != InAppPosition.BOTTOM
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }
        layout.autoDismissSeconds?.let { seconds ->
            LaunchedEffect(state.currentState, dragging) {
                if (state.currentState && !dragging) {
                    kotlinx.coroutines.delay((seconds * 1_000).toLong())
                    onDismiss(BrudcastDismissReason.AUTO)
                }
            }
        }
        val image = design.blocks.firstOrNull { it is InAppBlock.Image } as? InAppBlock.Image
        val title = design.blocks.firstOrNull { it is InAppBlock.Text && it.role == InAppTextRole.TITLE } as? InAppBlock.Text
        val body = design.blocks.firstOrNull { it is InAppBlock.Text && it.role != InAppTextRole.TITLE } as? InAppBlock.Text
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                state,
                enter = if (reducedMotion) fadeIn(tween(150)) else slideInVertically(tween(300)) { if (isTop) -it * 2 else it * 2 },
                exit = if (reducedMotion) fadeOut(tween(150)) else slideOutVertically(tween(200)) { if (isTop) -it * 2 else it * 2 },
            ) {
                Surface(
                    shape = RoundedCornerShape(minOf(layout.cornerRadius, 20.0).dp),
                    shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .widthIn(max = 500.dp)
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .pointerInput(layout.dismissible) {
                            if (!layout.dismissible) return@pointerInput
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    dragging = false
                                    if ((isTop && dragOffset < -40f) || (!isTop && dragOffset > 40f)) onDismiss(BrudcastDismissReason.SWIPE) else dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragOffset = 0f
                                },
                            ) { _, amount ->
                                dragOffset = if (isTop) minOf(0f, dragOffset + amount) else maxOf(0f, dragOffset + amount)
                            }
                        }
                        .clickable(enabled = layout.bannerAction != null) { layout.bannerAction?.let { onButton(null, it) } },
                ) {
                    InAppBackground(layout, theme, MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            image?.let { InAppImage(it.url, it.alt, 8f, Modifier.size(48.dp)) }
                            Column(Modifier.weight(1f)) {
                                title?.let { InAppText(it, theme, maxLines = 1) }
                                body?.let { InAppText(it.copy(role = InAppTextRole.BODY), theme, maxLines = 2) }
                            }
                            if (layout.dismissible) InAppCloseButton({ onDismiss(BrudcastDismissReason.CLOSE_BUTTON) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Fullscreen(
        design: InAppDesign,
        theme: InAppTheme,
        state: MutableTransitionState<Boolean>,
        reducedMotion: Boolean,
        onButton: (String?, InAppAction) -> Unit,
        onDismiss: (BrudcastDismissReason) -> Unit,
    ) {
        val layout = design.layout
        BackHandler(enabled = true) { if (layout.dismissible) onDismiss(BrudcastDismissReason.BACK) }
        val split = splitTrailingButtons(design.blocks)
        AnimatedVisibility(
            state,
            enter = if (reducedMotion) fadeIn(tween(150)) else slideInVertically(tween(350)) { it },
            exit = if (reducedMotion) fadeOut(tween(150)) else slideOutVertically(tween(250)) { it },
        ) {
            InAppBackground(layout, theme, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize().semantics { paneTitle = design.title ?: "Message" }) {
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val available = maxHeight
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = available)
                            .padding(start = layout.padding.dp, end = layout.padding.dp, bottom = layout.padding.dp, top = layout.padding.dp + if (layout.dismissible) 32.dp else 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.widthIn(max = 600.dp)) { InAppBlockList(split.first, theme, onButton) }
                        if (split.second.isNotEmpty()) {
                            Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                            Box(Modifier.widthIn(max = 600.dp)) { InAppBlockList(split.second, theme, onButton) }
                        }
                    }
                    if (layout.dismissible) {
                        InAppCloseButton({ onDismiss(BrudcastDismissReason.CLOSE_BUTTON) }, Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        val presentation = current ?: return
        if (presentation.view == null && !presentation.closing) {
            if (!attach(presentation, activity, animate = false)) finished(presentation)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        val presentation = current ?: return
        if (presentation.activity?.get() === activity) {
            detach(presentation)
            if (!activity.isChangingConfigurations) {
                presentation.closing = true
                finished(presentation)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}

internal fun splitTrailingButtons(blocks: List<InAppBlock>): Pair<List<InAppBlock>, List<InAppBlock>> {
    var index = blocks.size
    while (index > 0 && blocks[index - 1] is InAppBlock.Button) index -= 1
    return blocks.subList(0, index) to blocks.subList(index, blocks.size)
}
