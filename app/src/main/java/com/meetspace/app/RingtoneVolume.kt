package com.meetspace.app

/** Temporary ring-only boost. Never unmutes, lowers an existing level, or overwrites a manual change. */
internal class RingtoneVolume(
    private val current: () -> Int,
    private val maximum: () -> Int,
    private val allowed: () -> Boolean,
    private val set: (Int) -> Unit,
) : AutoCloseable {
    private var original: Int? = null
    private var applied: Int? = null

    fun raise() {
        if (original != null) return
        runCatching {
            if (!allowed()) return
            val before = current()
            val max = maximum()
            if (before <= 0 || max <= 0 || before > max) return
            val target = ((max * 4 + 4) / 5).coerceAtMost(max) // At least 80% of the available ring steps.
            if (before >= target) return
            original = before
            set(target)
            applied = current().takeIf { it > before }
        }
    }

    override fun close() {
        val before = original
        val last = applied
        original = null; applied = null
        runCatching {
            if (before != null && last != null && allowed() && current() == last) set(before)
        }
    }
}
