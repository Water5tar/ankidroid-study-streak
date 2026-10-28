// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import kotlinx.serialization.Serializable

/** A completion certificate, not a reconstruction from review counts. */
@Serializable
data class CompletionProof(
    val startMillis: Long,
    val endMillis: Long,
    val fingerprint: String,
)

@Serializable
data class StreakState(
    val version: Int = 1,
    val completed: Map<Int, CompletionProof> = emptyMap(),
    val protected: Set<Int> = emptySet(),
    val grants: List<Set<Int>> = emptyList(),
    val spent: Int = 0,
    val celebrated: Set<Int> = emptySet(),
    val resolved: Set<Int> = emptySet(),
    val pendingDay: Int? = null,
    val plan: Set<Long> = emptySet(),
    val activePlan: Set<Long> = emptySet(),
    val planDay: Int = -1,
    val answered: Boolean = false,
    val animation: Boolean = true,
    val sound: Boolean = true,
) {
    val days: Set<Int> get() = completed.keys + protected

    // Invalidated grants remain as receipts: restoring a backup cannot award them twice.
    val balance: Int get() = (grants.count { completed.keys.containsAll(it) } - spent).coerceAtLeast(0)

    fun runEnding(day: Int): List<Int> {
        val result = mutableListOf<Int>()
        val recorded = days
        var cursor = day
        while (cursor in recorded) result.add(cursor--)
        return result
    }

    /** Paid protection is a permanent local fact, even if today's plan becomes unfinished again. */
    fun reconcile(valid: Map<Int, CompletionProof>): StreakState = copy(completed = valid, pendingDay = pendingDay?.takeIf { it in valid })

    fun current(today: Int): Int = runEnding(if (today in days) today else today - 1).size

    val longest: Int
        get() {
            var best = 0
            var length = 0
            var previous: Int? = null
            for (day in days.sorted()) {
                length = if (previous == day - 1) length + 1 else 1
                best = maxOf(best, length)
                previous = day
            }
            return best
        }

    fun missingBefore(day: Int): Int {
        val previous = days.filter { it < day }.maxOrNull() ?: return 0
        return day - previous - 1
    }

    /** No completion is minted from opening the app or a partial review. */
    fun observe(
        day: Int,
        complete: Boolean,
        proof: CompletionProof?,
    ): StreakState {
        if (!complete || proof == null) {
            return copy(completed = completed - day, pendingDay = pendingDay.takeUnless { it == day })
        }
        val next = copy(completed = completed + (day to proof))
        if (day in completed) return next
        val gap = missingBefore(day)
        return if (gap > 0 && day !in resolved) {
            // Keep even an unaffordable request, so the UI explains why no cards were spent.
            next.copy(pendingDay = day)
        } else {
            next.award(day)
        }
    }

    /** Caller persists the returned object in ONE atomic write. Insufficient balance is a no-op. */
    fun repair(day: Int): StreakState {
        if (pendingDay != day || day !in completed) return this
        val count = missingBefore(day)
        if (count <= 0 || balance < count) return this
        return copy(
            protected = protected + ((day - count) until day),
            spent = spent + count,
            pendingDay = null,
            resolved = resolved + day,
        ).award(day)
    }

    fun decline(day: Int): StreakState = if (pendingDay == day) copy(pendingDay = null, resolved = resolved + day).award(day) else this

    /** Each grant consumes 30 distinct REAL completions in this continuous (possibly protected) run. */
    fun award(day: Int): StreakState {
        val credited = grants.flatten().toSet()
        val eligible = runEnding(day).filter { it in completed && it !in credited }.sorted()
        return copy(grants = grants + eligible.chunked(30).filter { it.size == 30 }.map { it.toSet() })
    }

    /** Consume all crossed milestones, show only the latest when a repair crosses several. */
    fun celebration(day: Int): Pair<StreakState, Int?> {
        if (pendingDay != null) return this to null
        val run = runEnding(day).sorted()
        val milestones = run.filterIndexed { index, _ -> (index + 1) % 7 == 0 }
        val fresh = milestones.filter { it !in celebrated }
        return copy(celebrated = celebrated + milestones) to fresh.lastOrNull()?.let { run.indexOf(it) + 1 }
    }
}
