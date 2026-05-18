package com.reverb.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.reverb.R
import com.reverb.model.FilterConfig
import com.reverb.server.FilterEngine
import java.util.Locale

class FilterActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var rgMode: RadioGroup
    private lateinit var tvModeDescription: TextView
    private lateinit var tvSelectionCount: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var searchField: TextView

    private lateinit var adapter: AppFilterAdapter
    private lateinit var allApps: List<ApplicationInfo>
    private val selectedPackages = linkedSetOf<String>()
    private var currentMode: String = "blacklist"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)

        supportActionBar?.apply {
            title = getString(R.string.filter_title)
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.rvApps)
        rgMode = findViewById(R.id.rgMode)
        tvModeDescription = findViewById(R.id.tvModeDescription)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        searchField = findViewById(R.id.etSearch)

        val config = FilterEngine.getConfig(this)
        currentMode = config.mode
        selectedPackages.clear()
        selectedPackages.addAll(config.packages)

        allApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }

        adapter = AppFilterAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        rgMode.check(if (currentMode == "whitelist") R.id.rbWhitelist else R.id.rbBlacklist)
        updateModeDescription()
        updateSelectionCount()
        adapter.submitQuery("")

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            currentMode = if (checkedId == R.id.rbWhitelist) "whitelist" else "blacklist"
            FilterEngine.setMode(this, currentMode)
            updateModeDescription()
        }

        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            selectedPackages.clear()
            persistSelection()
            adapter.notifyDataSetChanged()
            updateSelectionCount()
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.submitQuery(s?.toString().orEmpty())
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun updateModeDescription() {
        tvModeDescription.text = getString(
            if (currentMode == "whitelist") {
                R.string.filter_mode_whitelist_desc
            } else {
                R.string.filter_mode_blacklist_desc
            }
        )
    }

    private fun updateSelectionCount() {
        tvSelectionCount.text = getString(R.string.filter_selection_count, selectedPackages.size)
    }

    private fun persistSelection() {
        FilterEngine.setConfig(this, FilterConfig(mode = currentMode, packages = selectedPackages.toList().sorted()))
    }

    inner class AppFilterAdapter : RecyclerView.Adapter<AppFilterAdapter.ViewHolder>() {
        private val filteredApps = mutableListOf<ApplicationInfo>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val tvSelectionBadge: TextView = view.findViewById(R.id.tvSelectionBadge)
            val swToggle: SwitchCompat = view.findViewById(R.id.swFilter)
        }

        fun submitQuery(query: String) {
            val normalized = query.trim().lowercase(Locale.getDefault())
            filteredApps.clear()
            filteredApps.addAll(
                allApps.filter { app ->
                    if (normalized.isBlank()) {
                        true
                    } else {
                        val label = packageManager.getApplicationLabel(app).toString()
                        label.lowercase(Locale.getDefault()).contains(normalized) ||
                            app.packageName.lowercase(Locale.getDefault()).contains(normalized)
                    }
                }
            )
            notifyDataSetChanged()
            val hasItems = filteredApps.isNotEmpty()
            recyclerView.visibility = if (hasItems) View.VISIBLE else View.GONE
            tvEmptyState.visibility = if (hasItems) View.GONE else View.VISIBLE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_filter, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            val appName = packageManager.getApplicationLabel(app).toString()
            val isSelected = app.packageName in selectedPackages

            holder.tvName.text = appName
            holder.tvPackage.text = app.packageName
            holder.tvSelectionBadge.text = getString(
                if (isSelected) R.string.filter_selected else R.string.filter_not_selected
            )
            holder.tvSelectionBadge.background = buildBadgeBackground(
                if (isSelected) "#20304A" else "#2B313D"
            )
            holder.swToggle.setOnCheckedChangeListener(null)
            holder.swToggle.isChecked = isSelected
            holder.swToggle.contentDescription = getString(R.string.filter_toggle_label, appName)

            holder.swToggle.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                persistSelection()
                notifyItemChanged(position)
                updateSelectionCount()
            }

            holder.itemView.setOnClickListener {
                holder.swToggle.isChecked = !holder.swToggle.isChecked
            }
        }

        override fun getItemCount(): Int = filteredApps.size
    }

    private fun buildBadgeBackground(color: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(Color.parseColor(color))
        }
    }
}
