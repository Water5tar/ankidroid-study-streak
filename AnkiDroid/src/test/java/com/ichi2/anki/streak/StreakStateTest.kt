// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreakStateTest {
    @Test
    fun `sync invalidation truncates real streak evidence but preserves paid protection`() {
        val state = days(60).observe(63, true, proof).repair(63)
        val invalid = state.reconcile(state.completed - 60)
        assertEquals(setOf(61, 62), invalid.protected)
        assertEquals(2, invalid.spent)
        assertEquals(0, invalid.balance)
        assertEquals(3, invalid.current(63))
    }

    @Test
    fun `dynamic new tasks after repair preserve paid protection without charging again`() {
        val repaired = days(60).observe(63, true, proof).repair(63)
        val partial = repaired.observe(63, false, null)
        val refreshed = partial.reconcile(partial.completed)
        val finished = refreshed.observe(63, true, proof)
        assertEquals(setOf(61, 62), finished.protected)
        assertEquals(63, finished.current(63))
        assertEquals(2, finished.spent)
        assertEquals(0, finished.balance)
    }

    private val proof = CompletionProof(1, 2, "test")

    private fun days(count: Int): StreakState = (1..count).fold(StreakState()) { state, day -> state.observe(day, true, proof) }

    @Test
    fun `partial work and an empty app launch cannot complete a day`() {
        assertEquals(0, StreakState().observe(1, false, proof).current(1))
        assertEquals(0, StreakState().observe(1, true, null).current(1))
    }

    @Test
    fun `complete plans increment once per learning day`() {
        assertEquals(1, days(1).observe(1, true, proof).current(1))
        assertEquals(7, days(7).current(7))
        assertEquals(7, days(7).current(8))
        assertEquals(0, days(7).current(9))
    }

    @Test
    fun `30 and 60 real days issue one and two cards`() {
        assertEquals(0, days(29).balance)
        assertEquals(1, days(30).balance)
        assertEquals(2, days(60).balance)
    }

    @Test
    fun `repeated completion and recalculation do not duplicate grants`() {
        var state = days(60)
        repeat(10) { state = state.observe(60, true, proof).award(60) }
        assertEquals(2, state.balance)
        assertEquals(2, state.grants.size)
    }

    @Test
    fun `seven day celebration is consumed once and survives serialization`() {
        val (state, count) = days(7).celebration(7)
        assertEquals(7, count)
        val restarted = Json.decodeFromString<StreakState>(Json.encodeToString(state))
        assertNull(restarted.celebration(7).second)
    }

    @Test
    fun `no celebration before seven or while repair is pending`() {
        assertNull(days(6).celebration(6).second)
        assertNull(days(30).observe(32, true, proof).celebration(32).second)
    }

    @Test
    fun `one missing day without a card starts a new run without spending`() {
        val state = days(7).observe(9, true, proof)
        assertEquals(state, state.repair(9))
        assertEquals(1, state.decline(9).current(9))
        assertEquals(0, state.spent)
    }

    @Test
    fun `one missing day uses exactly one card`() {
        val state = days(37).observe(39, true, proof).repair(39)
        assertEquals(39, state.current(39))
        assertEquals(setOf(38), state.protected)
        assertEquals(0, state.balance)
    }

    @Test
    fun `declining keeps balance and cannot be reversed by repeated completion`() {
        val state = days(37).observe(39, true, proof).decline(39).observe(39, true, proof)
        assertEquals(1, state.current(39))
        assertEquals(1, state.balance)
        assertNull(state.pendingDay)
        assertEquals(state, state.repair(39))
    }

    @Test
    fun `two missing days are all restored or none`() {
        val insufficient = days(37).observe(40, true, proof)
        assertEquals(insufficient, insufficient.repair(40))
        assertTrue(insufficient.protected.isEmpty())
        assertEquals(1, insufficient.balance)
        val sufficient = days(60).observe(63, true, proof).repair(63)
        assertEquals(setOf(61, 62), sufficient.protected)
        assertEquals(63, sufficient.current(63))
        assertEquals(0, sufficient.balance)
    }

    @Test
    fun `three missing days consume three cards atomically`() {
        val state = days(90).observe(94, true, proof).repair(94)
        assertEquals(setOf(91, 92, 93), state.protected)
        assertEquals(94, state.current(94))
        assertEquals(3, state.spent)
        assertEquals(0, state.balance)
        assertEquals(state, state.repair(94))
    }

    @Test
    fun `protected day crossing 60 does not create a card`() {
        val state = days(58).observe(60, true, proof).repair(60)
        assertEquals(60, state.current(60))
        assertEquals(0, state.balance)
        assertEquals(1, state.grants.size)
        val next = state.observe(61, true, proof)
        assertEquals(1, next.balance)
        assertEquals(2, next.grants.size)
        assertTrue(next.grants.all { grant -> grant.none { it in next.protected } })
    }

    @Test
    fun `protected days cannot bootstrap an unlimited reward cycle`() {
        var state = days(90).observe(94, true, proof).repair(94)
        repeat(100) { state = state.award(94) }
        assertEquals(0, state.balance)
        assertEquals(3, state.grants.size)
    }

    @Test
    fun `repair crossing multiple milestones only presents latest and consumes all`() {
        val base = days(300).celebration(300).first
        val state = base.observe(311, true, proof).repair(311)
        val (marked, count) = state.celebration(311)
        assertEquals(308, count)
        assertNull(marked.celebration(311).second)
        assertEquals(0, marked.balance)
    }

    @Test
    fun `stale repair response does not spend cards`() {
        val state = days(60).observe(63, true, proof)
        assertEquals(state, state.repair(62))
        assertEquals(state, state.repair(64))
    }

    @Test
    fun `new run requires 30 new real completions`() {
        var state = days(59).observe(61, true, proof).decline(61)
        for (day in 62..89) state = state.observe(day, true, proof)
        assertEquals(1, state.balance)
        state = state.observe(90, true, proof)
        assertEquals(2, state.balance)
    }

    @Test
    fun `invalidated evidence removes unspent entitlement without reissuing on restore`() {
        val state = days(30)
        val invalid = state.copy(completed = state.completed - 15)
        assertEquals(0, invalid.balance)
        assertEquals(15, invalid.current(30))
        val restored = invalid.copy(completed = state.completed).award(30)
        assertEquals(1, restored.balance)
        assertEquals(1, restored.grants.size)
    }

    @Test
    fun `undo final completion removes today and its reward`() {
        val state = days(30).observe(30, false, proof)
        assertFalse(30 in state.completed)
        assertEquals(29, state.current(30))
        assertEquals(0, state.balance)
    }

    @Test
    fun `pending repair and all metadata survive restart`() {
        val state = days(60).observe(63, true, proof)
        assertEquals(state, Json.decodeFromString<StreakState>(Json.encodeToString(state)))
    }

    @Test
    fun `longest is derived from evidence rather than a stale counter`() {
        val state = days(30).observe(35, true, proof).decline(35)
        assertEquals(30, state.longest)
        assertEquals(1, state.current(35))
    }

    @Test
    fun `historical revlog alone never initializes a streak`() {
        assertEquals(0, StreakState().current(1000))
    }
}
