# Concurrency

Five places in this system can be hit by two requests at once in a way that matters. This
is what each one does, why that mechanism and not the other one, and — for one of them —
what is still wrong.

Three files in the backend cite this document by name: `Room`, `AllocationService` and
`FeeReminder`.

The short version: **every guarantee here rests on a database constraint or a database
lock, never on a check in Java.** A read followed by a write is two statements, and the gap
between them is where the bug lives.

---

## 1. Bed allocation — pessimistic lock

**The race.** Two wardens allocate the last bed in a room at the same moment. Both read
`occupiedBeds = 3` against `capacity = 4`, both find room, both insert. The room now holds
five students in four beds, and no error was raised.

**The mechanism.** `AllocationService` takes the room through
`RoomRepository.findByIdForUpdate` — `SELECT ... FOR UPDATE` — before it looks at capacity.
The second transaction blocks on the row until the first commits, then re-reads
`occupiedBeds = 4` and is refused.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from Room r where r.id = :id")
Optional<Room> findByIdForUpdate(@Param("id") Long id);
```

The lock is on `findById`, deliberately *not* on the scope-filtered finder. Making a
`FOR UPDATE` conditional on authorization would couple locking to permissions — a warden
whose scope excluded the room would take a weaker lock than one whose scope included it,
which is nonsense.

**Why not `@Version` and optimistic locking?** It would work. It was not taken because
allocation is a short, contended, retry-hostile operation:

| | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version`) |
| --- | --- | --- |
| Contention on one room | Second request waits ~milliseconds | Second request fails and must retry |
| Failure surface | None visible to the caller | `OptimisticLockException` → retry loop → still might fail |
| Cost when uncontended | One extra row lock | Nothing |
| Schema | No column | Needs a `version` column |

Rooms are the natural contention point — a warden filling a hostel works one room at a
time — so the optimistic path would push retry logic into the service and an occasional
"please try again" into the warden's face, in exchange for saving a lock that is held for
about a millisecond. `Room` also carries no `version` column, and the schema is frozen.

The pessimistic choice also loses nothing to a stale counter, because there isn't one:
`Room` derives occupancy from its allocations rather than caching a count. A cached
`occupied_beds` column is precisely what let the predecessor system report a full room that
had free beds.

**Belt and braces.** `uq_allocations_active_student` is a partial unique index:

```sql
CREATE UNIQUE INDEX uq_allocations_active_student ON allocations (student_id) WHERE active;
```

The lock stops over-filling a room; the index stops one student holding two beds — including
writes that never go through this service at all: a migration, a `psql` session, a future
endpoint that forgets to lock. `uq_applications_one_pending` does the same job for
applications.

**The proof.** `AllocationConcurrencyIT` fires N simultaneous requests at a room with fewer
than N free beds and asserts exactly `capacity` succeed.

---

## 2. Payment initiation — unique constraint, not a lookup

**The race.** A student's browser sends the same "start payment" request twice — a
double-click, a flaky network, a retry. Two gateway orders open against one invoice.

**The mechanism.** `Idempotency-Key` is a required header, and
`uq_fee_payments_idempotency UNIQUE (idempotency_key)` is the guarantee.
`PaymentService.initiate` does look the key up first, but that lookup is an optimisation for
the ordinary sequential retry — it returns the existing order cheaply. It is **not** what
makes the operation safe. Two copies of the request arriving together both find nothing, and
the second one hits the constraint on insert; `GlobalExceptionHandler` turns that into a
409.

That is the whole point. The window between "check for a duplicate" and "insert one" is
exactly the bug being avoided, so the check cannot be the defence.

**The client's half.** A key that is minted per click satisfies the header's type and
defeats its purpose. The frontend mints one when the pay panel is opened, holds it in state,
and re-sends the same value on every retry of that attempt; changing the amount is a new
attempt and mints a new key. See `frontend/src/app/student/fees/[id]/page.tsx`.

**Ownership is checked on replay.** Keys are chosen by the client and the constraint is
global rather than per student, so a replay is matched to the caller before the existing
order is returned. Without that check, guessing a key would leak somebody else's order.

