// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StreakServiceTest : RobolectricTest() {
    @get:Rule
    val streakFolder = TemporaryFolder()

    private val store get() = StreakStore(AtomicFile(File(streakFolder.root, "streak.json")))

    @Before
    fun alignClocks() {
        TimeManager.reset()
    }

    @Test
    fun `observer only records after all scheduled answers and survives store restart`() {
        addBasicNote("one")
        addBasicNote("two")
        assertTrue(StreakService.updateWithStore(col, store).completed.isEmpty())
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(StreakService.updateWithStore(col, store, answered = true).completed.isEmpty())
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val state = StreakService.updateWithStore(col, store, answered = true)
        assertEquals(1, state.current(col.sched.today))
        assertEquals(state, store.read())
        assertEquals(state, StreakService.updateWithStore(col, store))
    }

    @Test
    fun `first answer fixes scope for day even after plan setting changes`() {
        addBasicNote("one")
        addBasicNote("two")
        val first = col.decks.selected()
        val second = col.decks.id("Other")
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val state = StreakService.updateWithStore(col, store, answered = true)
        store.write(state.copy(plan = setOf(second)))
        assertEquals(setOf(first), StreakService.updateWithStore(col, store).activePlan)
    }

    @Test
    fun `invalidated today cannot be re-certified by an empty synced queue`() {
        addBasicNote()
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val today = col.sched.today
        val state = StreakService.updateWithStore(col, store, answered = true)
        store.write(state.copy(completed = mapOf(today to state.completed.getValue(today).copy(fingerprint = "replaced"))))
        val after = StreakService.updateWithStore(col, store)
        assertFalse(today in after.completed)
        assertFalse(after.answered)
        assertTrue(StreakService.updateWithStore(col, store).completed.isEmpty())
    }

    @Test
    fun `reviewing a child through its parent certifies the selected child plan`() {
        val parent = col.decks.id("Parent")
        val child = col.decks.id("Parent::Child")
        val card = addBasicNote().firstCard()
        card.did = child
        col.updateCards(listOf(card), skipUndoEntry = true)
        col.decks.select(parent)
        store.write(StreakState(plan = setOf(child)))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val state = StreakService.updateWithStore(col, store, answered = true, answeredDeckId = child)
        assertTrue(col.sched.today in state.completed)
    }

    @Test
    fun `changed review evidence invalidates historical completion without changing revlog`() {
        addBasicNote()
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val today = col.sched.today
        val state = StreakService.updateWithStore(col, store, answered = true)
        // Simulate a sync replacing historical numeric review evidence.
        val historical = state.completed.getValue(today).copy(fingerprint = "different-history")
        store.write(state.copy(completed = mapOf(today - 1 to historical), activePlan = emptySet(), answered = false))
        val reviews = col.db.queryLongScalar("select count(*) from revlog")
        val after = StreakService.updateWithStore(col, store)
        assertFalse(today - 1 in after.completed)
        assertEquals(reviews, col.db.queryLongScalar("select count(*) from revlog"))
    }
}
