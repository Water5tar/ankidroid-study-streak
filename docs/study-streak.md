<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Local study streak

This downstream feature targets v2.25.3 (d9bf072f814a0c37d30a694192593b31ff2df875).
It was implemented with OpenAI Codex assistance. It is not an upstream contribution;
upstream AI_POLICY.md and contribution eligibility must be considered separately.

## Integration and plan semantics

The deck picker toolbar opens a local streak detail dialog with a 42-learning-day
calendar, current/longest run, repair balance, plan selection and two celebration
switches. Filled circles are completed plans; diamonds are protected days.

Select one or more decks before the first answer. Descendants are included.
An empty selection defaults to the deck selected for the first answer that day.
The first answer freezes the daily scope. Later changes affect the next learning
day. This makes 'all tasks' explicit without making an unrelated unused deck
block completion. Parent plans include child decks. Existing deck daily limits
continue to define which new/review cards belong to today's work.

Both the classic Reviewer and the newer ReviewerViewModel report successful
answers after the existing undoable operation, and
refreshes the observer when the current card changes (including undo). DeckPicker
refreshes after collection refresh and when creating its toolbar. The observer is
isolated from ordinary review errors: unavailable streak metadata never prevents
reviewing or answering cards.

StreakPlan uses the native scheduler's deck due tree, including its daily limits,
filtered-deck handling and FSRS scheduling, and requires zero new/review/learning
counts for every selected root. It additionally reads intraday learning and
filtered preview queues due before scheduler.dayCutoff, so a learning card beyond
learn-ahead does not incorrectly qualify as finished. Buried/suspended negative
queues do not block completion. No selection changes are made to probe decks.

Completion also requires an observed successful answer in the daily scope.
Empty plans and opening the app alone never qualify. Filtered preview sessions
can qualify once their tasks are exhausted: their local answer observation is
needed because non-rescheduling previews may not produce actual revlog rows.

## Boundaries and evidence

Keys use scheduler.today, not wall-clock midnight. Revlog evidence starts at the
previous local rollover reconstructed from scheduler.dayCutoff and the configured
rollover hour (including nonexistent DST hours, not a fixed 86,400 seconds).
Calendar labels are derived from
the current rollover and local zone. Backward day movement does not mint or
overwrite completions; the user must reach the previously observed learning day.

Revlog alone does not preserve historical daily plan, options, or due queues.
Consequently no pre-installation streak is fabricated. A completion certificate
records the observed successful completion plus a SHA-256 fingerprint of revlog
rows up to the observation. Fingerprints are read-only and use raw numeric fields.

On a deck-picker refresh, changed evidence (undo, deletion, import, full sync)
invalidates certificates and recomputes derived streak counts. Paid protected
dates remain permanent local facts; reopening today's plan with dynamic tasks
cannot discard protection already purchased or charge the same dates again.
Invalidated current-day evidence requires a fresh successful answer to recertify.
On review, only current-day evidence is checked to avoid re-reading years of
history per answer. Appended reviews after the certificate do not invalidate it.
Historical preview-only activity without revlog cannot be independently checked
after sync; the persisted local completion observation remains its evidence.
Historical insertions inside its interval conservatively invalidate it: this
feature cannot reconstruct the old plan. Remote-device reviews alone cannot
certify plan completion. Importing a different collection with a different
creation timestamp gets separate metadata. This is local-only, not cross-device
streak synchronization or tamper-resistant anti-cheat.

## Pure rules and persistence

StreakState is an immutable serializable value. Current and longest runs are
derived from completion and protection days. Today's unfinished state preserves
yesterday's run until the day ends. A gap followed by a genuine completed plan
persists a pending repair decision, including when cards are insufficient.
Dismissal preserves the choice; if a new learning day starts, the old pending
choice is declined. A stale dialog cannot repair a different day.

Repair requires N available cards for N consecutive missing days. The domain
returns either the unchanged value or a complete replacement containing all
protected dates and the entire debit. The replacement is committed once through
Android AtomicFile. I/O errors retain the old file; malformed or unknown-version
metadata is not silently reset. CollectionManager.withCol serializes operations.

One card is awarded for each group of 30 previously uncredited REAL completion
days in the current continuous run. Protected days extend that run but are never
reward evidence. A declined/unaffordable gap restarts the real-day accumulation.
Grant receipts retain their exact 30 days permanently. Lost evidence removes
that grant from available entitlement, and spent cards are never duplicated or
refunded by recalculation. Restoring evidence can restore entitlement but cannot
issue a second receipt for those same days. Balance is clamped at zero when
previous spending exceeds remaining valid entitlement.

Milestones use each seventh day's identity in a run; consumed receipts survive
restarts. When a repair crosses several milestones, all are consumed and only
the latest is presented. Receipts are saved before playback, so an interrupted
playback may be skipped but cannot replay on restart. Disabled celebrations are
also consumed. A new independent run can celebrate its own seven-day milestones.

Metadata: application-private files/study-streak-<SHA256(path:collection-crt)>.json.
It includes the ledger, grant and celebration receipts, spending, pending choice,
daily/configured plan and settings. It is not written into collection schema,
revlog, scheduler settings, FSRS, or AnkiWeb configuration. App-data deletion
removes it; normal collection export/sync does not carry it.

## Assets and accessibility

The badge uses the platform's fire glyph. Animation is an original 1.4-second
scale pulse, dismissed after 1.6 seconds. Existing AnkiDroid animation preferences
and Android animator scale are respected. Each calendar cell exposes a full
date/status description and uses distinct shapes, not color alone.
Sound is Android ToneGenerator's synthesized TONE_PROP_ACK at 25% generator
volume on STREAM_MUSIC. It respects media volume and bundles no audio assets.
Transient celebrations stop when the deck-picker activity pauses.
No Duolingo assets or new libraries are used. New source uses GPL-3.0-or-later.

## Validation

New tests cover the pure ledger, reward receipts, atomic repair, restarts,
day boundaries/DST, AtomicFile rollback and native scheduler plan semantics.
Observer tests exercise plan freezing, complete/partial plans, child-deck answers,
store restarts and invalidated current/historical evidence. Classic and newer
reviewer paths report answers. A process death between the review commit and the
local metadata commit can undercount a day; an empty queue alone never backfills it.
The accompanying delivery report records actual command results and limitations;
the existence of a test is not evidence that it passed.
