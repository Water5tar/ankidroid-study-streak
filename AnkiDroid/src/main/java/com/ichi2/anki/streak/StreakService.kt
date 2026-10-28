// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import android.content.Context
import com.ichi2.anki.common.time.TimeManager.time
import com.ichi2.anki.libanki.Collection
import java.time.ZoneId

/** Call only inside CollectionManager.withCol, which serializes collection access. */
object StreakService {
    fun update(
        context: Context,
        col: Collection,
        answered: Boolean = false,
        validateHistory: Boolean = true,
        answeredDeckId: Long? = null,
    ): StreakState = updateWithStore(col, StreakStore(context, col), answered, validateHistory, answeredDeckId)

    internal fun updateWithStore(
        col: Collection,
        store: StreakStore,
        answered: Boolean = false,
        validateHistory: Boolean = true,
        answeredDeckId: Long? = null,
    ): StreakState {
        val before = store.read()
        val day = col.sched.today
        // Time travel cannot mint additional completions/rewards or rewrite later certificates.
        if (day < before.planDay) return before
        var state = before
        if (state.planDay != day) {
            state.pendingDay?.let { state = state.decline(it) }
            state = state.copy(planDay = day, activePlan = emptySet(), answered = false)
        }
        if (answered && state.activePlan.isEmpty()) {
            state = state.copy(activePlan = state.plan.ifEmpty { setOf(col.decks.selected()) })
        }
        val scope = state.activePlan.flatMap { col.decks.deckAndChildIds(it) }.toSet()
        val scopeAnswer = answered && (answeredDeckId ?: col.decks.selected()) in scope
        if (scopeAnswer) state = state.copy(answered = true)

        // Revlog cannot prove old plan completion. Certificates start with this installation;
        // their read-only fingerprints detect undo, import, deletion and changed sync history.
        val valid =
            state.completed.filter { (recordedDay, proof) ->
                (!validateHistory && recordedDay != day) || fingerprint(col, proof.startMillis, proof.endMillis) == proof.fingerprint
            }
        val invalidatedToday = day in state.completed && day !in valid
        state = state.reconcile(valid)
        // An empty queue after sync/deletion is not a new completion observation.
        // Only a fresh successful answer can certify today again.
        if (invalidatedToday) state = state.copy(answered = scopeAnswer)
        if (state.answered && scope.isNotEmpty()) {
            val exhausted = StreakPlan.complete(col, state.activePlan)
            val end = time.intTimeMS()
            val start = StreakPlan.dayStart(col.sched.dayCutoff, ZoneId.systemDefault(), col.config.get("rollover") ?: 4)
            val proof = if (exhausted) state.completed[day] ?: CompletionProof(start, end, fingerprint(col, start, end)) else null
            state = state.observe(day, exhausted, proof)
        }
        if (state != before) store.write(state)
        return state
    }

    fun mutate(
        context: Context,
        col: Collection,
        transform: (StreakState) -> StreakState,
    ): StreakState {
        val store = StreakStore(context, col)
        val before = store.read()
        val after = transform(before)
        if (after != before) store.write(after)
        return after
    }

    private fun fingerprint(
        col: Collection,
        start: Long,
        end: Long,
    ): String {
        val rows = StringBuilder()
        col.db
            .query(
                "select id,cid,ease,ivl,lastIvl,factor,time,type from revlog where id >= ? and id <= ? order by id",
                start,
                end,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    for (index in 0..7) rows.append(cursor.getLong(index)).append(':')
                    rows.append(';')
                }
            }
        return digest(rows.toString())
    }
}
