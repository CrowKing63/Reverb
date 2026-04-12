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
        android.util.Log.i("WebSocketManager", "세션 추가 - 현재 세션 수: ${sessions.size}")
    }

    fun removeSession(session: DefaultWebSocketServerSession) {
        sessions.remove(session)
        android.util.Log.i("WebSocketManager", "세션 제거 - 현재 세션 수: ${sessions.size}")
    }

    fun broadcast(message: String) {
        android.util.Log.i("WebSocketManager", "브로드캐스트 시작 - 세션 수: ${sessions.size}")
        android.util.Log.i("WebSocketManager", "브로드캐스트 메시지: ${message.take(100)}...")
        
        val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
        sessions.forEach { session ->
            try {
                android.util.Log.i("WebSocketManager", "세션에 전송 시도...")
                // CoroutineScope를 사용하지 않고 직접 호출 (ReverbServer에서 이미 CoroutineScope.launch로 호출하므로)
                kotlinx.coroutines.runBlocking {
                    session.send(Frame.Text(message))
                }
                android.util.Log.i("WebSocketManager", "세션 전송 성공")
            } catch (e: Exception) {
                android.util.Log.e("WebSocketManager", "세션 전송 실패: ${e.message}", e)
                deadSessions.add(session)
            }
        }
        sessions.removeAll(deadSessions.toSet())
        if (deadSessions.isNotEmpty()) {
            android.util.Log.w("WebSocketManager", "죽은 세션 ${deadSessions.size}개 제거")
        }
    }
}
