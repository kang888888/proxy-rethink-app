package com.kang.bravedns.database

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HostsProfileRepository(
    private val profileDao: HostsProfileDao,
    private val entryDao: HostsEntryDao,
    private val syncDao: HostsProfileSyncDao
) {
    data class ResolveRule(val value: String, val source: String, val profileId: Long)
    data class ImportResult(val imported: Int, val skipped: Int, val conflicts: Int)
    data class ProfileConflict(
        val domain: String,
        val recordType: String,
        val currentValue: String,
        val incomingValue: String
    )

    fun ensureDefaultProfile(): Long {
        val existing = profileDao.getAll().firstOrNull { it.name == DEFAULT_PROFILE_NAME }
        if (existing != null) return existing.id
        return profileDao.insert(
            HostsProfile(
                name = DEFAULT_PROFILE_NAME,
                enabled = true,
                priority = 0,
                sourceType = "manual"
            )
        )
    }

    fun getProfiles(): List<HostsProfile> = profileDao.getAll()

    fun getActiveProfiles(): List<HostsProfile> = profileDao.getActiveProfiles()

    fun addProfile(name: String, enabled: Boolean = true): Long {
        val p = HostsProfile(name = name, enabled = enabled, priority = nextPriority(), sourceType = "mixed")
        return profileDao.insert(p)
    }

    fun renameProfile(profileId: Long, name: String): Boolean {
        val p = profileDao.getById(profileId) ?: return false
        return profileDao.update(p.copy(name = name, updatedAt = System.currentTimeMillis())) > 0
    }

    fun setProfileEnabled(profileId: Long, enabled: Boolean): Boolean {
        return profileDao.setEnabled(profileId, enabled) > 0
    }

    fun detectConflictsWithActiveProfiles(profileId: Long): List<ProfileConflict> {
        val target = entryDao.getByProfile(profileId).filter { it.enabled }
        if (target.isEmpty()) return emptyList()

        val activeOthers =
            entryDao.getAllActiveEntries()
                .filter { it.profileId != profileId }
                .associateBy { conflictKey(it) }

        val out = mutableListOf<ProfileConflict>()
        target.forEach { t ->
            val active = activeOthers[conflictKey(t)] ?: return@forEach
            if (!t.value.equals(active.value, ignoreCase = true)) {
                out.add(
                    ProfileConflict(
                        domain = t.domain.trimEnd('.'),
                        recordType = normalizeType(t.recordType),
                        currentValue = active.value,
                        incomingValue = t.value
                    )
                )
            }
        }
        return out.distinctBy { "${it.domain}|${it.recordType}|${it.currentValue}|${it.incomingValue}" }
    }

    fun enableOnly(profileId: Long): Boolean {
        val updated = profileDao.setEnabled(profileId, true)
        profileDao.disableOthers(profileId)
        return updated > 0
    }

    fun moveProfile(profileId: Long, newPriority: Int): Boolean {
        val p = profileDao.getById(profileId) ?: return false
        return profileDao.update(p.copy(priority = newPriority, updatedAt = System.currentTimeMillis())) > 0
    }

    fun deleteProfile(profileId: Long): Boolean {
        entryDao.deleteByProfile(profileId)
        syncDao.delete(profileId)
        return profileDao.deleteById(profileId) > 0
    }

    fun getEntries(profileId: Long): List<HostsEntry> = entryDao.getByProfile(profileId)

    fun getEnabledRuleCount(profileId: Long): Int = entryDao.getEnabledCountByProfile(profileId)

    fun deleteEntry(entryId: Long): Boolean = entryDao.deleteById(entryId) > 0

    fun addManualEntry(
        profileId: Long,
        domain: String,
        recordType: String,
        value: String,
        enabled: Boolean,
        isSuffixMatch: Boolean
    ): Long {
        val e =
            HostsEntry(
                profileId = profileId,
                domain = normalizeDomain(domain),
                recordType = normalizeType(recordType),
                value = value.trim(),
                enabled = enabled,
                isSuffixMatch = isSuffixMatch,
                source = "manual"
            )
        return entryDao.insert(e)
    }

    fun importFromFileContent(profileId: Long, content: String, source: String = "file"): Int {
        return importFromFileContentWithResult(profileId, content, source).imported
    }

    fun replaceProfileContent(profileId: Long, content: String, source: String = "manual"): ImportResult {
        entryDao.deleteByProfile(profileId)
        if (content.isBlank()) return ImportResult(0, 0, 0)
        return importFromFileContentWithResult(profileId, content, source)
    }

    fun importFromFileContentWithResult(profileId: Long, content: String, source: String = "file"): ImportResult {
        val parsed = parseHosts(content, profileId, source)
        if (parsed.entries.isEmpty()) return ImportResult(0, parsed.skipped, 0)
        val conflictCount = countConflictsWithActiveProfiles(profileId, parsed.entries)
        entryDao.insertAll(parsed.entries)
        return ImportResult(parsed.entries.size, parsed.skipped, conflictCount)
    }

    fun syncFromRemote(profileId: Long, url: String): Int {
        val content = fetchUrl(url)
        val parsed = parseHosts(content, profileId, "remote")
        if (parsed.entries.isEmpty()) {
            syncDao.insert(
                HostsProfileSync(
                    profileId = profileId,
                    remoteUrl = url,
                    lastError = "no valid hosts entries",
                    updatedAt = System.currentTimeMillis()
                )
            )
            return 0
        }
        entryDao.insertAll(parsed.entries)
        syncDao.insert(
            HostsProfileSync(
                profileId = profileId,
                remoteUrl = url,
                contentHash = content.hashCode().toString(),
                lastSuccessTs = System.currentTimeMillis(),
                lastError = "",
                updatedAt = System.currentTimeMillis()
            )
        )
        return parsed.entries.size
    }

    fun buildActiveRuleMaps(): Pair<Map<String, ResolveRule>, Map<String, MutableList<ResolveRule>>> {
        val active = entryDao.getAllActiveEntries()
        val exact = LinkedHashMap<String, ResolveRule>()
        val suffix = LinkedHashMap<String, MutableList<ResolveRule>>()
        active.forEach { e ->
            val k = key(e.recordType, e.domain)
            val v = ResolveRule(e.value, e.source, e.profileId)
            if (e.isSuffixMatch) {
                suffix.getOrPut(e.recordType) { mutableListOf() }.add(v.copy(value = "${e.domain}|${e.value}"))
            } else {
                if (!exact.containsKey(k)) exact[k] = v
            }
        }
        return Pair(exact, suffix)
    }

    private data class ParseHostsOutput(val entries: List<HostsEntry>, val skipped: Int)

    private fun parseHosts(content: String, profileId: Long, source: String): ParseHostsOutput {
        val rows = mutableListOf<HostsEntry>()
        var skipped = 0
        content.lineSequence().forEach { lineRaw ->
            val line = lineRaw.substringBefore("#").trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 2) {
                skipped++
                return@forEach
            }
            val ip = parts[0].trim()
            if (!isValidIp(ip)) {
                skipped++
                return@forEach
            }
            val type = if (ip.contains(":")) "AAAA" else "A"
            var validDomainFound = false

            // hosts file line format supports one IP followed by one or more hostnames.
            parts.drop(1).forEach { token ->
                val domain = token.trim().lowercase(Locale.ROOT)
                if (!isLikelyHostname(domain)) return@forEach
                validDomainFound = true
                val suffix = domain.startsWith(".")
                val d = if (suffix) domain.removePrefix(".") else domain
                rows.add(
                    HostsEntry(
                        profileId = profileId,
                        domain = normalizeDomain(d),
                        recordType = type,
                        value = ip,
                        enabled = true,
                        isSuffixMatch = suffix,
                        source = source
                    )
                )
            }
            if (!validDomainFound) skipped++
        }
        return ParseHostsOutput(rows, skipped)
    }

    private fun fetchUrl(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.connect()
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun nextPriority(): Int {
        return (profileDao.getAll().maxOfOrNull { it.priority } ?: -1) + 1
    }

    private fun normalizeDomain(domain: String): String {
        return domain.trim().trim('.').lowercase(Locale.ROOT) + "."
    }

    private fun normalizeType(type: String): String {
        return type.uppercase(Locale.ROOT).let { if (it == "AAAA") "AAAA" else "A" }
    }

    private fun isValidIp(ip: String): Boolean {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return false
        // Accept plain IPv6 literals and optional bracket form.
        val ipv6 = trimmed.removePrefix("[").removeSuffix("]")
        val ipv4Regex = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        if (ipv4Regex.matches(trimmed)) return true
        // Lightweight IPv6 check (sufficient for hosts-file import).
        return ipv6.contains(":") && ipv6.all { it.isLetterOrDigit() || it == ':' }
    }

    private fun isLikelyHostname(token: String): Boolean {
        val t = token.trim().trimEnd('.')
        if (t.isEmpty()) return false
        // Filter obvious non-host tokens.
        if (t.contains("/") || t.contains("=") || t.contains(",")) return false
        if (t.any { it.isWhitespace() }) return false
        // Support suffix rules like ".example.com".
        val h = t.removePrefix(".")
        if (h.isEmpty()) return false
        return h.all { it.isLetterOrDigit() || it == '-' || it == '.' }
    }

    private fun key(type: String, domain: String): String = "${normalizeType(type)}|${normalizeDomain(domain)}"

    private fun conflictKey(entry: HostsEntry): String {
        return "${normalizeType(entry.recordType)}|${normalizeDomain(entry.domain)}|${entry.isSuffixMatch}"
    }

    private fun countConflictsWithActiveProfiles(profileId: Long, incomingEntries: List<HostsEntry>): Int {
        if (incomingEntries.isEmpty()) return 0
        val activeOthers =
            entryDao.getAllActiveEntries()
                .filter { it.profileId != profileId }
                .associateBy { conflictKey(it) }
        return incomingEntries.count { incoming ->
            val active = activeOthers[conflictKey(incoming)] ?: return@count false
            !incoming.value.equals(active.value, ignoreCase = true)
        }
    }

    companion object {
        const val DEFAULT_PROFILE_NAME = "Default"
    }
}
