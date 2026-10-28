// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.streak

import android.animation.ValueAnimator
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.android.animationEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId

/** UI only: collection reads and the single-file commits run on withCol's dispatcher. */
class StreakUi(
    private val activity: AppCompatActivity,
) : DefaultLifecycleObserver {
    private var menu: MenuItem? = null
    private var prompting = false
    private var celebrationJob: Job? = null
    private var repairDialog: AlertDialog? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onPause(owner: LifecycleOwner) {
        celebrationJob?.cancel()
        repairDialog?.dismiss()
    }

    private fun visible(): Boolean = !activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    fun attach(item: MenuItem) {
        menu = item
        item.setOnMenuItemClickListener {
            details()
            true
        }
        refresh()
    }

    fun refresh() {
        activity.lifecycleScope.launch {
            try {
                val (state, day) = withCol { StreakService.update(activity, this) to sched.today }
                menu?.title = activity.getString(R.string.streak_badge, state.current(day))
                if (!prompting && visible()) {
                    if (state.pendingDay == day) {
                        repairPrompt(state, day)
                    } else if (celebrationJob?.isActive != true) {
                        celebrationJob =
                            activity.lifecycleScope.launch {
                                try {
                                    celebrate(day)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    Timber.w(error, "Unable to celebrate study streak")
                                }
                            }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Study streak unavailable")
                menu?.setTitle(R.string.streak_title)
            }
        }
    }

    private fun repairPrompt(
        state: StreakState,
        day: Int,
    ) {
        val missing = state.missingBefore(day)
        val affordable = state.balance >= missing
        prompting = true
        repairDialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.streak_repair_title)
                .setMessage(activity.getString(R.string.streak_repair_message, missing, state.balance))
                .setPositiveButton(if (affordable) R.string.streak_use_cards else R.string.streak_restart) { _, _ ->
                    change { if (affordable) it.repair(day) else it.decline(day) }
                }.setNegativeButton(if (affordable) R.string.streak_restart else android.R.string.cancel) { _, _ ->
                    change { it.decline(day) }
                }.setOnDismissListener {
                    prompting = false
                    repairDialog = null
                }.show()
    }

    private fun change(transform: (StreakState) -> StreakState) {
        activity.lifecycleScope.launch {
            try {
                withCol {
                    StreakService.update(activity, this)
                    StreakService.mutate(activity, this, transform)
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Unable to save study streak")
                AlertDialog
                    .Builder(activity)
                    .setMessage(R.string.streak_save_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun details() {
        activity.lifecycleScope.launch {
            try {
                val snapshot =
                    withCol {
                        val state = StreakService.update(activity, this)
                        Triple(state, sched.today, sched.dayCutoff)
                    }
                val (state, today, cutoff) = snapshot
                val box =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        val padding = (20 * resources.displayMetrics.density).toInt()
                        setPadding(padding, padding, padding, padding)
                    }

                fun label(text: String) =
                    TextView(activity).also {
                        it.text = text
                        it.textSize = 16f
                        it.setPadding(0, 8, 0, 8)
                        box.addView(it)
                    }
                label(activity.getString(R.string.streak_summary, state.current(today), state.longest, state.balance))
                label(activity.getString(if (today in state.completed) R.string.streak_done else R.string.streak_not_done))
                val planNames =
                    withCol {
                        val ids = state.activePlan.ifEmpty { state.plan.ifEmpty { setOf(decks.selected()) } }
                        decks.allNamesAndIds().filter { it.id in ids }.joinToString("\n") { it.name }
                    }
                label(activity.getString(R.string.streak_plan_names, planNames))
                label(activity.getString(R.string.streak_rules))
                val anchor =
                    Instant
                        .ofEpochSecond(cutoff)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .minusDays(1)
                val calendar = GridLayout(activity).apply { columnCount = 7 }
                ((today - 41)..today).forEach { day ->
                    val status =
                        when (day) {
                            in state.completed -> activity.getString(R.string.streak_calendar_done)
                            in state.protected -> activity.getString(R.string.streak_calendar_protected)
                            else -> activity.getString(R.string.streak_calendar_empty)
                        }
                    val date = anchor.minusDays((today - day).toLong())
                    calendar.addView(
                        TextView(activity).apply {
                            text = "${date.monthValue}/${date.dayOfMonth}\n${status.first()}"
                            contentDescription = "$date $status"
                            gravity = Gravity.CENTER
                            textSize = 12f
                            setPadding(0, 10, 0, 10)
                            layoutParams =
                                GridLayout.LayoutParams().apply {
                                    width = 0
                                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                                }
                        },
                    )
                }
                box.addView(calendar)
                label(
                    listOf(R.string.streak_calendar_done, R.string.streak_calendar_protected, R.string.streak_calendar_empty)
                        .joinToString("  ") { activity.getString(it) },
                )

                fun toggle(
                    title: Int,
                    checked: Boolean,
                    update: (StreakState, Boolean) -> StreakState,
                ) {
                    box.addView(
                        SwitchCompat(activity).apply {
                            setText(title)
                            isChecked = checked
                            setOnCheckedChangeListener { _, value -> change { update(it, value) } }
                        },
                    )
                }
                toggle(R.string.streak_animation, state.animation) { s, value -> s.copy(animation = value) }
                toggle(R.string.streak_sound, state.sound) { s, value -> s.copy(sound = value) }
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.streak_title)
                    .setView(ScrollView(activity).apply { addView(box) })
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.streak_plan) { _, _ -> choosePlan() }
                    .show()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Unable to open study streak")
                AlertDialog
                    .Builder(activity)
                    .setMessage(R.string.streak_save_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun choosePlan() {
        activity.lifecycleScope.launch {
            try {
                val (decks, state) = withCol { decks.allNamesAndIds() to StreakStore(activity, this).read() }
                val selected = state.plan.toMutableSet()
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.streak_plan)
                    .setMultiChoiceItems(
                        decks.map { it.name }.toTypedArray(),
                        decks.map { it.id in selected }.toBooleanArray(),
                    ) { _, index, checked ->
                        if (checked) selected.add(decks[index].id) else selected.remove(decks[index].id)
                    }.setPositiveButton(android.R.string.ok) { _, _ -> change { it.copy(plan = selected.toSet()) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Timber.w(error, "Unable to choose study plan")
                AlertDialog
                    .Builder(activity)
                    .setMessage(R.string.streak_save_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private suspend fun celebrate(day: Int) {
        if (!visible()) return
        var milestone: Int? = null
        val state =
            withCol {
                StreakService.mutate(activity, this) {
                    val (next, count) = it.celebration(day)
                    milestone = count
                    next
                }
            }
        val count = milestone ?: return
        // Receipt is committed before playback: a process death cannot replay a milestone.
        var tone: ToneGenerator? = null
        var dialog: AlertDialog? = null
        var animator: ValueAnimator? = null
        try {
            if (state.sound) {
                // Android's synthesized ACK tone: no bundled third-party audio asset.
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 25)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            }
            if (state.animation) {
                val text =
                    TextView(activity).apply {
                        this.text = activity.getString(R.string.streak_celebration, count)
                        textSize = 28f
                        gravity = Gravity.CENTER
                        setPadding(24, 32, 24, 32)
                    }
                dialog = AlertDialog.Builder(activity).setView(text).show()
                val motion = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                if (motion > 0f && activity.animationEnabled()) {
                    animator =
                        ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
                            duration = 1400
                            addUpdateListener {
                                text.scaleX = it.animatedValue as Float
                                text.scaleY = text.scaleX
                            }
                            start()
                        }
                }
            }
            delay(1600)
        } finally {
            animator?.cancel()
            dialog?.dismiss()
            tone?.release()
        }
    }
}
