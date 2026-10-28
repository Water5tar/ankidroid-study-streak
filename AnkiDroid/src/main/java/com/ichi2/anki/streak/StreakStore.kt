// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import android.content.Context
import android.util.AtomicFile
import com.ichi2.anki.libanki.Collection
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Local, collection-scoped metadata. Never writes to collection.anki2 or AnkiWeb config. */
class StreakStore internal constructor(
    private val file: AtomicFile,
) {
    constructor(context: Context, col: Collection) : this(
        AtomicFile(
            File(
                context.filesDir,
                "study-streak-${digest("${col.colDb.canonicalPath}:${col.db.queryLongScalar("select crt from col")}")}.json",
            ),
        ),
    )

    fun read(): StreakState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return StreakState()
        // Fail closed. Never silently replace a damaged/unknown ledger and reissue rewards.
        return Json.decodeFromString<StreakState>(file.openRead().bufferedReader().use { it.readText() }).also {
            require(it.version == 1) { "Unsupported study streak metadata" }
        }
    }

    fun write(state: StreakState) {
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
