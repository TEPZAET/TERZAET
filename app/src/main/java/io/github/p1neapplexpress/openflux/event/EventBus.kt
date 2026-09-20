package io.github.p1neapplexpress.openflux.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private var lastLog = ""
    private var lastLogAt = 0L
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun dispatch(event: AppEvent) {
        if (event !is AppEvent.LogMessage) {
            _events.tryEmit(event)
            return
        }
        val safe = SafeLog.message(event.message)
        val now = System.currentTimeMillis()
        if (safe == lastLog && now - lastLogAt < 5_000L) return
        lastLog = safe
        lastLogAt = now
        _events.tryEmit(AppEvent.LogMessage(safe))
    }
}
