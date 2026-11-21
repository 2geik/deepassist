package com.deepassist.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The next element, closed, or null after [timeoutMs] — without losing an element.
 *
 * `withTimeoutOrNull { receiveCatching() }` can drop the element that arrives just as
 * the timeout fires: the receive completes, then the cancellation throws it away.
 * On 21 November that dropped Meta's session ack, and the turn fell back to Android's
 * recognizer as if Meta hadn't answered. A select with onTimeout is all-or-nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <E> ReceiveChannel<E>.receiveCatchingOrNull(timeoutMs: Long): ChannelResult<E>? = select {
    onReceiveCatching { it }
    onTimeout(timeoutMs) { null }
}
