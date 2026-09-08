# Concurrency

Five places in this system can be hit by two requests at once in a way that matters. This
is what each one does, why that mechanism and not the other one, and — for one of them — what
was wrong with the first attempt.

Six files in the backend cite this document by name: `Room`, `AllocationService`,
`FeeReminder`, `HostelFee`, `FeePaymentRepository` and `PaymentService`.

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

## 3. Payment settlement — two row locks, in that order

**The race.** A gateway redelivers its callback, or a student's browser fires it twice.
Crediting is read-modify-write on `hostel_fees.amount_paid_paise`: under READ COMMITTED both
transactions read the old balance and the second write loses the first. A student who paid
twice sees one payment vanish from the invoice while its `fee_payments` row still says
`SUCCEEDED`. It is §1's double-booking in a different table.

**The mechanism.** `settle` verifies the signature, then takes **two** row locks before it
credits anything: `FeePaymentRepository.findByIdForUpdate` on the attempt, then
`HostelFeeRepository.findByIdForUpdate` on the invoice. Concurrent callbacks for one attempt
serialise on the first; concurrent callbacks for different attempts against one invoice
serialise on the second.

**What happens when the invoice cannot absorb the payment.** `HostelFee.applyPayment` throws
rather than overshooting. The `fee_payments` row is then left `PENDING` — deliberately not
`FAILED`, because the money *has* been captured at the provider and `FAILED` would assert it
has not. A `PENDING` row carrying a provider payment reference against a settled invoice is
the state that needs a human and a refund, and it is logged at `ERROR`.

### Why two locks, and not one

> **This was an open defect for two days.** Found 2026-08-25 by reading the method, raised,
> deferred, fixed 2026-08-27. The trace below is what it did, kept because the reasoning is
> the reason the second lock exists.

The fee lock serialises the two transactions; on its own it does not stop either from
proceeding. `FeePayment` has no `@Version`, so with a 10 000-paise invoice and one `PENDING`
payment of 5 000 the invoice lock alone gave this:

1. Both callbacks read the `fee_payments` row as `PENDING` and both pass the
   `payment.getStatus().isSettled()` guard.
2. T1 takes the fee lock, credits 5 000, marks the payment `SUCCEEDED`, commits.
3. T2 takes the fee lock, re-reads `paid = 5 000`, and `applyPayment(5 000)` legitimately
   reaches 10 000 — the invoice has room, so nothing throws.
4. `payment.succeed(...)` checks `requirePending()` against **T2's own stale in-memory
   copy**, which still says `PENDING`, so it passes. Hibernate's
   `UPDATE ... WHERE id = ?` overwrites T1's row.

The invoice ends up marked fully paid on the strength of one 5 000-paise payment.

A **full-amount** payment was protected throughout, but only incidentally: `applyPayment`
refuses to overshoot, so the second credit failed the balance check. That incidental
protection is exactly why the defect survived a passing test suite — see "the shape of the
test" below. `uq_fee_payments_provider_ref` could not help either, because both writes target
the same row id.

Three candidate fixes were written down. The first was taken:

| Fix | Schema change? | |
| --- | --- | --- |
| Add `findByIdForUpdate` on `FeePayment` and lock the payment row too | No | **taken** |
| Add `@Version` to `FeePayment` | Yes — needs a column | rejected: the schema is frozen |
| Reorder so the payment lock is taken before the fee lock | No | that is the same fix; the ordering is part of it |

`@Version` would also be the wrong shape here even with a free hand at the schema. Optimistic
locking hands the loser an exception to retry, and a redelivered callback does not want a
retry — it wants a 409. The pessimistic lock lets the loser read the winner's committed row
and answer `PAYMENT_ALREADY_SETTLED`, which is a true statement about the payment rather than
a request to try again.

### The part that is not obvious

**Locking the row is not the same as reading it under the lock,** and getting this wrong
leaves the defect in place with a `FOR UPDATE` sitting on top of it.

`settle` used to resolve the callback by loading the `FeePayment` entity by order reference.
Adding `payments.findByIdForUpdate(payment.getId())` after that does nothing for freshness:
Hibernate finds the id already in the persistence context, returns **the instance it already
has**, and throws away the row state its own `SELECT ... FOR UPDATE` just returned. T2 would
block on the lock, wait for T1 to commit, acquire the lock, and then evaluate
`isSettled()` against the copy it read before T1 committed. Both transactions still pass the
guard, the second still overwrites the first, and the code now *looks* correct.

So the entity's first and only read has to be the locked one. The callback is resolved instead
through `findAttemptRefsByProviderOrderId`, a JPQL constructor expression returning
`FeePaymentAttemptRef(id, provider)` — two scalars, nothing managed. That leaves the session
empty until `findByIdForUpdate`, whose read is therefore the one the guard sees, and Postgres'
EvalPlanQual re-read hands it the winner's committed `SUCCEEDED`.

