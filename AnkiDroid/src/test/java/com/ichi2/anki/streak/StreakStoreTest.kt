// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFails

@RunWith(AndroidJUnit4::class)
class StreakStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `restart preserves the entire atomic repair result`() {
        val file = folder.newFile()
        val store = StreakStore(AtomicFile(file))
        val proof = CompletionProof(1, 2, "proof")
        val before = (1..60).fold(StreakState()) { s, d -> s.observe(d, true, proof) }.observe(63, true, proof)
        store.write(before)
        val repaired = store.read().repair(63)
        store.write(repaired)
        assertEquals(repaired, StreakStore(AtomicFile(file)).read())
    }

    @Test
    fun `failed transaction retains original card balance and all days`() {
        val file = folder.newFile()
        val atomic = AtomicFile(file)
        val store = StreakStore(atomic)
        val state = StreakState(spent = 2, protected = setOf(31, 32))
        store.write(state)
        val interrupted = atomic.startWrite()
        interrupted.write("incomplete write".toByteArray())
        atomic.failWrite(interrupted)
        assertEquals(state, store.read())
    }

    @Test
    fun `corrupt ledger is not silently reset to a new reward account`() {
        val file = folder.newFile()
        file.writeText("broken")
        assertFails { StreakStore(AtomicFile(file)).read() }
        assertEquals("broken", file.readText())
    }
}
