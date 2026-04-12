package com.reverb.server

import com.reverb.model.NotificationPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NotificationStore {
    private const val MAX_SIZE = 200
    private val mutex = Mutex()
    private val store = ArrayDeque<NotificationPayload>(MAX_SIZE)

    suspend fun push(payload: NotificationPayload) = mutex.withLock {
        // 중복 제거 (같은 id가 재연결 시 다시 올 수 있음)
        store.removeIf { it.id == payload.id }
        store.addLast(payload)
        if (store.size > MAX_SIZE) store.removeFirst()
    }

    suspend fun getAll(): List<NotificationPayload> = mutex.withLock {
        store.toList()
    }

    suspend fun dismiss(id: String) = mutex.withLock {
        store.removeIf { it.id == id }
    }

    suspend fun clear() = mutex.withLock {
        store.clear()
    }
}
