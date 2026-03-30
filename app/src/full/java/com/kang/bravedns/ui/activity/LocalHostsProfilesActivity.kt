package com.kang.bravedns.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.kang.bravedns.R
import com.kang.bravedns.database.HostsProfile
import com.kang.bravedns.database.HostsProfileRepository
import com.kang.bravedns.service.LocalHostsResolver
import com.kang.bravedns.service.PersistentState
import com.kang.bravedns.util.Themes.Companion.getCurrentTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class LocalHostsProfilesActivity : AppCompatActivity(R.layout.activity_local_hosts_profiles) {
    private val persistentState by inject<PersistentState>()
    private val repo by inject<HostsProfileRepository>()
    private val resolver by inject<LocalHostsResolver>()
    private lateinit var listView: ListView
    private lateinit var summaryView: TextView
    private var profiles: List<HostsProfile> = emptyList()
    private var ruleCounts: Map<Long, Int> = emptyMap()
    private var conflictCounts: Map<Long, Int> = emptyMap()
    private var selectedProfileId: Long = -1L
    private lateinit var adapter: ArrayAdapter<HostsProfile>
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                importUri(uri)
            }
        }

    private fun android.content.Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        title = getString(R.string.local_hosts)
        listView = findViewById(R.id.lv_profiles)
        summaryView = findViewById(R.id.tv_summary)
        adapter =
            object : ArrayAdapter<HostsProfile>(
                this,
                R.layout.item_local_hosts_profile,
                mutableListOf()
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view =
                        convertView
                            ?: LayoutInflater.from(context)
                                .inflate(R.layout.item_local_hosts_profile, parent, false)
                    val profile = getItem(position) ?: return view
                    val tvName = view.findViewById<TextView>(R.id.tv_profile_name)
                    val tvMeta = view.findViewById<TextView>(R.id.tv_profile_meta)
                    val tvConflict = view.findViewById<TextView>(R.id.tv_profile_conflict)
                    val sw = view.findViewById<SwitchMaterial>(R.id.sw_profile_enabled)
                    val editBtn = view.findViewById<View>(R.id.btn_edit_profile)
                    val deleteBtn = view.findViewById<View>(R.id.btn_delete_profile)

                    tvName.text = profile.name
                    val rules = ruleCounts[profile.id] ?: 0
                    tvMeta.text =
                        context.getString(
                            R.string.local_hosts_profile_meta,
                            profile.priority.toString(),
                            rules.toString()
                        )
                    val conflicts = conflictCounts[profile.id] ?: 0
                    if (conflicts > 0) {
                        tvConflict.visibility = View.VISIBLE
                        tvConflict.text =
                            context.getString(R.string.local_hosts_conflict_count, conflicts.toString())
                    } else {
                        tvConflict.visibility = View.GONE
                    }
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = profile.enabled
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        selectedProfileId = profile.id
                        if (isChecked == profile.enabled) return@setOnCheckedChangeListener
                        onToggleProfile(profile, isChecked)
                    }
                    editBtn.setOnClickListener {
                        selectedProfileId = profile.id
                        showEditProfileDialog(profile)
                    }
                    deleteBtn.setOnClickListener {
                        selectedProfileId = profile.id
                        confirmDeleteProfile(profile)
                    }
                    view.setOnClickListener {
                        selectedProfileId = profile.id
                        startActivity(
                            Intent(context, LocalHostsProfileDetailActivity::class.java)
                                .putExtra(LocalHostsProfileDetailActivity.EXTRA_PROFILE_ID, profile.id)
                                .putExtra(LocalHostsProfileDetailActivity.EXTRA_PROFILE_NAME, profile.name)
                        )
                    }
                    return view
                }
            }
        listView.adapter = adapter

        findViewById<android.view.View>(R.id.btn_add_profile).setOnClickListener { showAddProfileDialog() }
        findViewById<android.view.View>(R.id.btn_add_rule).setOnClickListener { showAddRuleDialog() }
        findViewById<android.view.View>(R.id.btn_sync_remote).setOnClickListener { showRemoteSyncDialog() }
        findViewById<android.view.View>(R.id.btn_import_file).setOnClickListener { pickFile() }

        listView.setOnItemLongClickListener { _, _, _, _ -> true }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            refreshProfiles()
        }
    }

    private suspend fun refreshProfiles() {
        resolver.reload()
        profiles =
            repo.getProfiles().filterNot {
                it.name == HostsProfileRepository.DEFAULT_PROFILE_NAME && repo.getEntries(it.id).isEmpty()
            }
        ruleCounts = profiles.associate { it.id to repo.getEnabledRuleCount(it.id) }
        conflictCounts = profiles.associate { it.id to repo.detectConflictsWithActiveProfiles(it.id).size }
        if (selectedProfileId <= 0 && profiles.isNotEmpty()) selectedProfileId = profiles.first().id
        withContext(Dispatchers.Main) {
            val enabledProfiles = profiles.count { it.enabled }
            val totalRules = ruleCounts.values.sum()
            val totalConflicts = conflictCounts.values.sum()
            summaryView.text =
                getString(
                    R.string.local_hosts_summary,
                    enabledProfiles.toString(),
                    totalRules.toString(),
                    totalConflicts.toString()
                )
            adapter.clear()
            adapter.addAll(profiles)
            adapter.notifyDataSetChanged()
        }
    }

    private fun onToggleProfile(profile: HostsProfile, enable: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (enable) {
                val conflicts = repo.detectConflictsWithActiveProfiles(profile.id)
                if (conflicts.isNotEmpty()) {
                    val preview =
                        conflicts.take(8).joinToString("\n") {
                            "${it.domain} (${it.recordType}) : ${it.currentValue} -> ${it.incomingValue}"
                        } + if (conflicts.size > 8) "\n..." else ""
                    withContext(Dispatchers.Main) {
                        MaterialAlertDialogBuilder(this@LocalHostsProfilesActivity)
                            .setTitle(R.string.local_hosts_conflict_title)
                            .setMessage(
                                getString(
                                    R.string.local_hosts_conflict_message,
                                    conflicts.size.toString(),
                                    preview
                                )
                            )
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    repo.setProfileEnabled(profile.id, true)
                                    resolver.reload()
                                    refreshProfiles()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) { refreshProfiles() }
                            }
                            .show()
                    }
                    return@launch
                }
            }

            repo.setProfileEnabled(profile.id, enable)
            resolver.reload()
            refreshProfiles()
        }
    }

    private fun confirmDeleteProfile(profile: HostsProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_hosts_delete_profile)
            .setMessage(getString(R.string.local_hosts_delete_profile_confirm, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repo.deleteProfile(profile.id)
                    resolver.reload()
                    refreshProfiles()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddProfileDialog() {
        val (form, nameInput, contentInput) = buildProfileEditorForm()
        val dialog =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle(R.string.local_hosts_add_profile)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            styleProfileDialog(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim().ifEmpty { "Profile-${System.currentTimeMillis()}" }
                val content = contentInput.text.toString()
                lifecycleScope.launch(Dispatchers.IO) {
                    selectedProfileId = repo.addProfile(name, true)
                    if (content.isNotBlank()) {
                        repo.importFromFileContentWithResult(selectedProfileId, content, "manual")
                    }
                    resolver.reload()
                    refreshProfiles()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditProfileDialog(profile: HostsProfile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content =
                try {
                    val entries = repo.getEntries(profile.id)
                    entries.joinToString("\n") { entry ->
                        val domain = entry.domain.trimEnd('.')
                        val host = if (entry.isSuffixMatch) ".$domain" else domain
                        "${entry.value} $host"
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("LocalHostsProfilesAct", "load edit content failed, profileId=${profile.id}", t)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@LocalHostsProfilesActivity,
                            "${getString(R.string.local_hosts_open_dialog_error)}: ${t.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                try {
                    val (form, nameInput, contentInput) = buildProfileEditorForm(profile.name, content)
                    val dialog =
                        MaterialAlertDialogBuilder(this@LocalHostsProfilesActivity, R.style.App_Dialog_NoDim)
                            .setTitle(R.string.local_hosts_edit_profile)
                            .setView(form)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNegativeButton(android.R.string.cancel, null)
                            .create()
                    dialog.setOnShowListener {
                        styleProfileDialog(dialog)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener {
                                val name = nameInput.text.toString().trim()
                                if (name.isEmpty()) {
                                    nameInput.error = getString(R.string.local_hosts_error_name_required)
                                    return@setOnClickListener
                                }
                                val updatedContent = contentInput.text.toString()
                                lifecycleScope.launch(Dispatchers.IO) {
                                    repo.renameProfile(profile.id, name)
                                    repo.replaceProfileContent(profile.id, updatedContent, "manual")
                                    resolver.reload()
                                    refreshProfiles()
                                }
                                dialog.dismiss()
                            }
                    }
                    dialog.show()
                } catch (t: Throwable) {
                    android.util.Log.e("LocalHostsProfilesAct", "show edit dialog failed, profileId=${profile.id}", t)
                    Toast.makeText(
                        this@LocalHostsProfilesActivity,
                        "${getString(R.string.local_hosts_open_dialog_error)}: ${t.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun createLocalHostsFormLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#1F1F1F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

    private fun applyLocalHostsInputStyle(edit: EditText) {
        edit.setTextColor(Color.parseColor("#111111"))
        edit.setHintTextColor(Color.parseColor("#7A7A7A"))
        edit.setBackgroundResource(R.drawable.edittext_default)
    }

    private fun buildProfileEditorForm(
        initialName: String = "",
        initialContent: String = ""
    ): Triple<View, EditText, EditText> {
        val dp =
            { value: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    resources.displayMetrics
                ).toInt()
            }
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
        val nameLabel = createLocalHostsFormLabel(getString(R.string.local_hosts_label_profile_name))
        val nameInput =
            EditText(this).apply {
                hint = getString(R.string.local_hosts_add_profile_name_hint)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                applyLocalHostsInputStyle(this)
                setText(initialName)
            }
        val contentLabel = createLocalHostsFormLabel(getString(R.string.local_hosts_label_hosts_content))
        val contentInput =
            EditText(this).apply {
                hint = getString(R.string.local_hosts_add_profile_content_hint)
                minLines = 8
                maxLines = 16
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                applyLocalHostsInputStyle(this)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.default_font_text_view))
                setText(initialContent)
            }
        nameInput.doAfterTextChanged { nameInput.error = null }
        contentInput.doAfterTextChanged { contentInput.error = null }
        val lpMatch =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        form.addView(nameLabel, lpMatch)
        form.addView(
            nameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        form.addView(
            contentLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )
        form.addView(
            contentInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                setPadding(dp(12), dp(4), dp(12), dp(12))
                addView(
                    form,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        return Triple(scroll, nameInput, contentInput)
    }

    private fun styleProfileDialog(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        val titleRes =
            resources.getIdentifier("material_alert_dialog_title", "id", packageName)
        val titleId = if (titleRes != 0) titleRes else android.R.id.title
        dialog.findViewById<TextView>(titleId)?.setTextColor(Color.parseColor("#1F1F1F"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#1565C0"))
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#6A6A6A"))
    }

    private fun showAddRuleDialog() {
        val dp =
            { value: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    resources.displayMetrics
                ).toInt()
            }
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(12))
            }
        val domainLabel =
            TextView(this).apply {
                text = getString(R.string.local_hosts_rule_domain)
                setTextColor(Color.parseColor("#1F1F1F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        val domainInput =
            EditText(this).apply {
                hint = "example.com"
                setSingleLine(true)
                applyLocalHostsInputStyle(this)
            }
        val ipLabel =
            TextView(this).apply {
                text = getString(R.string.local_hosts_rule_ip)
                setTextColor(Color.parseColor("#1F1F1F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        val ipInput =
            EditText(this).apply {
                hint = "1.1.1.1"
                setSingleLine(true)
                applyLocalHostsInputStyle(this)
            }
        domainInput.doAfterTextChanged { domainInput.error = null }
        ipInput.doAfterTextChanged { ipInput.error = null }
        form.addView(
            domainLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        form.addView(
            domainInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        form.addView(
            ipLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
        form.addView(
            ipInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        val dialog =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle(R.string.local_hosts_add_rule)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            styleProfileDialog(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val domain = domainInput.text.toString().trim()
                val ip = ipInput.text.toString().trim()
                var hasError = false
                if (domain.isEmpty()) {
                    domainInput.error = getString(R.string.local_hosts_error_domain_required)
                    hasError = true
                }
                if (ip.isEmpty()) {
                    ipInput.error = getString(R.string.local_hosts_error_ip_required)
                    hasError = true
                }
                if (hasError) return@setOnClickListener
                lifecycleScope.launch(Dispatchers.IO) {
                    val profileName = "Rule-${domain.take(24)}-${System.currentTimeMillis()}"
                    selectedProfileId = repo.addProfile(profileName, true)
                    val type = if (ip.contains(":")) "AAAA" else "A"
                    val suffix = domain.startsWith(".")
                    repo.addManualEntry(selectedProfileId, domain, type, ip, true, suffix)
                    resolver.reload()
                    refreshProfiles()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LocalHostsProfilesActivity, R.string.local_hosts_rule_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRemoteSyncDialog() {
        val dp =
            { value: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    resources.displayMetrics
                ).toInt()
            }
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(12))
            }
        val urlLabel =
            TextView(this).apply {
                text = getString(R.string.local_hosts_remote_url)
                setTextColor(Color.parseColor("#1F1F1F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        val et =
            EditText(this).apply {
                hint = "https://example.com/hosts.txt"
                setSingleLine(true)
                applyLocalHostsInputStyle(this)
            }
        et.doAfterTextChanged { et.error = null }
        form.addView(
            urlLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        form.addView(
            et,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        val dialog =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle(R.string.local_hosts_sync_remote)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            styleProfileDialog(dialog)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = et.text.toString().trim()
                if (url.isEmpty()) {
                    et.error = getString(R.string.local_hosts_error_url_required)
                    return@setOnClickListener
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val profileName = "Remote-${url.substringAfter("://").substringBefore("/").take(24)}-${System.currentTimeMillis()}"
                    selectedProfileId = repo.addProfile(profileName, true)
                    runCatching { repo.syncFromRemote(selectedProfileId, url) }
                    resolver.reload()
                    refreshProfiles()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.type = "*/*"
        importFileLauncher.launch(i)
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            }.onSuccess { content ->
                val profileName = "Import-${System.currentTimeMillis()}"
                selectedProfileId = repo.addProfile(profileName, true)
                val result = repo.importFromFileContentWithResult(selectedProfileId, content, "file")
                resolver.reload()
                refreshProfiles()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LocalHostsProfilesActivity,
                        getString(
                            R.string.local_hosts_import_result_detailed,
                            result.imported.toString(),
                            result.skipped.toString(),
                            result.conflicts.toString()
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

}
