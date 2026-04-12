package com.reverb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.reverb.R
import com.reverb.model.NotificationPayload
import com.reverb.server.FilterEngine
import com.reverb.server.NotificationStore
import com.reverb.server.ReverbServer
import com.reverb.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class NotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "reverb_service"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("NotificationService", "onListenerConnected - 알림 접근 권한이 정상적으로 부여되었습니다")
        createNotificationChannel()
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        } catch (e: Exception) {
            android.util.Log.e("NotificationService", "startForeground failed", e)
        }
        registerBatteryReceiver()
        try {
            ReverbServer.start(this)
        } catch (e: Exception) {
            android.util.Log.e("NotificationService", "ReverbServer.start failed", e)
        }
        startHeartbeat()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        android.util.Log.w("NotificationService", "onListenerDisconnected - 알림 접근 권한이 해제되었습니다")
        stopHeartbeat()
        unregisterBatteryReceiver()
        ReverbServer.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 그룹 요약 알림은 건너뜀 (내용이 없음)
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            android.util.Log.d("NotificationService", "그룹 요약 알림 건너뜀: ${sbn.packageName}")
            return
        }

        // 필터 체크
        if (!FilterEngine.isAllowed(this, sbn.packageName)) {
            android.util.Log.d("NotificationService", "필터링됨: ${sbn.packageName}")
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val ticker = sbn.notification.tickerText?.toString()

        // 앱 레이블
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        // 카테고리 분류
        val category = when (sbn.notification.category) {
            Notification.CATEGORY_MESSAGE -> "sms"
            Notification.CATEGORY_CALL -> "call"
            Notification.CATEGORY_TRANSPORT -> "media"
            else -> "generic"
        }

        // actions 및 conversationId 추출
        val actions = mutableListOf<String>()
        var conversationId: String? = null
        var replyAction: Notification.Action? = null

        sbn.notification.actions?.forEach { action ->
            actions.add(action.title?.toString() ?: "")
            // RemoteInput이 있는 Action = Reply 버튼
            if (action.remoteInputs?.isNotEmpty() == true && replyAction == null) {
                replyAction = action
                // conversationId: RemoteInput의 resultKey 또는 extras에서 유추
                conversationId = extractConversationId(sbn, action)
            }
        }

        // Reply Action 캐싱
        if (replyAction != null && conversationId != null) {
            SmsReplyManager.cacheReplyAction(conversationId!!, replyAction!!)
        }

        val payload = NotificationPayload(
            id = UUID.randomUUID().toString(),
            timestamp = sbn.postTime,
            packageName = sbn.packageName,
            appLabel = appLabel,
            category = category,
            title = title,
            body = body,
            ticker = ticker,
            conversationId = conversationId,
            actions = actions,
            isGroupSummary = isGroupSummary,
            priority = sbn.notification.priority,
            sbnKey = sbn.key
        )

        android.util.Log.i("NotificationService", "=== 알림 수신 ===")
        android.util.Log.i("NotificationService", "앱: ${payload.appLabel}, 제목: ${payload.title}")
        android.util.Log.i("NotificationService", "내용: ${payload.body}")
        android.util.Log.i("NotificationService", "WebSocket 세션 수: ${com.reverb.server.WebSocketManager.sessionCount}")

        serviceScope.launch {
            try {
                NotificationStore.push(payload)
                android.util.Log.i("NotificationService", "NotificationStore.push 완료")
                
                ReverbServer.broadcastNotification(payload)
                android.util.Log.i("NotificationService", "broadcastNotification 완료")
                android.util.Log.i("NotificationService", "알림 웹 소켓 브로드캐스트 완료: ${payload.id}")
            } catch (e: Exception) {
                android.util.Log.e("NotificationService", "알림 처리 중 오류", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Phase 2: dismiss 동기화 — sbnKey로 브라우저에 dismiss 이벤트 전송 예정
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun extractConversationId(sbn: StatusBarNotification, replyAction: Notification.Action): String? {
        // 1. extras에서 직접 전화번호/주소 찾기
        val extras = sbn.notification.extras
        val address = extras.getString("address")
            ?: extras.getString("com.google.android.apps.messaging.EXTRA_FROM")
        if (!address.isNullOrBlank()) return address

        // 2. RemoteInput resultKey를 conversationId로 사용 (앱별 고유 스레드 식별자)
        return replyAction.remoteInputs?.firstOrNull()?.resultKey
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(30_000)
                updateBatteryStatus()
                ReverbServer.broadcastStatus()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                ReverbServer.batteryLevel = if (scale > 0) (level * 100 / scale) else -1
                ReverbServer.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateBatteryStatus()
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    private fun updateBatteryStatus() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        ReverbServer.batteryLevel = if (scale > 0) (level * 100 / scale) else -1
        ReverbServer.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
            || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "Reverb 서비스",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "알림 미러링 서비스 실행 중"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Reverb 실행 중")
            .setContentText("Vision Pro 연결 대기 중...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