**Ordering.** `initiate` claims the key by flushing the row *before* it calls the gateway.
If the gateway then fails, the transaction rolls back and the key is released — so a failed
attempt does not burn the key, and the student can retry with the same one.

---

## 3. Payment settlement — row lock, and a known gap

**The race.** A gateway redelivers its callback, or a student's browser fires it twice.
Crediting is read-modify-write on `hostel_fees.amount_paid_paise`: under READ COMMITTED both
transactions read the old balance and the second write loses the first. A student who paid
twice sees one payment vanish from the invoice while its `fee_payments` row still says
`SUCCEEDED`. It is §1's double-booking in a different table.

**The mechanism.** `settle` verifies the signature, then takes the invoice through
`HostelFeeRepository.findByIdForUpdate` before crediting it. Concurrent callbacks against
one invoice serialise on that row.

**What happens when the invoice cannot absorb the payment.** `HostelFee.applyPayment` throws
rather than overshooting. The `fee_payments` row is then left `PENDING` — deliberately not
`FAILED`, because the money *has* been captured at the provider and `FAILED` would assert it
has not. A `PENDING` row carrying a provider payment reference against a settled invoice is
the state that needs a human and a refund, and it is logged at `ERROR`.

### The open defect

> **`settle` can double-credit a *partial* payment under concurrent callback redelivery.**
> Found 2026-08-25 by reading the method. Raised with the project owner, deferred, still
> unfixed as of 2026-08-26.

The fee lock serialises the two transactions; it does not stop either from proceeding.
`findByIdForUpdate` locks the **fee**, not the **payment**, and `FeePayment` has no
`@Version`. So with a 10 000-paise invoice and one `PENDING` payment of 5 000:

1. Both callbacks read the `fee_payments` row as `PENDING` and both pass the
   `payment.getStatus().isSettled()` guard.
2. T1 takes the fee lock, credits 5 000, marks the payment `SUCCEEDED`, commits.
3. T2 takes the fee lock, re-reads `paid = 5 000`, and `applyPayment(5 000)` legitimately
   reaches 10 000 — the invoice has room, so nothing throws.
4. `payment.succeed(...)` checks `requirePending()` against **T2's own stale in-memory
   copy**, which still says `PENDING`, so it passes. Hibernate's
   `UPDATE ... WHERE id = ?` overwrites T1's row.

The invoice is now marked fully paid on the strength of one 5 000-paise payment.

A **full-amount** payment is protected, but only incidentally: `applyPayment` refuses to
overshoot, so the second credit fails the balance check. `uq_fee_payments_provider_ref`
cannot help, because both writes target the same row id.

Three candidate fixes, all in main code:

| Fix | Schema change? |
| --- | --- |
| Add `findByIdForUpdate` on `FeePayment` and lock the payment row too | No |
| Add `@Version` to `FeePayment` | Yes — needs a column |
| Reorder so the payment lock is taken before the fee lock | No |

The first is the smallest correct change and respects the frozen schema.

**What the test suite says about this — and what it does not.**
`concurrentRedeliveriesCreditTheInvoiceOnce` races six redeliveries of a *full-amount*
payment and asserts one 200 against five 409s, which is the guarantee that actually holds.
`partPaymentsAddUp` proves the partial path *sequentially*. The concurrent
partial case is documented in the class Javadoc and **deliberately not asserted**: a
knowingly-failing committed test is worse than a written-down finding, because the next
person deletes it instead of reading it.

---

## 4. Fee reminders — write first, then send

**The race.** The nightly job runs twice — a retry after a crash, an operator triggering it
by hand for a date it already covered — and every student with an invoice due gets two
emails.

**The mechanism.** `uq_fee_reminders_per_day UNIQUE (fee_id, reminder_date)`. One reminder
per invoice per day, enforced by the database.

**The ordering, which is the real decision.** The row is written *before* the message is
sent. That makes delivery **at-most-once**:

