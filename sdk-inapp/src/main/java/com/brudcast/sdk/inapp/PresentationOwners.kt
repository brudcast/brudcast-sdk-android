package com.brudcast.sdk.inapp

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The lifecycle, saved state, back dispatcher and recomposer a Compose view needs, for a view the
 * SDK puts in an activity that provides none, such as Flutter's `FlutterActivity`. They live as long
 * as the view is attached.
 *
 * The recomposer is the view's own because Compose looks for the window's recomposer, and the
 * lifecycle it follows, on the window's root view or the activity's content view, never on the
 * view's own ancestors.
 */
internal class PresentationOwners(private val activity: Activity?) : LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    private var recomposer: Recomposer? = null
    private var recomposeScope: CoroutineScope? = null

    /**
     * Installs the owners on a view, gives its Compose content a recomposer of its own, and moves
     * them to resumed. On Android 13 and later the back dispatcher also receives the system's back
     * callbacks while the view handles back. Must be called on the main thread.
     *
     * @param view The view, before it is attached.
     * @param content The Compose view inside it, before it is attached.
     */
    fun install(view: View, content: AbstractComposeView) {
        savedState.performAttach()
        savedState.performRestore(null)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeOnBackPressedDispatcherOwner(this)
        if (Build.VERSION.SDK_INT >= 33 && activity != null) {
            onBackPressedDispatcher.setOnBackInvokedDispatcher(activity.onBackInvokedDispatcher)
        }
        val context = AndroidUiDispatcher.CurrentThread
        val created = Recomposer(context)
        val scope = CoroutineScope(context)
        scope.launch { created.runRecomposeAndApplyChanges() }
        recomposer = created
        recomposeScope = scope
        content.setParentCompositionContext(created)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Sends a back press to the view's handlers.
     *
     * @return True when one of them took it.
     */
    fun handleBack(): Boolean {
        if (!onBackPressedDispatcher.hasEnabledCallbacks()) return false
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Removes the owners from a view, so it finds its window's owners when attached again.
     *
     * @param view The view [install] was given.
     * @param content The Compose view [install] was given.
     */
    fun uninstall(view: View, content: AbstractComposeView) {
        view.setViewTreeLifecycleOwner(null)
        view.setViewTreeSavedStateRegistryOwner(null)
        view.setTag(androidx.activity.R.id.view_tree_on_back_pressed_dispatcher_owner, null)
        content.setParentCompositionContext(null)
    }

    /** Ends the owners' lifecycle and stops the recomposer once the view is gone. */
    fun destroy() {
        recomposer?.cancel()
        recomposeScope?.cancel()
        recomposer = null
        recomposeScope = null
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
