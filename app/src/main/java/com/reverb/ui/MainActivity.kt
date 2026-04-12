package com.reverb.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.reverb.R
import com.reverb.model.NotificationPayload
import com.reverb.server.NotificationStore
import com.reverb.server.ReverbServer
import com.reverb.server.WebSocketManager
import com.reverb.util.IpHelper
import com.reverb.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnections: TextView
    private lateinit var btnGrantPermission: View
    private lateinit var btnTestNotification: View
    private lateinit var btnManageFilters: View
    private lateinit var btnResetToken: View

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            tvUrl.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvUrl = findViewById(R.id.tvUrl)
        tvToken = findViewById(R.id.tvToken)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnections = findViewById(R.id.tvConnections)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnTestNotification = findViewById(R.id.btnTestNotification)
        btnManageFilters = findViewById(R.id.btnManageFilters)
        btnResetToken = findViewById(R.id.btnResetToken)

        btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnTestNotification.setOnClickListener {
            sendTestNotification()
        }

        btnManageFilters.setOnClickListener {
            startActivity(Intent(this, FilterActivity::class.java))
        }

        btnResetToken.setOnClickListener {
            TokenManager.resetToken(this)
            updateStatus()
        }

        requestBatteryOptimizationExclusion()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        tvUrl.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        tvUrl.removeCallbacks(updateRunnable)
    }

    private fun updateStatus() {
        val isListenerEnabled = isNotificationListenerEnabled()

        if (isListenerEnabled) {
            btnGrantPermission.visibility = View.GONE
            tvStatus.text = "✅ 서버 실행 중"

            val ip = IpHelper.getWifiIp(this)
            val token = TokenManager.getToken(this)
            val url = "http://$ip:${ReverbServer.PORT}"

            tvUrl.text = url
            tvToken.text = "🔑 토큰: $token"
            tvConnections.text = "📱 연결된 기기: ${WebSocketManager.sessionCount}개"
        } else {
            btnGrantPermission.visibility = View.VISIBLE
            tvStatus.text = "⚠️ 알림 접근 권한 필요\n설정에서 Reverb를 활성화해주세요"
            tvUrl.text = "-"
            tvToken.text = ""
            tvConnections.text = ""
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        // 1. PackageManager에서 활성화된 확인
        val isPackageEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        if (!isPackageEnabled) return false

        // 2. 서비스가 실제로 바인딩되어 있는지 확인 (추가 체크)
        val serviceName = ComponentName(this, "${packageName}.service.NotificationService")
        val pm = packageManager
        val serviceInfo = pm.getServiceInfo(serviceName, 0)
        
        return serviceInfo != null
    }

    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun sendTestNotification() {
        android.util.Log.i("MainActivity", "=== 테스트 알림 시작 ===")
        android.util.Log.i("MainActivity", "WebSocket 세션 수: ${WebSocketManager.sessionCount}")

        // 1. 실제 Android 시스템 알림 생성 (NotificationService가 감지할 수 있도록)
        try {
            createTestSystemNotification()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "시스템 알림 생성 실패 (무시하고 계속)", e)
        }

        // 2. 직접 WebSocket으로도 전송 (테스트용)
        val testPayload = NotificationPayload(
            id = "direct_" + UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            packageName = "com.reverb.test",
            appLabel = "테스트 알림 (직접)",
            category = "generic",
            title = "🧪 직접 전송 테스트",
            body = "이 알림이 웹에 보인다면 WebSocket 브로드캐스트는 정상입니다!",
            ticker = null,
            conversationId = null,
            actions = emptyList(),
            isGroupSummary = false,
            priority = 0,
            sbnKey = "test_direct_${System.currentTimeMillis()}"
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationStore.push(testPayload)
                android.util.Log.i("MainActivity", "NotificationStore.push 완료")
                
                ReverbServer.broadcastNotification(testPayload)
                android.util.Log.i("MainActivity", "broadcastNotification 완료")
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity, 
                        "✅ 테스트 알림 전송 성공 (세션: ${WebSocketManager.sessionCount}개)", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "테스트 알림 전송 실패", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity, 
                        "❌ 전송 실패: ${e.message}", 
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun createTestSystemNotification() {
        try {
            val channelId = "reverb_test_channel"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 채널 생성
            val channel = NotificationChannel(
                channelId,
                "Reverb 테스트 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "테스트용 알림 채널"
            }
            notificationManager.createNotificationChannel(channel)

            // 실제 시스템 알림 생성 (NotificationCompat 사용)
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("🧪 시스템 알림 테스트")
                .setContentText("이 알림이 NotificationService를 통해 웹으로 전송되어야 합니다")
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val notificationId = 9999
            notificationManager.notify(notificationId, notification)
            android.util.Log.i("MainActivity", "시스템 알림 생성 완료 (ID: $notificationId)")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "시스템 알림 생성 중 오류", e)
            throw e
        }
    }

}
