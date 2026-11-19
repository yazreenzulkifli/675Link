package dev.zanderp.opencfmoto.aa

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether Android Auto's Google Maps is guiding a route, as reported over the AAP navigation-status
 * channel. Drives the home-screen "Destination: Set / Not set" card.
 */
object AaNavState {
    @Volatile var active: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun set(value: Boolean) {
        if (active == value) return
        active = value
        for (l in listeners) try { l() } catch (_: Exception) {}
    }
}