Reading scalars first also preserves the security ordering that was already there: the
provider comes off the stored row, so the caller cannot choose whose secret verifies their
signature, and the signature is checked **before** any lock is taken — a forged callback
cannot make real ones queue behind it. Putting `@Lock` on the order-reference finder would
have been fewer lines and would have given that up.

**Lock ordering.** Attempt, then invoice, always. Nothing else in the codebase takes both, so
there is no cycle to deadlock on today; the ordering is written down in `PaymentService`'s
class Javadoc so that a future caller needing both takes them the same way round.

### The shape of the test, which is the other half of the lesson

`concurrentRedeliveriesCreditTheInvoiceOnce` races six redeliveries of a *full-amount* payment
and asserts one 200 against five 409s. It passed for as long as the defect existed, and it was
not a bad test — it just could not see this bug, because `applyPayment`'s overshoot refusal
does its work for it. A green test over a case with two protections tells you at least one of
them holds, and never which.

`concurrentRedeliveriesOfAPartPaymentCreditTheInvoiceOnce` is the fixture with no accidental
help: half an invoice leaves room, so the domain model has no objection and the lock is the
only thing left. It asserts the invoice holds exactly `HALF`, is `PARTIALLY_PAID`, carries one
`SUCCEEDED` row, and returned one 200 and five `PAYMENT_ALREADY_SETTLED` — and, last, that the
remaining half is still payable afterwards, since a fix that deadlocked the student out of
finishing would be worse than the bug. Traced against the old code it fails on its first
assertion.

It was not committed while the defect stood open, on the reasoning in rule of thumb 6: a
knowingly-failing committed test teaches the next person to delete it rather than read it. It
is committed now that it is expected to pass — and "expected" is the honest word, because it
needs Docker and has not run yet. See the verification table below.

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

## 6. What the integration runs found

The suite's first real execution (CI run #3, 2026-08-26) put 126 executions through a real
PostgreSQL. Forty-five failed. They reduced to four root causes — three production defects
and one set of test-side mistakes — and the three defects are the argument for this suite
existing, because **every one of them lives in behaviour a mock cannot have**: a DDL type,
and two transaction rollback semantics. The unit suite was green throughout — 229 tests at
that commit, 231 once these fixes brought their own tests with them. §6.5 is the one thing
the second run added.

**6.1 Enum columns: `CHAR(1)` where the schema says `VARCHAR(1)`.** Hibernate 6 maps
`@Enumerated(STRING)` plus `@Column(length = 1)` to `CHAR(1)`; `V1__init.sql` declares
`VARCHAR(1)`. Only `SchemaAgreementIT` runs with `ddl-auto=validate`, so only it noticed —
and a `SchemaManagementException` aborts the entire persistence unit, so one mismatched
column took all 35 of that class's executions down with it. Fixed with
`@JdbcTypeCode(SqlTypes.VARCHAR)` on `Notice.audienceGender`, `Student.gender` and
`Room.eligibleGender`. The entity moved, not the migration: the schema is frozen.

**6.2 A lost reminder race counted as a failure.** `FeeReminderDispatch.sendFor` caught
`DataIntegrityViolationException` and returned `SKIPPED`. That could not work. The failed
flush had already marked its `REQUIRES_NEW` transaction rollback-only, so the commit on the
way out threw `UnexpectedRollbackException`, the caller's `catch (RuntimeException)` counted
it `FAILED`, and the returned `SKIPPED` was discarded. Fixed by letting the violation
propagate — Spring then rolls back and rethrows with no commit attempted — and classifying
it in `FeeReminderService.outcomeFor`. The `saveAndFlush` had to stay: a deferred flush
surfaces the duplicate only at commit, which is after the email has gone out.

**6.3 Refresh-token containment, rolled back by its own refusal.** On reuse detection
`RefreshTokenService.rotate` revoked the whole token family and then threw `ApiException`.
Unchecked, so Spring's default rollback rule took the revocation down with the rotation: the
bulk `UPDATE` reached the database and was undone on the way out. Nothing observable
disagreed — the 401 still went back, the error code was still right, and the log still
announced that the family had been cut — so reuse detection was inert in production, and the
live successor of a stolen chain kept rotating indefinitely. The same bug silently spared a
deactivated account's tokens. `AuthFlowIT` caught it the only way it can be caught: by
reading the rows back after the transaction had ended. Fixed by moving the revocation into
`RefreshTokenRevoker`, a separate bean under `REQUIRES_NEW`, so containment commits *before*
the refusal that follows it. Separate bean because Spring's advice is on a proxy — a
`REQUIRES_NEW` method called on `this` would rejoin the doomed transaction and restore the
bug with the annotation still sitting there.

That last one is the sharpest illustration of the rule this document opens with. A mock
verifying `revokeAllForUser` was called passed for as long as the defect existed, and was
right: the call *was* made. Whether it survived is not a question a mock can be asked.

