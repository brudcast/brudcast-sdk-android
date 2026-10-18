package com.brudcast.sdk.inapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * An inbox entry drawn with the standard renderer, returned by [BrudcastInbox.render]. It scrolls
 * when it is shorter than its design, and reports the height the design needs at its width.
 */
internal class InboxMessageView(
    context: Context,
    design: InAppDesign,
    theme: InAppTheme,
    onButton: (String?, InAppAction) -> Unit,
    private val onContentHeightChanged: ((Int) -> Unit)?,
) : FrameLayout(context) {
    private var owners: PresentationOwners? = null
    private var reportedHeight = -1

    private val content: ComposeView

    init {
        content = ComposeView(context).apply {
            setContent {
                MaterialTheme(colorScheme = inAppColorScheme(theme)) {
                    InAppBackground(design.layout, theme, MaterialTheme.colorScheme.surface, Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Column(Modifier.onSizeChanged { report(it.height) }.padding(design.layout.padding.dp)) {
                                InAppBlockList(design.blocks, theme, onButton)
                            }
                        }
                    }
                }
            }
        }
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        if ((parent as? View)?.findViewTreeLifecycleOwner() == null) {
            owners = PresentationOwners(activity(context)).also { it.install(this, content) }
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        owners?.let {
            it.destroy()
            it.uninstall(this, content)
        }
        owners = null
    }

    private fun report(height: Int) {
        if (height == reportedHeight) return
        reportedHeight = height
        onContentHeightChanged?.invoke(height)
    }

    companion object {
        /**
         * Finds the activity behind a context, through any wrappers.
         *
         * @param context The context.
         * @return The activity, or null when the context has none.
         */
        fun activity(context: Context): Activity? {
            var current: Context? = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return null
        }
    }
}
