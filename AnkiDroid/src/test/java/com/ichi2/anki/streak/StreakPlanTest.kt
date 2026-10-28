// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.QueueType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StreakPlanTest : RobolectricTest() {
    @Before
    fun alignNativeAndKotlinClocks() {
        // RobolectricTest pins Kotlin time to 2020, but Rust's scheduler uses real time.
        // A deck tree requested for 2020 legitimately has different limits/burial state.
        TimeManager.reset()
    }

    @Test
    fun `daily new card limit defines plan rather than every card in deck`() {
        val card = addBasicNote("one").firstCard()
        addBasicNote("two")
        val config = col.decks.configDictForDeckId(card.did)
        config.new.perDay = 1
        col.decks.save(config)
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(
            StreakPlan.complete(col, setOf(card.did)),
            "tree=${col.sched.deckDueTree(topDeckId = card.did)} queue=${col.sched.counts()}",
        )
    }

    @Test
    fun `relearning later today must finish`() {
        val card = addBasicNote().firstCard()
        card.queue = QueueType.Lrn
        card.type = CardType.Relearning
        card.due = (col.sched.dayCutoff - 60).toInt()
        col.updateCards(listOf(card), skipUndoEntry = true)
        assertFalse(StreakPlan.complete(col, setOf(card.did)))
    }

    @Test
    fun `FSRS completes through the same native scheduler without modifying history`() {
        col.config.set("fsrs", true)
        val card = addBasicNote().firstCard()
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        val history = col.db.queryLongScalar("select count(*) from revlog")
        assertTrue(StreakPlan.complete(col, setOf(card.did)))
        assertEquals(history, col.db.queryLongScalar("select count(*) from revlog"))
    }

    @Test
    fun `partial review does not exhaust plan but final answer does`() {
        addBasicNote("one")
        addBasicNote("two")
        val plan = setOf(col.decks.selected())
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertFalse(StreakPlan.complete(col, plan))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(StreakPlan.complete(col, plan))
    }

    @Test
    fun `learning later today blocks completion even outside learn ahead`() {
        val card = addBasicNote().firstCard()
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        card.due = (col.sched.dayCutoff - 60).toInt()
        col.updateCards(listOf(card), skipUndoEntry = true)
        assertFalse(StreakPlan.complete(col, setOf(card.did)))
    }

    @Test
    fun `buried and suspended cards do not block completion`() {
        val one = addBasicNote("one").firstCard()
        val two = addBasicNote("two").firstCard()
        col.sched.buryCards(listOf(one.id), true)
        col.sched.suspendCards(listOf(two.id))
        assertTrue(
            StreakPlan.complete(col, setOf(one.did)),
            "tree=${col.sched.deckDueTree(topDeckId = one.did)} queue=${col.sched.counts()}",
        )
    }

    @Test
    fun `all selected decks must be finished while unselected decks do not block`() {
        val one = addBasicNote("one").firstCard()
        val second = col.decks.id("Second")
        val two = addBasicNote("two").firstCard()
        two.did = second
        col.updateCards(listOf(two), skipUndoEntry = true)
        col.decks.select(one.did)
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(StreakPlan.complete(col, setOf(one.did)))
        assertFalse(StreakPlan.complete(col, setOf(one.did, second)))
        col.decks.select(second)
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(StreakPlan.complete(col, setOf(one.did, second)))
    }

    @Test
    fun `parent plan includes children`() {
        val parent = col.decks.id("Parent")
        val child = col.decks.id("Parent::Child")
        val card = addBasicNote().firstCard()
        card.did = child
        col.updateCards(listOf(card), skipUndoEntry = true)
        assertFalse(StreakPlan.complete(col, setOf(parent)))
    }

    @Test
    fun `missing or empty plan cannot complete`() {
        assertFalse(StreakPlan.complete(col, emptySet()))
        assertFalse(StreakPlan.complete(col, setOf(Long.MAX_VALUE)))
    }

    @Test
    fun `filtered deck uses native limits and completion`() {
        addBasicNote()
        addDynamicDeck("Filtered", "")
        val plan = setOf(col.decks.selected())
        assertFalse(StreakPlan.complete(col, plan))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)
        assertTrue(StreakPlan.complete(col, plan))
    }

    @Test
    fun `probe does not change review history or selected deck`() {
        val card = addBasicNote().firstCard()
        val selected = col.decks.selected()
        val reviews = col.db.queryLongScalar("select count(*) from revlog")
        repeat(3) { StreakPlan.complete(col, setOf(card.did)) }
        assertEquals(selected, col.decks.selected())
        assertEquals(reviews, col.db.queryLongScalar("select count(*) from revlog"))
    }
}
