// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import com.ichi2.anki.libanki.Collection
import java.time.Instant
import java.time.ZoneId

internal object StreakPlan {
    fun complete(
        col: Collection,
        plan: Set<Long>,
    ): Boolean {
        if (plan.isEmpty()) return false
        val tree = col.sched.deckDueTree(topDeckId = null) ?: return false
        val exhausted =
            plan.all { id ->
                col.decks.findDeckInTree(tree, id)?.let {
                    it.newCount == 0 && it.reviewCount == 0 && it.learnCount == 0
                } == true
            }
        if (!exhausted) return false
        val scope = plan.flatMap { col.decks.deckAndChildIds(it) }.toSet()
        if (scope.isEmpty()) return false
        // The deck tree respects daily limits, filtered decks and FSRS. Add a read-only
        // guard for learning/relearning/filtered-preview due later in this learning day.
        // Negative queues (buried/suspended) intentionally do not block completion.
        val ids = scope.joinToString(",")
        return col.db.queryLongScalar(
            "select count(*) from cards where did in ($ids) and queue in (1,4) and due < ?",
            col.sched.dayCutoff,
        ) == 0L
    }

    fun dayStart(
        cutoff: Long,
        zone: ZoneId,
        rolloverHour: Int = 4,
    ): Long =
        Instant
            .ofEpochSecond(cutoff)
            .atZone(zone)
            .toLocalDate()
            .minusDays(1)
            .atTime(rolloverHour.coerceIn(0, 23), 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
