package com.kang.bravedns.ui.activity

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.kang.bravedns.R
import com.kang.bravedns.database.HostsEntry
import com.kang.bravedns.database.HostsProfileRepository
import com.kang.bravedns.service.LocalHostsResolver
import com.kang.bravedns.service.PersistentState
import com.kang.bravedns.util.Themes.Companion.getCurrentTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class LocalHostsProfileDetailActivity :
    AppCompatActivity(R.layout.activity_local_hosts_profile_detail) {
    private val persistentState by inject<PersistentState>()
    private val repo by inject<HostsProfileRepository>()
    private val resolver by inject<LocalHostsResolver>()

    private lateinit var listView: ListView
    private lateinit var searchView: SearchView
    private lateinit var summaryView: TextView
    private lateinit var adapter: ArrayAdapter<HostsEntry>

    private var profileId: Long = -1L
    private var allEntries: List<HostsEntry> = emptyList()
    private var filteredEntries: List<HostsEntry> = emptyList()

    private fun android.content.Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        title = if (profileName.isBlank()) getString(R.string.local_hosts_rules) else profileName

        listView = findViewById(R.id.lv_rules)
        // Visual: make rules list look like a set of cards (less "flat/washed out")
        val dp = { v: Int -> (v * resources.displayMetrics.density).roundToInt() }
        listView.setBackgroundResource(R.drawable.local_hosts_list_bg)
        listView.setPadding(
            listView.paddingLeft,
            dp(6),
            listView.paddingRight,
            dp(6)
        )
        listView.divider = null
        listView.dividerHeight = 0

        searchView = findViewById(R.id.sv_rules)
        summaryView = findViewById(R.id.tv_rules_summary)
        adapter =
            object : ArrayAdapter<HostsEntry>(
                this,
                R.layout.item_local_hosts_rule,
                mutableListOf()
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view =
                        convertView
                            ?: LayoutInflater.from(context).inflate(R.layout.item_local_hosts_rule, parent, false)
                    val item = getItem(position) ?: return view
                    val domainView = view.findViewById<TextView>(R.id.tv_rule_domain)
                    val metaView = view.findViewById<TextView>(R.id.tv_rule_meta)
                    val deleteBtn = view.findViewById<ImageButton>(R.id.btn_delete_rule)

                    domainView.text = item.domain.trimEnd('.')
                    metaView.text =
                        getString(
                            R.string.local_hosts_rule_meta,
                            item.recordType,
                            item.value,
                            item.source
                        )
                    deleteBtn.setOnClickListener { confirmDelete(item) }
                    return view
                }
            }
        listView.adapter = adapter

        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    applyFilter(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    applyFilter(newText)
                    return true
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { loadEntries() }
    }

    private suspend fun loadEntries() {
        if (profileId <= 0L) return
        resolver.reload()
        allEntries = repo.getEntries(profileId)
        applyFilter(searchView.query?.toString())
    }

    private fun applyFilter(query: String?) {
        val q = query?.trim().orEmpty().lowercase()
        filteredEntries =
            if (q.isEmpty()) {
                allEntries
            } else {
                allEntries.filter {
                    it.domain.lowercase().contains(q) ||
                        it.value.lowercase().contains(q) ||
                        it.recordType.lowercase().contains(q)
                }
            }
        lifecycleScope.launch(Dispatchers.Main) {
            summaryView.text =
                getString(
                    R.string.local_hosts_rules_summary,
                    filteredEntries.size.toString(),
                    allEntries.size.toString()
                )
            adapter.clear()
            adapter.addAll(filteredEntries)
            adapter.notifyDataSetChanged()
        }
    }

    private fun confirmDelete(entry: HostsEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_hosts_delete_rule)
            .setMessage(
                getString(
                    R.string.local_hosts_delete_rule_confirm,
                    entry.domain.trimEnd('.'),
                    entry.value
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.deleteEntry(entry.id)
                    resolver.reload()
                    loadEntries()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
    }
}
