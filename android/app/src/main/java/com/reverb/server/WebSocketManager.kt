package com.reverb.server

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

object WebSocketManager {
    private val sessions = CopyOnWriteArrayList<DefaultWebSocketServerSession>()

    // 연결된 브라우저 수 (MainActivity에서 표시용)
    val sessionCount: Int get() = sessions.size

    fun addSession(session: DefaultWebSocketServerSession) {
        sessions.add(session)
    }

    fun removeSession(session: DefaultWebSocketServerSession) {
        sessions.remove(session)
    }

    fun broadcast(message: String) {
        val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
        sessions.forEach { session ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(message))
                } catch (e: Exception) {
                    deadSessions.add(session)
                }
            }
        }
        sessions.removeAll(deadSessions.toSet())
    }
}
