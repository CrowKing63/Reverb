package com.reverb.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.reverb.R
import com.reverb.server.ReverbServer
import com.reverb.server.WebSocketManager
import com.reverb.util.IpHelper
import com.reverb.util.TokenManager

class MainActivity : AppCompatActivity() {

    private lateinit var tvUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnections: TextView
    private lateinit var btnGrantPermission: View
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
        btnManageFilters = findViewById(R.id.btnManageFilters)
        btnResetToken = findViewById(R.id.btnResetToken)

        btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
        val isListenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        if (isListenerEnabled) {
            btnGrantPermission.visibility = View.GONE
            tvStatus.text = "서버 실행 중"

            val ip = IpHelper.getWifiIp(this)
            val token = TokenManager.getToken(this)
            val url = "http://$ip:${ReverbServer.PORT}"

            tvUrl.text = url
            tvToken.text = "토큰: $token"
            tvConnections.text = "연결된 기기: ${WebSocketManager.sessionCount}개"
        } else {
            btnGrantPermission.visibility = View.VISIBLE
            tvStatus.text = "알림 접근 권한 필요"
            tvUrl.text = "-"
            tvToken.text = ""
            tvConnections.text = ""
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

}
