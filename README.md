# hostel-ops

Hostel operations for a college: applications, bed allocation, attendance, absence
alerts, fees, gateway payments, complaints and notices — behind one login with
three consoles (warden, student, admin).

Spring Boot 3.5 · PostgreSQL 17 · Next.js 16 · Java 17 · TypeScript 5.9

This is a rebuild. The original was a Django app with jQuery templates that kept a
cached `occupied_beds` counter on each room, and would happily report a full room
that had free beds. Getting that one thing right — and being able to *prove* it —
is most of why the rest of the system looks the way it does.

**If you read one file, read [`docs/concurrency.md`](docs/concurrency.md).** It is
the argument this repository exists to make.

---

## Running it

```bash
cp .env.example .env
# put a JWT_SECRET in it: openssl rand -base64 48
docker compose up --build
```

- Console: <http://localhost:3000>
- API docs: <http://localhost:8080/swagger-ui.html>

Sign in as `admin`, `lh_warden`, `mh_warden`, or one of 24 students such as
`asha.rao`, using whatever you set as `DEV_SEED_PASSWORD`. With the `dev` profile,
those accounts and dashboard-ready sample records are seeded idempotently. Leave
that variable empty and no demo data is created at all — there is no hardcoded
fallback password anywhere in the codebase.

Running the two halves directly instead: [`backend`](backend) needs a Postgres and
the environment variables in `.env.example`; [`frontend/README.md`](frontend/README.md)
covers the console, including the one setting that trips everybody up
(`REFRESH_COOKIE_SECURE=false` for local HTTP).

---

## Layout

```
backend/     Spring Boot — 189 source files, 26 controllers, 72 endpoints
frontend/    Next.js App Router — 29 routes, 47 source files
docs/        concurrency.md
```

Two Flyway migrations own the schema outright: `V1__init.sql` and
`V2__room_reference_data.sql`. Hibernate runs with `ddl-auto: none` and never
infers or alters anything, and `SchemaAgreementIT` boots with `validate` purely to
assert the entities and the migrations still agree — so drift fails CI instead of a
production start.

---

## What is worth looking at

**Concurrency is enforced by the database, never by a check in Java.** A read
followed by a write is two statements, and the gap between them is where the bug
lives. So bed allocation takes a `SELECT ... FOR UPDATE` on the room; payment
settlement takes one on the attempt and then on the invoice, in that order; payment
idempotency rests on `uq_fee_payments_idempotency` rather than on the lookup that
precedes it; the absence scan is idempotent because it is a function of the
attendance register rather than of its own history. Each choice — including the two
that were rejected — is set out in [`docs/concurrency.md`](docs/concurrency.md).

**Money is an integer count of paise from the database to the browser.** No float
ever holds a currency value. `Intl.NumberFormat` divides by 100 at the last
possible moment, inside the formatter.

**`LocalDate` is a string end to end.** `new Date('2026-08-26')` is midnight UTC
and renders as the 25th for anyone west of Greenwich, which is an attendance
register that shifts by a day depending on who reads it. Dates that are moments
are `Instant` and are parsed; dates that are days are split.

**The audit trail is an aspect, not a call at each site.** `@Audited` on a service
method records actor, entity, action and a payload diff, with
`password|passwd|secret|token|credential|signature|otp|pin` redacted before the row
is written. `/admin/audit` reads it by entity, by actor, or whole.

**Authorization is a scope, not a role check.** A warden sees one hostel's students
because `AccessScope` narrows every query, and `WardenScopeIT` asserts the
narrowing from the outside — a warden reaching another hostel's URL gets a 403 from
Spring, not a redirect from the client.

**No token in `localStorage`.** The access token lives in a module variable and is
lost on reload; the refresh token is an httpOnly `Secure` `SameSite=Strict` cookie
the JavaScript cannot read. There is no BFF and no proxy route, which is a decision
with consequences — argued in `frontend/src/lib/session.ts`.

---

## Tests

| | |
| --- | --- |
| Unit | **232**, passing (`cd backend && ./mvnw test`) |
| Integration | **127** executions across 8 classes, passing (`cd backend && ./mvnw verify`) |

The integration tests need a Docker daemon for Testcontainers. One is available as of
2026-08-31, so they now run locally as well as in `.github/workflows/ci.yml`; the full
`verify` is green there — 232 unit and 127 integration executions, 0 failures, 0 errors,
against `postgres:16.4-alpine`. CI is still the gate on pull requests.

That first real run earned its keep: it found three production defects that a fully green
unit suite had all missed — 229 tests at that commit, all passing — because every one of
them lives in behaviour a mock cannot have: an entity/DDL type mismatch, and two
transactions whose rollback semantics were wrong. All three are fixed and a second CI run
confirms it; `docs/concurrency.md` §6 has the detail.

The last of the 127 to be proven was `concurrentRedeliveriesOfAPartPaymentCreditTheInvoiceOnce`,
which arrived with the §3 fix on 2026-08-27, after the last CI run, and first executed on
2026-08-31. It also got the check a concurrency test actually needs: with the attempt lock
removed it fails, crediting a 22,500-paise part payment twice against a 45,000-paise invoice.
That is what makes its green result mean something — `docs/concurrency.md` §"What is actually
verified" has the output.

```bash
cd backend && ./mvnw verify   # unit (surefire) + integration (failsafe) — needs Docker
```

The eight classes are `AuthFlowIT`, `WardenScopeIT`, `ApplicationLifecycleIT`,
`AllocationConcurrencyIT`, `PaymentCallbackIT`, `FeeReminderIdempotencyIT`,
`AbsenceScanIdempotencyIT` and `SchemaAgreementIT`. The middle four are the ones
that matter — they are the only executable evidence for the claims in
`docs/concurrency.md`.

---

## Known limitations

Stated rather than discovered later:

- **The integration suite's first run found three production defects** — see
  [`docs/concurrency.md`](docs/concurrency.md) §6 — all three since confirmed fixed. It
  ran in CI only until 2026-08-31; it now also runs locally, where `mvnw verify` puts 232
  unit tests and 127 integration executions through `postgres:16.4-alpine` with 0
  failures. CI remains the gate.
- **No frontend tests.** The typecheck is strict and `next build` fails on a type
  error, which catches contract drift against `types.ts` and nothing about
  behaviour.
- **Notifications are logged, not sent.** There is no mail transport wired up; the
  reminder path proves its ordering and its idempotence, not its delivery.
- **`mock` is the default payment gateway.** The Razorpay adapter implements the
  same signature scheme and the same interface, and has never been pointed at the
  real provider.
- **No rate limiting outside `/auth`.** Login is throttled per
  (username, client IP) — a campus behind one NAT address must not lock itself out
  collectively. Nothing else is.
