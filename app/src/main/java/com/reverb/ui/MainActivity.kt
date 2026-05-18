package com.reverb.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvStatusSummary: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvConnections: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvPowerStatus: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var btnGrantPermission: Button
    private lateinit var btnManageFilters: Button
    private lateinit var btnTestNotification: Button
    private lateinit var btnResetToken: Button
    private lateinit var btnCopyUrl: Button
    private lateinit var btnCopyToken: Button

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            tvUrl.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvStatusSummary = findViewById(R.id.tvStatusSummary)
        tvUrl = findViewById(R.id.tvUrl)
        tvToken = findViewById(R.id.tvToken)
        tvConnections = findViewById(R.id.tvConnections)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvPowerStatus = findViewById(R.id.tvPowerStatus)
        ivQrCode = findViewById(R.id.ivQrCode)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnManageFilters = findViewById(R.id.btnManageFilters)
        btnTestNotification = findViewById(R.id.btnTestNotification)
        btnResetToken = findViewById(R.id.btnResetToken)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        btnCopyToken = findViewById(R.id.btnCopyToken)

        btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnManageFilters.setOnClickListener {
            startActivity(Intent(this, FilterActivity::class.java))
        }

        btnTestNotification.setOnClickListener {
            sendTestNotification()
        }

        btnResetToken.setOnClickListener {
            TokenManager.resetToken(this)
            updateStatus()
            Toast.makeText(this, getString(R.string.toast_token_reset), Toast.LENGTH_SHORT).show()
        }

        btnCopyUrl.setOnClickListener {
            copyValue(tvUrl.text.toString(), getString(R.string.copy_url_label), R.string.toast_url_copied)
        }

        btnCopyToken.setOnClickListener {
            val rawToken = TokenManager.getToken(this)
            copyValue(rawToken, getString(R.string.copy_token_label), R.string.toast_token_copied)
        }

        tvPowerStatus.setOnClickListener {
            requestBatteryOptimizationExclusion()
        }
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
        val token = TokenManager.getToken(this)
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryIgnored = powerManager.isIgnoringBatteryOptimizations(packageName)

        tvPermissionStatus.text = getString(
            if (isListenerEnabled) R.string.value_permission_enabled else R.string.value_permission_disabled
        )
        tvPowerStatus.text = getString(
            if (batteryIgnored) R.string.value_battery_ignored else R.string.value_battery_not_ignored
        )
        tvConnections.text = getString(R.string.connections_count, WebSocketManager.sessionCount)
        tvToken.text = getString(R.string.token_value, token)

        if (isListenerEnabled) {
            val ip = IpHelper.getWifiIp(this)
            val url = "http://$ip:${ReverbServer.PORT}"
            tvStatusBadge.text = getString(R.string.status_running)
            tvStatusBadge.background = buildBadgeBackground("#1F5C3F")
            tvStatusBadge.setTextColor(Color.parseColor("#D9FFE7"))
            tvStatusSummary.text = getString(R.string.status_summary_ready)
            tvUrl.text = url
            renderQrCode("$url/?token=$token")
            btnCopyUrl.isEnabled = true
        } else {
            tvStatusBadge.text = getString(R.string.status_waiting_permission)
            tvStatusBadge.background = buildBadgeBackground("#5E3322")
            tvStatusBadge.setTextColor(Color.parseColor("#FFE3D7"))
            tvStatusSummary.text = getString(R.string.status_summary_permission)
            tvUrl.text = getString(R.string.value_no_url)
            ivQrCode.setImageResource(R.drawable.ic_launcher_foreground)
            btnCopyUrl.isEnabled = false
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val isPackageEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        if (!isPackageEnabled) return false

        val serviceName = ComponentName(this, "${packageName}.service.NotificationService")
        return runCatching { packageManager.getServiceInfo(serviceName, 0) }.isSuccess
    }

    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun copyValue(value: String, label: String, successMessageRes: Int) {
        if (value.isBlank() || value == getString(R.string.value_no_url)) {
            Toast.makeText(this, getString(R.string.toast_copy_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(successMessageRes), Toast.LENGTH_SHORT).show()
    }

    private fun buildBadgeBackground(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor(color))
        }
    }

    private fun renderQrCode(content: String) {
        runCatching {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 720, 720)
            val width = matrix.width
            val height = matrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            ivQrCode.setImageBitmap(bitmap)
        }.onFailure {
            ivQrCode.setImageResource(R.drawable.ic_launcher_foreground)
            Toast.makeText(this, getString(R.string.toast_qr_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTestNotification() {
        createTestSystemNotification()

        val testPayload = NotificationPayload(
            id = "direct_" + UUID.randomUUID(),
            timestamp = System.currentTimeMillis(),
            packageName = "com.reverb.test",
            appLabel = "Reverb 테스트",
            category = "generic",
            title = "대시보드 테스트 알림",
            body = "웹 알림 센터와 연결 상태를 빠르게 확인할 수 있습니다.",
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
                ReverbServer.broadcastNotification(testPayload)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_test_sent),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_test_failed, e.message ?: "unknown"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun createTestSystemNotification() {
        val channelId = "reverb_test_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Reverb 테스트 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "대시보드에서 보내는 테스트 알림"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Reverb 테스트 알림")
            .setContentText("실제 알림 수신과 웹 미러링 경로를 확인합니다.")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(9999, notification)
    }
}
