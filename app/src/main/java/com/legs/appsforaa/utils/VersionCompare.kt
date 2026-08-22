package com.legs.appsforaa.utils

/**
 * Compares app version strings, safely.
 *
 * Upstream's `utils/Version` class (deleted in this fork, see TASKS.md T-17) threw on anything
 * that was not strictly `1.2.3`, and real release names are not: this catalog alone contains
 * `v1.0.3`, `beta1.1`, `0.88B` and
 * `untagged-7666cf8b031e67be69d2`. A comparator that throws — or worse, guesses — would show
 * phantom "update available" badges, which is exactly the kind of wrong that erodes trust in the
 * whole list.
 *
 * So the contract here is deliberately narrow: **return null unless both sides are confidently
 * comparable.** A null means "do not claim anything", and callers show no update rather than a
 * wrong one.
 */
object VersionCompare {

    /** Leading dotted-numeric run, which is the only part that can be ordered meaningfully. */
    private val NUMERIC_PREFIX = Regex("^([0-9]+(?:\\.[0-9]+)*)")

    /**
     * Extracts a comparable version from a release name.
     *
     * Handles the common decorations — a `v` prefix, and a trailing suffix like `B` or `-beta`
     * that carries no ordering information — by keeping the numeric prefix and discarding the
     * rest. Returns null when there is no leading number at all, e.g. `beta1.1` or an untagged
     * release hash, because guessing at those produces false updates.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        return NUMERIC_PREFIX.find(trimmed)?.groupValues?.get(1)
    }

    /**
     * @return true when [available] is strictly newer than [installed]; false when it is not;
     *   **null when either side cannot be compared**, which callers must treat as "unknown", not
     *   as "no update".
     */
    fun isNewer(installed: String?, available: String?): Boolean? {
        val left = normalize(installed) ?: return null
        val right = normalize(available) ?: return null
        return compare(right, left) > 0
    }

    /** Segment-wise numeric comparison; missing trailing segments count as zero, so 1.2 == 1.2.0. */
    private fun compare(a: String, b: String): Int {
        val aParts = a.split('.')
        val bParts = b.split('.')
        for (index in 0 until maxOf(aParts.size, bParts.size)) {
            val aPart = aParts.getOrNull(index)?.toLongOrNull() ?: 0L
            val bPart = bParts.getOrNull(index)?.toLongOrNull() ?: 0L
            if (aPart != bPart) return if (aPart > bPart) 1 else -1
        }
        return 0
    }
}
