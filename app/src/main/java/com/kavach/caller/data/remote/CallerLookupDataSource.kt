package com.kavach.caller.data.remote

import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.domain.model.RemoteIdentity
import com.kavach.caller.util.PhoneUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote caller lookup with a short-lived in-memory cache (F9.7).
 *
 * The cache matters: the dialer resolves a number on almost every keystroke, and
 * a screening decision must never wait on the network. Entries expire quickly so
 * a number reported as spam becomes visible soon after.
 *
 * Honest behaviour: when the feature is off, the server is unconfigured, or the
 * request fails, this returns null. It never fabricates an identity.
 */
@Singleton
class CallerLookupDataSource @Inject constructor(
    private val api: KavachApiClient,
    private val features: FeatureRegistry,
) {
    private data class Cached(val identity: RemoteIdentity?, val storedAt: Long)

    private val cache = LinkedHashMap<String, Cached>()

    /** Server identity for a number, or null when there isn't one to trust. */
    suspend fun lookup(rawNumber: String): RemoteIdentity? {
        if (!features.stateOf(FeatureId.REMOTE_LOOKUP).isUsable) return null

        val e164 = PhoneUtils.toE164(rawNumber)
        if (e164.isBlank()) return null

        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[e164]?.let { if (now - it.storedAt < CACHE_TTL_MS) return it.identity }
        }

        val dto = api.lookup(e164)
        val identity = dto?.takeIf { it.source != "NONE" }?.let {
            RemoteIdentity(
                number = it.number,
                displayName = it.displayName,
                numberType = it.numberType,
                businessCategory = it.businessCategory,
                source = it.source,
                confidence = it.confidence,
                verificationStatus = it.verificationStatus,
                spamRisk = it.spamRisk,
                reports = it.reports,
                freshness = it.freshness,
            )
        }

        synchronized(cache) {
            cache[e164] = Cached(identity, now)
            // Bound the cache so a long session cannot grow it without limit.
            while (cache.size > MAX_ENTRIES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
        return identity
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private companion object {
        const val CACHE_TTL_MS = 10L * 60L * 1000L
        const val MAX_ENTRIES = 200
    }
}
