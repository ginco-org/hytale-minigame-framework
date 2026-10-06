package gg.ginco.minigame.hytaletest

import gg.ginco.hytest.runtime.HytestPlugin
import gg.ginco.hytest.runtime.api.HytaleTestHelper

/**
 * Live-handler twin of `HytaleTestHelper.captureEvents`, living here rather
 * than in hytest so this suite builds against any PUBLISHED hytest checkout:
 * the region-events and behaviors tests act on crossings synchronously on
 * the world thread (eliminate a player, win a game) — behaviour a capture
 * list polled after the fact cannot express.
 *
 * hytest@main ships `captureEvents` only; the `onEvent` member these tests
 * were written against exists solely as an uncommitted working-tree edit in
 * the local hytest checkout, which is why CI (compiling hytest from a clean
 * sibling checkout) fails to resolve it. The extension is built from
 * hytest@main's public API — the same `HytestPlugin.EVENTS` registry and
 * done-future cleanup `captureEvents` uses — so it compiles and runs against
 * committed hytest. Once hytest ships a member `onEvent`, the member shadows
 * this extension at every call site; delete this file then.
 */
fun <E : Any> HytaleTestHelper.onEvent(eventClass: Class<E>, handler: (E) -> Unit) {
    val registration = HytestPlugin.EVENTS!!
        .register(eventClass) { event -> handler(event) }!!
    doneFuture().whenComplete { _, _ -> runCatching { registration.unregister() } }
}
