package com.brudcast.sample

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Turns the sample's `brudcast-sample:` links into screens, whether they come from an in-app
 * button, a notification tap or the system. A campaign button set to `brudcast-sample://checkout`
 * opens checkout.
 */
object SampleRouter {
    /** A screen a link can open. */
    enum class Destination { SHOP, CHECKOUT }

    private val pending = Channel<Destination>(Channel.CONFLATED)

    /** Destinations to open, each delivered once. */
    val destinations: Flow<Destination> = pending.receiveAsFlow()

    /**
     * Opens the screen a link names.
     *
     * @param link A `brudcast-sample:` link.
     * @return Whether the link names a screen.
     */
    fun open(link: Uri): Boolean {
        val destination = when (link.host) {
            "shop" -> Destination.SHOP
            "checkout" -> Destination.CHECKOUT
            else -> return false
        }
        pending.trySend(destination)
        return true
    }
}
