package org.moontechlab.selenetv.service

/**
 * Conservative starter list for FongMi-style ads domain filtering.
 *
 * Keep this list limited to dedicated advertising hosts: broad analytics/CDN domains
 * can be shared by legitimate playback traffic and should not be blocked here.
 */
object AdBlockService {
    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adsrvr.org",
        "adnxs.com",
        "adform.net",
        "taboola.com",
        "outbrain.com",
    )

    /** Match an exact host or one of its subdomains; never use substring matching. */
    fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        return blockedDomains.any { normalized == it || normalized.endsWith(".$it") }
    }
}
