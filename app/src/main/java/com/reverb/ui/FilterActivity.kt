package com.reverb.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.reverb.R
import com.reverb.server.FilterEngine

class FilterActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var rgMode: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)

        supportActionBar?.apply {
            title = "앱 필터 관리"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.rvApps)
        rgMode = findViewById(R.id.rgMode)

        val config = FilterEngine.getConfig(this)

        // 모드 선택 라디오 버튼
        rgMode.check(
            if (config.mode == "whitelist") R.id.rbWhitelist else R.id.rbBlacklist
        )
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbWhitelist) "whitelist" else "blacklist"
            FilterEngine.setMode(this, mode)
        }

        // 설치된 앱 목록 (사용자 앱만)
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = AppFilterAdapter(apps, config.packages.toMutableSet())
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    inner class AppFilterAdapter(
        private val apps: List<ApplicationInfo>,
        private val selectedPackages: MutableSet<String>
    ) : RecyclerView.Adapter<AppFilterAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val swToggle: Switch = view.findViewById(R.id.swFilter)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_filter, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.tvName.text = packageManager.getApplicationLabel(app).toString()
            holder.tvPackage.text = app.packageName
            holder.swToggle.setOnCheckedChangeListener(null)
            holder.swToggle.isChecked = app.packageName in selectedPackages

            holder.swToggle.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (isChecked) {
                    selectedPackages.add(app.packageName)
                    FilterEngine.addPackage(this@FilterActivity, app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                    FilterEngine.removePackage(this@FilterActivity, app.packageName)
                }
            }
        }

        override fun getItemCount() = apps.size
    }
}