**6.4 Test-side, for completeness.** `ApplicationLifecycleIT` compared a `String` return to
an enum constant, queried a `decided_by_id` column that does not exist (it is `decided_by`),
and expected a username where the response carries a display name.

**6.5 A fourth test-side mistake, found by the second run.** `applyingCreatesAPendingApplication`
asserted `path("decidedAt").isNull()` on a pending application. The app serialised with
`default-property-inclusion: non_null` at the time, so an undecided application omitted
`decidedAt` altogether rather than sending it as `null` — `path` returns a `MissingNode`,
whose `isNull()` is `false`. Its sibling in `approvalAllocates` asserted the same predicate
was `isFalse()` and so passed for the wrong reason: a missing field would have satisfied it
just as well as a real timestamp, which is the one thing that assertion existed to rule out.
Both now use `hasNonNull`, which asks whether there is a decision timestamp rather than what
Jackson does with absent ones. That inclusion setting is `always` as of 2026-08-31:
omitting a key the TypeScript contract declares as `T | null` is invisible to `tsc` and
false to a `=== null` guard, which crashed the student landing page on the next line. Both
assertions were already written not to care which shape arrives, and neither changed.

**What the second run said.** CI run #4 (2026-08-27) put the same 126 executions through the
fixed tree and 125 passed. All three production defects are confirmed fixed by a real run,
not merely by reasoning: `SchemaAgreementIT` 35/35 for §6.1, `FeeReminderIdempotencyIT` 9/9
for §6.2, and `AuthFlowIT` 22/22 for §6.3 — including the `refreshing` tests that read the
token rows back after the transaction ended, which is the only way §6.3 was ever visible. The
lone failure was §6.5, above.

---

## What is actually verified

| Claim | Test | Has it run? |
| --- | --- | --- |
| Allocation cannot over-fill a room | `AllocationConcurrencyIT` (7 tests) | **Yes — 7/7, CI run #4** |
| Initiation cannot double-charge | `PaymentCallbackIT` | **Yes — 14/14, CI run #4** |
| Full-amount settlement is race-safe | `PaymentCallbackIT` | **Yes — same run** |
| Partial settlement is race-safe | `PaymentCallbackIT` (added 2026-08-27) | **Yes — locally, 2026-08-31, and negatively controlled** |
| Reminders are once per invoice per day | `FeeReminderIdempotencyIT` (9 tests) | **Yes — 9/9, CI run #4** |
| The absence scan converges | `AbsenceScanIdempotencyIT` (8 tests) | **Yes — 8/8, CI run #4** |

Run #4 rather than run #3 for the first three: run #3 executed against a tree carrying the
three defects in §6, so run #4 is the first result that describes the code as it then stood.

**The integration suite has now executed three times: twice in CI, then once locally.**
Run #3 put 126 executions through a real PostgreSQL and 45 failed, reducing to four root
causes: three production defects and a set of test-side mistakes, all set out in §6. Run #4
re-ran the same 126 against the fixed tree and 125 passed, the one failure being a fourth
test-side mistake (§6.5). On 2026-08-31 a Docker daemon became available on the development
machine, and `mvnw verify` ran the whole tree there: 232 unit tests and 127 integration
executions, 0 failures and 0 errors, against `postgres:16.4-alpine`. CI is still the gate,
but it is no longer the only place these claims can be tested.

**The newest row has now run, and it was checked the other way round as well.** §3's
attempt lock landed on 2026-08-27, after run #4, and it brought
`concurrentRedeliveriesOfAPartPaymentCreditTheInvoiceOnce` with it — 127 executions now
rather than 126. The local run of 2026-08-31 executed it against a real PostgreSQL and it
passed.

A green test is weak evidence on its own, and this document says so about §6: run #3 was
green on the full-amount race while the partial-settlement defect was live, because
`HostelFee.applyPayment` refuses to overshoot and so protected that case incidentally. The
new test was therefore negatively controlled. Replacing the locking read in `settle` with the
unlocked `payments.findById(attempt.id())` — the shape of the code before the fix, invoice
lock still in place — makes it fail exactly as §3's trace predicts:

```
[paise credited to invoice 1 by 6 simultaneous callbacks for one 2250000-paise attempt]
expected: 2250000L
 but was: 4500000L
```

Half the invoice credited twice, one 22,500-paise payment settling a 45,000-paise invoice in
full. Restoring `findByIdForUpdate` returns the class to 15/15. So the assertion is
load-bearing rather than incidentally true, and the §3 fix is proven rather than argued.

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
6. **A known defect written down beats a failing test committed** — and the test goes in the
   moment the defect goes out. §3 stood open for two days in three places, and the fixture
   that proves it fixed was written before the fix and held back until it passed.
7. **A lock is only as good as the read that carries it.** If the row is already in the
   session, `FOR UPDATE` buys serialisation and nothing else — §3.