- A duplicate reminder is impossible — the constraint has already been claimed.
- A reminder can be *lost*, if delivery fails after the row is written.

The other ordering — send, then record — gives at-least-once: nothing is lost, and a crash
between the two produces a duplicate. For a payment reminder about an invoice with a due
date weeks out, sent by a job that runs daily, losing one and sending it tomorrow is
strictly better than emailing a student twice tonight. Both are defensible; this one is
chosen, and it is chosen rather than stumbled into.

**Observability.** The job reports `invoicesExamined`, `sent`, `skipped` and `failed`
separately, so `sent == 0 && skipped == invoicesExamined` on a repeat run is visible proof
the guard held — as opposed to a job that returns "ok" and leaves an operator unable to
tell "nothing needed doing" from "nothing was done". `/admin/jobs` renders exactly those
four counters for that reason.

**The proof.** `FeeReminderIdempotencyIT` (9 tests).

---

## 5. Absence scan — convergent by construction

**The race.** Same as §4: the scan runs twice for one date.

**The mechanism is different in kind.** There is no "already scanned" marker. The scan
*reconciles* alert state against the attendance register: it raises alerts for new streaks,
extends the ones whose streak has grown, and closes the ones whose streak has ended.
`uq_absence_alert_streak UNIQUE (student_id, streak_start_date)` keys an alert to the streak
it describes rather than to the run that found it, so a second run over unchanged data
computes the same answer and writes nothing.

This is idempotence by making the operation a function of the data rather than of the
history — the more robust of the two shapes, and available here only because the register is
the single source of truth for absence. Reminders cannot work this way: "has this email been
sent" is not derivable from anything but a record that it was.

**One asymmetry worth knowing.** `AbsenceAlertService.raise` counts only days marked
`ABSENT`; `recount` counts every working day in the span. So a streak containing an unmarked
working day is raised at N and *extended* to N+gap by the next scan, then settles. That is
deliberate — an unmarked day is not an absence, but it is not a return either — and it is
pinned by `anUnmarkedWorkingDayIsNeitherAnAbsenceNorAReturn`.

**The proof.** `AbsenceScanIdempotencyIT` (8 tests).

---

## What is actually verified

| Claim | Test | Has it run? |
| --- | --- | --- |
| Allocation cannot over-fill a room | `AllocationConcurrencyIT` (7 tests) | **No** |
| Initiation cannot double-charge | `PaymentCallbackIT` (14 tests) | **No** |
| Full-amount settlement is race-safe | `PaymentCallbackIT` | **No** |
| Partial settlement is race-safe | *not asserted — see §3* | — |
| Reminders are once per invoice per day | `FeeReminderIdempotencyIT` (9 tests) | **No** |
| The absence scan converges | `AbsenceScanIdempotencyIT` (8 tests) | **No** |

**The integration suite has never executed.** It needs Docker for Testcontainers, and the
machine this was built on does not have it. The tests are written and they compile
(`mvnw test-compile` → BUILD SUCCESS), and the 228 unit tests pass (`mvnw test`, 0 failures,
0 errors, verified 2026-08-26). Neither of those is the same thing as a green concurrency
suite, and this document does not claim otherwise. The CI workflow in
`.github/workflows/ci.yml` is where these six claims get their first real run.

---

## Rules of thumb this codebase follows

1. **A check in Java is not a guarantee.** If two requests must not both succeed, the
   database has to be the one saying no — a unique constraint or a row lock.
2. **Lock on the unscoped finder.** Locking must never depend on who is asking.
3. **Choose the ordering, and write down which failure you chose.** Write-then-send loses
   messages; send-then-write duplicates them. Silence about which one is in force is the
   only wrong answer.
4. **Prefer idempotence by reconciliation** — a function of current data — over idempotence
   by marker, when the data can support it.
5. **Report counters, not "ok."** `examined / done / skipped / failed` is what makes a
   repeated run legible.
6. **A known defect written down beats a failing test committed.** §3 is unfixed on purpose
   and says so in three places: here, the `PaymentCallbackIT` Javadoc, and the frontend page
   that takes the payment.
