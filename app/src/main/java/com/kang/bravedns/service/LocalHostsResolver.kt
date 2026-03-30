package com.kang.bravedns.service

import com.kang.bravedns.database.HostsProfileRepository
import com.kang.bravedns.util.ResourceRecordTypes
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class LocalHostsResolver(
    private val hostsRepository: HostsProfileRepository
) {
    data class MatchResult(
        val ip: String,
        val source: String,
        val profileId: Long
    )

    private data class Snapshot(
        val exact: Map<String, HostsProfileRepository.ResolveRule>,
        val suffix: Map<String, MutableList<HostsProfileRepository.ResolveRule>>
    )

    private val current = AtomicReference(Snapshot(emptyMap(), emptyMap()))

    /**
     * Ensures default profile exists and rebuilds the in-memory rule snapshot.
     * Call when enabling Local DNS from settings (not on every DNS query).
     */
    fun ensureInitialized() {
        hostsRepository.ensureDefaultProfile()
        reload()
    }

    /** Rebuilds rule maps from the database. Safe to call frequently (e.g. each VPN DNS query). */
    fun reload() {
        val maps = hostsRepository.buildActiveRuleMaps()
        current.set(Snapshot(maps.first, maps.second))
    }

    fun resolve(domain: String, qtype: Int): MatchResult? {
        val t =
            when (ResourceRecordTypes.getTypeName(qtype).name) {
                "AAAA" -> "AAAA"
                "A" -> "A"
                else -> return null
            }
        val d = normalizeDomain(domain)
        val snapshot = current.get()
        snapshot.exact["$t|$d"]?.let { return MatchResult(it.value, it.source, it.profileId) }
        val suffixRules = snapshot.suffix[t] ?: return null
        suffixRules.forEach { r ->
            val parts = r.value.split("|", limit = 2)
            if (parts.size != 2) return@forEach
            val suffixDomain = parts[0]
            val ip = parts[1]
            if (d.endsWith(suffixDomain)) {
                return MatchResult(ip, r.source, r.profileId)
            }
        }
        return null
    }

    private fun normalizeDomain(domain: String): String {
        return domain.trim().trim('.').lowercase(Locale.ROOT) + "."
    }
}
