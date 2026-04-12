package com.reverb.server

import android.content.Context
import android.media.RingtoneManager
import com.reverb.model.FilterConfig
import com.reverb.model.ReplyRequest
import com.reverb.model.ReplyResponse
import com.reverb.model.SnapshotMessage
import com.reverb.model.StatusMessage
import com.reverb.service.SmsReplyManager
import com.reverb.util.IpHelper
import com.reverb.util.TokenManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration

object ReverbServer {
    const val PORT = 8765
    private var engine: ApplicationEngine? = null
    private val json = Json { ignoreUnknownKeys = true }

    // 현재 배터리/기기 상태 (NotificationService에서 업데이트)
    var deviceName: String = android.os.Build.MODEL
    var batteryLevel: Int = -1
    var batteryCharging: Boolean = false

    fun start(context: Context) {
        if (engine != null) return
        val token = TokenManager.getToken(context)

        engine = embeddedServer(CIO, port = PORT) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(20)
                timeout = Duration.ofSeconds(60)
            }
            install(ContentNegotiation) { json(json) }
            install(CORS) { anyHost() }

            routing {
                // ── 웹 UI 서빙 (APK assets/web/) ──────────────────────────
                get("/") { serveAsset(context, call, "web/index.html", ContentType.Text.Html) }
                get("/style.css") { serveAsset(context, call, "web/style.css", ContentType.Text.CSS) }
                get("/app.js") { serveAsset(context, call, "web/app.js", ContentType.Text.JavaScript) }

                // ── WebSocket ─────────────────────────────────────────────
                webSocket("/ws") {
                    val queryToken = call.request.queryParameters["token"]
                    val clientIp = call.request.local.remoteAddress
                    if (!isLocalIp(clientIp) && queryToken != token) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }

                    WebSocketManager.addSession(this)

                    // 최초 연결 시 snapshot 전송
                    val snapshot = SnapshotMessage(
                        deviceName = deviceName,
                        batteryLevel = batteryLevel,
                        batteryCharging = batteryCharging,
                        notifications = NotificationStore.getAll()
                    )
                    send(Frame.Text(json.encodeToString(snapshot)))

                    try {
                        for (frame in incoming) {
                            // 브라우저→서버 메시지는 현재 REST로만 처리
                        }
                    } finally {
                        WebSocketManager.removeSession(this)
                    }
                }

                // ── REST API ──────────────────────────────────────────────

                // 답장 발송
                post("/api/reply") {
                    val req = call.receive<ReplyRequest>()
                    val result = SmsReplyManager.sendReply(context, req.conversationId, req.replyBody)
                    if (result.isSuccess) {
                        call.respond(ReplyResponse(success = true))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ReplyResponse(success = false, error = result.exceptionOrNull()?.message)
                        )
                    }
                }

                // 벨 울리기 (기기 찾기)
                post("/api/ring") {
                    try {
                        val ringtone = RingtoneManager.getRingtone(
                            context,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        )
                        ringtone.play()
                        CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(5000)
                            if (ringtone.isPlaying) ringtone.stop()
                        }
                        call.respond(mapOf("success" to true))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                    }
                }

                // 필터 조회
                get("/api/filters") {
                    call.respond(FilterEngine.getConfig(context))
                }

                // 필터 업데이트
                post("/api/filters") {
                    val config = call.receive<FilterConfig>()
                    FilterEngine.setConfig(context, config)
                    call.respond(FilterEngine.getConfig(context))
                }

                // 서버 상태 조회
                get("/api/status") {
                    call.respond(StatusMessage(
                        deviceName = deviceName,
                        batteryLevel = batteryLevel,
                        batteryCharging = batteryCharging
                    ))
                }
            }
        }
        engine!!.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    fun broadcastStatus() {
        val msg = json.encodeToString(StatusMessage(
            deviceName = deviceName,
            batteryLevel = batteryLevel,
            batteryCharging = batteryCharging
        ))
        WebSocketManager.broadcast(msg)
    }

    fun broadcastNotification(payload: com.reverb.model.NotificationPayload) {
        WebSocketManager.broadcast(json.encodeToString(payload))
    }

    private fun isLocalIp(ip: String): Boolean {
        return ip == "127.0.0.1" || ip == "::1" ||
               ip.startsWith("192.168.") ||
               ip.startsWith("10.") ||
               Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
    }

    private suspend fun serveAsset(
        context: Context,
        call: ApplicationCall,
        path: String,
        contentType: ContentType
    ) {
        try {
            val bytes = context.assets.open(path).readBytes()
            call.respondBytes(bytes, contentType)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.NotFound, "Not found: $path")
        }
    }
}
