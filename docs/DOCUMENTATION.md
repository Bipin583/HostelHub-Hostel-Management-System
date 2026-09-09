# hostel-ops — Project Documentation

Hostel operations for a college: applications, bed allocation, attendance, absence
alerts, fees, gateway payments, complaints and notices — behind one login with three
consoles (warden, student, admin).

Spring Boot 3.5 · PostgreSQL 17 · Next.js 16 · Java 17 · TypeScript 5.9

---

### About this document

This is a single self-contained reference for the `hostel-ops-platform` repository,
written to serve three audiences at once: a final-year project report appendix, a
GitHub wiki page, and background material for a presentation.

Everything below was derived from the repository itself — source, migrations, build
files, Dockerfiles, CI configuration and the two existing prose documents
([`README.md`](../README.md) and [`docs/concurrency.md`](concurrency.md)). Some claims
were additionally confirmed by running the stack on **2026-09-03**; those are marked
*(verified at runtime)*. Anything that could not be established from the repository is
marked **Assumption** or **To be confirmed by author** rather than guessed silently.

A short glossary, since the terms recur:

| Term | Meaning in this document |
| --- | --- |
| **Warden** | Staff member who runs one hostel. Sees only their own hostel's students. |
| **Paise** | 1/100 of a rupee. All money here is an integer count of paise, never a decimal. |
| **Invoice / fee** | A `hostel_fees` row: one charge raised against one student. |
| **Attempt** | A `fee_payments` row: one try at paying an invoice through a gateway. |
| **Row lock** | `SELECT ... FOR UPDATE` — the database makes other transactions wait for this row. |
| **Idempotent** | Running it twice has the same effect as running it once. |
| **DTO** | Data Transfer Object: a plain record used to shape a request or response body. |

---

### Contents

- [1. Overview](#1-overview)
- [2. Features](#2-features)
- [3. System Architecture](#3-system-architecture)
  - [3.1 Style](#31-style)
  - [3.2 The pieces](#32-the-pieces)
  - [3.3 Backend responsibilities, layer by layer](#33-backend-responsibilities-layer-by-layer)
  - [3.4 The frontend/backend split](#34-the-frontendbackend-split)
  - [3.5 Database layer](#35-database-layer)
  - [3.6 External APIs](#36-external-apis)
  - [3.7 Background jobs](#37-background-jobs)
- [4. Technology Stack](#4-technology-stack)
  - [4.1 Backend](#41-backend)
  - [4.2 Frontend](#42-frontend)
  - [4.3 Infrastructure](#43-infrastructure)
- [5. Project Structure](#5-project-structure)
  - [Where to look for what](#where-to-look-for-what)
- [6. Setup and Installation](#6-setup-and-installation)
  - [6.1 Prerequisites](#61-prerequisites)
  - [6.2 Clone](#62-clone)
  - [6.3 Configure](#63-configure)
  - [6.4 Run everything with Docker](#64-run-everything-with-docker)
  - [6.5 Database migration](#65-database-migration)
  - [6.6 Running the halves directly](#66-running-the-halves-directly)
- [7. Usage Guide](#7-usage-guide)
  - [7.1 Sign in](#71-sign-in)
  - [7.2 Workflow: a student gets a bed](#72-workflow-a-student-gets-a-bed)
  - [7.3 Workflow: attendance and an absence alert](#73-workflow-attendance-and-an-absence-alert)
  - [7.4 Workflow: raising and paying a fee](#74-workflow-raising-and-paying-a-fee)
  - [7.5 Workflow: a complaint](#75-workflow-a-complaint)
  - [7.6 Workflow: admin oversight](#76-workflow-admin-oversight)
  - [7.7 The console, route by route](#77-the-console-route-by-route)
- [8. API / Interface Documentation](#8-api--interface-documentation)
  - [8.1 Conventions](#81-conventions)
  - [8.2 Error codes](#82-error-codes)
  - [8.3 Authentication - `/api/v1/auth`](#83-authentication---apiv1auth)
  - [8.4 Warden console - `/api/v1/warden`](#84-warden-console---apiv1warden)
  - [8.5 Student console - `/api/v1/student`](#85-student-console---apiv1student)
  - [8.6 Admin console - `/api/v1/admin`](#86-admin-console---apiv1admin)
  - [8.7 Operational endpoints](#87-operational-endpoints)
- [9. Testing](#9-testing)
  - [9.1 Strategy](#91-strategy)
  - [9.2 Commands](#92-commands)
  - [9.3 What lives where](#93-what-lives-where)
  - [9.4 The four tests that carry the argument](#94-the-four-tests-that-carry-the-argument)
  - [9.5 Test prioritisation](#95-test-prioritisation)
- [10. Configuration and Customization](#10-configuration-and-customization)
  - [10.1 Every environment variable](#101-every-environment-variable)
  - [10.2 Fixed settings worth knowing about](#102-fixed-settings-worth-knowing-about)
  - [10.3 Behaviour by environment](#103-behaviour-by-environment)
  - [10.4 Extension points](#104-extension-points)
- [11. Deployment](#11-deployment)
  - [11.1 Local](#111-local)
  - [11.2 Building the images](#112-building-the-images)
  - [11.3 Deploying somewhere real](#113-deploying-somewhere-real)
  - [11.4 CI/CD](#114-cicd)
- [12. Security and Privacy](#12-security-and-privacy)
  - [12.1 Authentication](#121-authentication)
  - [12.2 Authorisation](#122-authorisation)
  - [12.3 Secret management](#123-secret-management)
  - [12.4 Input validation and injection](#124-input-validation-and-injection)
  - [12.5 Privacy](#125-privacy)
  - [12.6 Security limitations, stated rather than discovered later](#126-security-limitations-stated-rather-than-discovered-later)
- [13. Performance](#13-performance)
  - [13.1 Characteristics](#131-characteristics)
  - [13.2 What was done deliberately](#132-what-was-done-deliberately)
  - [13.3 Known bottlenecks](#133-known-bottlenecks)
  - [13.4 Ideas for later](#134-ideas-for-later)
- [14. Known Issues and Limitations](#14-known-issues-and-limitations)
  - [14.1 Functional gaps](#141-functional-gaps)
  - [14.2 Operational gaps](#142-operational-gaps)
  - [14.3 Interface shapes that are not contractually stable](#143-interface-shapes-that-are-not-contractually-stable)
  - [14.4 Documentation drift found while writing this document](#144-documentation-drift-found-while-writing-this-document)
  - [14.5 State left in the local development database](#145-state-left-in-the-local-development-database)
- [15. Future Work / Roadmap](#15-future-work--roadmap)
  - [15.1 Close the gaps that make it not-yet-deployable](#151-close-the-gaps-that-make-it-not-yet-deployable)
  - [15.2 Make the contract stable](#152-make-the-contract-stable)
  - [15.3 Prove more of it](#153-prove-more-of-it)
  - [15.4 Features the domain obviously wants](#154-features-the-domain-obviously-wants)
  - [15.5 Operational maturity](#155-operational-maturity)
- [16. References and Credits](#16-references-and-credits)
  - [16.1 What this project is a response to](#161-what-this-project-is-a-response-to)
  - [16.2 Influences worth naming](#162-influences-worth-naming)
  - [16.3 Licence of this project](#163-licence-of-this-project)
  - [16.4 Third-party dependencies and their licences](#164-third-party-dependencies-and-their-licences)
  - [16.5 Credits](#165-credits)

---

## 1. Overview

`hostel-ops` is a web platform for running a college hostel end to end: students apply
for a place, wardens allocate beds, take the daily attendance register, raise and settle
fee invoices, and work through complaints and notices — all behind one sign-in that
opens one of three role-specific consoles.

It is a rebuild of an earlier Django application whose cached `occupied_beds` counter
drifted from reality and would happily report a full room that still had free beds. The
entire system is therefore organised around one claim — **every operational invariant is
enforced by the database, never by a check in Java** — and around being able to prove
that claim with tests that run genuinely concurrent transactions against a real
PostgreSQL.

For a hostel office the value is an accurate register, an auditable trail of who changed
what, and a payment path that cannot double-credit an invoice. For an evaluator or a
reviewer the value is that each of those claims has a named test and a written argument
behind it — including the two designs that were considered and rejected, and the three
production defects the first integration run caught in code that a fully green unit
suite had passed.

---

## 2. Features

**Identity and access**

- **One sign-in, three consoles.** A single login page routes an account to the warden,
  student or admin console by role, so there is no separate staff URL to leak or guard.
- **Hostel-scoped visibility.** A warden sees exactly one hostel's students because
  every query is narrowed by a scope object, not because a page hides rows — a warden
  who types another hostel's URL gets a 403 from the server *(verified at runtime)*.
- **Login throttling.** Five attempts per fifteen minutes per (username, client IP),
  which stops password guessing without letting one campus NAT address lock out the
  whole campus.

**Rooms, applications and beds**

- **Room inventory as reference data.** 120 rooms and 360 beds across five blocks ship
  in a database migration, so a fresh clone has something to allocate against.
- **Application lifecycle.** A student applies once; a warden approves or rejects with a
  mandatory reason. Approval and allocation are a single transaction, so there is no
  state in which a student is approved with nowhere to sleep.
- **Manual and automatic allocation.** Either pick the room or let the matcher find an
  eligible one by gender, year and free capacity; vacating a bed and the full allocation
  history are both first-class.
- **Live occupancy.** Occupancy per block is counted from the allocation rows on every
  read, so the number cannot drift — which is precisely the bug that motivated the
  rebuild.

**Attendance and absence**

- **Daily register.** Mark a whole hostel in one request (up to 1000 rows) or correct one
  student; marking a future date is refused.
- **Trends and per-student summaries.** Present/absent counts over any date range,
  including a percentage for a single student.
- **Automatic absence alerts.** A nightly scan raises an alert for a run of consecutive
  absent working days, extends it while the run continues, and closes it when the student
  returns. Wardens acknowledge alerts; the scan itself can be re-run any number of times
  for the same date without duplicating anything.

**Money**

- **Invoices in paise.** Amounts are integers from the database to the browser, so no
  invoice is ever off by a fraction of a paisa.
- **Partial payments.** An invoice tracks amount billed and amount paid and moves through
  `UNPAID → PARTIALLY_PAID → PAID`, with `CANCELLED` as a separate terminal state.
- **Gateway payments with an idempotency key.** A payment attempt is identified by a
  client-supplied `Idempotency-Key`; re-sending the same key returns the same attempt
  instead of opening a second order.
- **Signature-verified callbacks.** A settlement callback is HMAC-verified *before* any
  row is locked, and the credit is applied under two row locks taken in a fixed order, so
  a redelivered callback cannot credit the same money twice.
- **Collection analytics.** Billed, collected and outstanding totals per academic term
  with a collection rate.
- **Nightly fee reminders.** One reminder per invoice per day, guaranteed by a unique
  constraint rather than by a flag the job has to remember to set.

**Complaints and notices**

- **Complaint workflow.** Students file complaints in seven categories with an urgency;
  wardens move them `OPEN → IN_PROGRESS → RESOLVED`, and resolving one requires saying
  what was done.
- **Complaint analytics.** Average and 90th-percentile resolution time, queue time versus
  work time, backlog age, and a per-category breakdown.
- **Targeted notices.** A notice can be addressed to a hostel, a gender, a year of study
  or everyone, with an optional expiry after which it stops appearing in the feed.

**Operations and accountability**

- **Audit trail as an aspect.** Every mutating service method annotated `@Audited` writes
  an audit row inside the same transaction as the change, recording actor, entity, action
  and a JSON description of the call — with credential-shaped values redacted.
- **Admin job console.** Both nightly jobs can be re-run on demand for any business date,
  and the run reports counters (`examined / sent / skipped / failed`) rather than "ok".
- **Interactive API docs.** Swagger UI ships with the backend and can be switched off with
  one environment variable *(verified at runtime)*.

---

## 3. System Architecture

### 3.1 Style

Two deployable processes and one database. The backend is a **layered monolith**: a
controller receives HTTP, a service owns the transaction and the business rule, a
repository talks to PostgreSQL. The frontend is a separate Next.js process that renders
the console and calls the backend over HTTP as an ordinary client would.

There is no message broker, no service mesh and no second datastore. Every guarantee the
system makes is made by PostgreSQL, which is a deliberate architectural choice rather
than an omission: a single transactional database is what makes a claim like "this
invoice cannot be credited twice" provable in a test.

### 3.2 The pieces

```text
                     +------------------------------------------+
   browser --------->| Next.js 16 (App Router, standalone)      |
     :3000           |  - 28 routes, 3 console trees            |
                     |  - access token in a module variable     |
                     +------------------------------------------+
        |                                    ^
        |  the browser calls Spring DIRECTLY |  HTML + JS only
        |  (no BFF, no /api proxy route)     |
        v                                    |
   +----------------------------------------------------------------------+
   | Spring Boot 3.5   :8080   /api/v1/**                                 |
   |                                                                      |
   |  Filter chain   JwtAuthenticationFilter -> URL-prefix role gates      |
   |        |                                                             |
   |        v                                                             |
   |  Controller     24 classes, 72 endpoints, DTOs in and out only       |
   |        |                                                             |
   |        v                                                             |
   |  Service        15 classes, @Transactional, @Audited                 |
   |        |                    (audit advice runs INSIDE the tx)        |
   |        v                                                             |
   |  Repository     Spring Data JPA, findByIdForUpdate = row lock        |
   |        |                                                             |
   |  -- seams ---------------------------------------------------------   |
   |  PaymentGateway   mock | razorpay      (chosen at startup)           |
   |  Notifier         LoggingNotifier      (no mail transport wired)     |
   |  Scheduler        FeeReminderJob, AbsenceScanJob (cron, UTC)         |
   +----------------------------------------------------------------------+
                                     | JDBC
                                     v
   +----------------------------------------------------------------------+
   | PostgreSQL 17    schema owned outright by two Flyway migrations       |
   |  CHECK constraints, partial UNIQUE indexes, FOR UPDATE row locks      |
   +----------------------------------------------------------------------+
```

### 3.3 Backend responsibilities, layer by layer

| Layer | Package | Responsibility | Rule it obeys |
| --- | --- | --- | --- |
| Controller | `com.hostelops.controller` | HTTP shape: path, method, status, validation, pagination | Never touches an entity; never opens a transaction |
| Service | `com.hostelops.service` | Business rule, transaction boundary, audit annotation | The only layer that may see an entity |
| Repository | `com.hostelops.repository` | Queries, including the locking ones | Every scoped query takes the caller's scope as a parameter |
| Domain | `com.hostelops.domain` | JPA entities and enums | Mapped to the migration, never generated from it |
| DTO + mapper | `com.hostelops.dto` | Request and response records | An entity never leaves the service layer |
| Security | `com.hostelops.security` | JWT issue and verify, refresh rotation, login throttle | Stateless: there is no server-side session |
| Audit | `com.hostelops.audit` | `@Audited` and the aspect that writes the row | Runs inside the caller's transaction |
| Payment | `com.hostelops.payment` | Gateway interface, two adapters, HMAC signatures | Adapters are stateless and thread-safe |
| Job | `com.hostelops.job` | Cron triggers only | A trigger contains no logic; the service does |
| Notify | `com.hostelops.notify` | The single outbound seam | Logs; sends nothing |
| Exception | `com.hostelops.exception` | `ErrorCode` and the one error body | HTTP status is decided by the code, not the throw site |
| Config | `com.hostelops.config` | Security, transactions, scheduling, OpenAPI, dev seed | - |

**Concretely: what happens when a warden approves an application.** The controller
receives `POST /api/v1/warden/applications/42/approve`, having already passed the JWT
filter and the `/warden/**` role gate. It hands the id and the warden's scope to
`ApplicationService.approve`, which opens one transaction; inside that transaction the
application row moves to `APPROVED`, the matcher looks for an eligible room,
`AllocationService` takes a row lock on that room, counts the active allocations while
holding the lock, inserts the allocation and flips the student to `ALLOCATED`; the audit
aspect then writes one `audit_events` row - still inside the same transaction, so if
anything rolls back the audit row goes with it. The controller maps the result to a DTO
and returns 200.

### 3.4 The frontend/backend split

The browser calls Spring directly. There is no Backend-For-Frontend layer and no
`/api/*` route inside Next that forwards requests, and that decision is argued at length
in [`frontend/src/lib/session.ts`](../frontend/src/lib/session.ts). Its consequences are
concrete, and visible in the backend's own configuration:

- CORS (Cross-Origin Resource Sharing, the browser rule that lets a page on one origin
  call another) must be configured on the backend for the browser's origin, with
  credentials allowed and `Idempotency-Key` among the permitted request headers.
- The refresh cookie is `SameSite=Strict` and scoped to `/api/v1/auth`, because it travels
  to Spring's origin rather than to the Next server.
- `Retry-After` has to be an *exposed* header, or browser JavaScript cannot read the
  throttle hint on a 429 response.
- `NEXT_PUBLIC_API_BASE_URL` must be the address **the browser** can reach. Under Docker
  Compose that is `http://localhost:8080`, never `http://backend:8080`, because the
  browser is not attached to the Compose network.

Next therefore renders no data server-side and holds no secret. The trade-off accepted is
that the access token lives in browser memory and CORS becomes load-bearing; the
trade-off refused is a second server to keep in step with the first.

### 3.5 Database layer

| Migration | Lines | Contents |
| --- | --- | --- |
| `V1__init.sql` | 457 | Every table, constraint and index: `users`, `refresh_tokens`, `students`, `rooms`, `applications`, `allocations`, `attendance`, `absence_alerts`, `complaints`, `hostel_fees`, `fee_payments`, `fee_reminders`, `notices`, `audit_events` |
| `V2__room_reference_data.sql` | 36 | The 120 rooms - reference data, not test fixtures |

Hibernate runs with `ddl-auto: none` and never infers or alters anything. Drift between
the Java entities and the SQL is caught by a dedicated integration test,
`SchemaAgreementIT`, which boots the application with `validate` purely to assert that
the two still agree - so a mismatch fails the build instead of a production start.

The invariants live in the schema, not in Java:

```sql
-- one active allocation per student, enforced as a partial unique index
CREATE UNIQUE INDEX uq_allocations_active_student ON allocations (student_id) WHERE active;

-- an attempt may be replayed by key, but can only ever be inserted once
CONSTRAINT uq_fee_payments_idempotency UNIQUE (idempotency_key)

-- at most one reminder per invoice per calendar day
CONSTRAINT uq_fee_reminders_per_day UNIQUE (fee_id, reminder_date)

-- one alert per student per absence streak, so two concurrent scans cannot both raise it
CONSTRAINT uq_absence_alert_streak UNIQUE (student_id, streak_start_date)

-- money is positive, and statuses are a closed set
CONSTRAINT ck_fee_payments_amount CHECK (amount_paise > 0)
CONSTRAINT ck_fee_payments_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED'))
```

### 3.6 External APIs

One: a payment gateway, reached through the `PaymentGateway` interface, whose methods are
`name`, `publicKey`, `createOrder`, `verify` and `assertConfigured`.

- **`mock`** - the default. It generates `mock_order_<16 hex>` order identifiers and signs
  with a development secret, so the whole payment path, signature verification included,
  is exercisable offline.
- **`razorpay`** - talks to `https://api.razorpay.com/v1` using the same scheme:
  HMAC-SHA256 over the string `orderId|paymentId`, hex-encoded, compared in constant time
  so a wrong signature cannot be narrowed down by timing.

`PaymentGatewayRegistry` resolves the configured provider **at startup**, so a typo in
`PAYMENT_PROVIDER` fails the boot rather than the first payment. Callbacks are dispatched
by the provider name **stored on the attempt row**, not by current configuration - an
invoice paid through one provider still settles correctly after the default is switched.

> **To be confirmed by author:** the Razorpay adapter implements the documented scheme
> but, per the repository's own README, has never been pointed at the real provider.

### 3.7 Background jobs

| Job | Cron (UTC) | What it runs | Where idempotence comes from |
| --- | --- | --- | --- |
| `FeeReminderJob` | `0 17 7 * * *` (07:17 daily) | `FeeReminderService.run(date)` - reminders for invoices due within 7 days | `uq_fee_reminders_per_day`: the row is written *before* the notification is dispatched |
| `AbsenceScanJob` | `0 23 6 * * *` (06:23 daily) | `AbsenceAlertService.scan(date)` - raise, extend or close absence alerts | The scan is a pure function of the attendance register, never of its own previous output |

Both jobs are triggers and nothing more: they resolve the business date in UTC, call one
service method, and catch `RuntimeException` themselves so that a failure is logged with
the job name and the date instead of disappearing into the scheduler. Both are safe to
fire on every instance of a multi-instance deployment, because the constraint - not the
scheduler - is what bounds the effect. Both can be re-run on demand for any date from the
admin console.

Scheduling can be switched off entirely with `SCHEDULING_ENABLED=false`, in which case
the timer threads are never created at all, rather than created and skipped.

---

## 4. Technology Stack

### 4.1 Backend

| Choice | Version | Why this one |
| --- | --- | --- |
| Java | 17 | The long-term-support release these libraries all target. Records give free immutable DTOs, and sealed/pattern features are not needed, so nothing is lost by not being on 21. |
| Spring Boot | 3.5.16 | Declarative transactions and AOP are exactly the two mechanisms this design leans on: `@Transactional` for the lock scope and an aspect for the audit trail. Doing either by hand would mean writing the part most likely to be wrong. |
| Spring Data JPA / Hibernate | Boot-managed | Repository interfaces remove the boilerplate, while `@Lock(PESSIMISTIC_WRITE)` still gives direct access to `SELECT ... FOR UPDATE` when a rule needs it. Entities are mapped by hand to migration-owned tables, so the ORM is a convenience, not the schema authority. |
| PostgreSQL | 17 | Chosen for what it can prove: `FOR UPDATE` blocking under READ COMMITTED, partial unique indexes (`WHERE active`), `JSONB` for audit payloads. Every guarantee this project claims is one of those three. |
| Flyway | Boot-managed | Versioned, ordered, checksum-validated SQL. The schema is a reviewable artefact in the repository rather than a side effect of whatever the entities happened to look like at deploy time. |
| Spring Security | Boot-managed | Used only for the filter chain and URL-prefix authorisation. Row-level narrowing is done explicitly in queries instead, because a database row is the thing that actually needs guarding. |
| JJWT | 0.12.7 | A small, focused JWT library. HS256 with a rejected-if-too-short key, no algorithm negotiation, no JWKS machinery this project has no use for. |
| Bucket4j | 8.19.0 | Token-bucket rate limiting with a clean in-memory implementation and a documented path to a shared Redis backend when one instance is no longer enough. |
| springdoc-openapi | 2.8.17 | Generates OpenAPI and Swagger UI from the controllers that already exist, so the docs cannot drift from the endpoints. |
| Lombok | Boot-managed | Reduces entity boilerplate only. DTOs are records and use none of it. |
| Testcontainers | Boot-managed | Integration tests run against a real `postgres:16.4-alpine`. An in-memory database in "Postgres mode" would run faster and prove less: it does not block on `FOR UPDATE` the way Postgres does, which is the behaviour under test. |
| JaCoCo | 0.8.13 | Coverage report on every green CI run. |

### 4.2 Frontend

| Choice | Version | Why this one |
| --- | --- | --- |
| Next.js | ^16.3.3 | App Router file-based routing across three console trees, with `output: 'standalone'` producing a runtime image that contains no npm, no lockfile and no source. |
| React | ^19.2.0 | The component model the router assumes. |
| TypeScript | ^5.9 (strict) | The console is a typed client of a typed API. `types.ts` mirrors the backend DTOs by hand, and `tsc --noEmit` plus `next build` turn contract drift into a failed build. A generated client was rejected deliberately - see `frontend/README.md`. |
| No UI framework | - | Styling is plain CSS. A component library would have added a dependency surface larger than the application for a console with this few widgets. |
| No test runner | - | There are no frontend tests; the typecheck is the only automated gate. Recorded as a known limitation in section 14. |

### 4.3 Infrastructure

| Choice | Why this one |
| --- | --- |
| Docker + Compose | One command brings up Postgres, Spring and Next together with the health-check ordering they need. It is also the only way to run the frontend against the backend without configuring two shells. |
| Multi-stage images | The build tooling never reaches the runtime image: the backend ships a JRE and a jar, the frontend ships the standalone server. Both run as an unprivileged user. |
| GitHub Actions | Three parallel jobs (backend `verify`, frontend typecheck and build, image build) gate every pull request. |

---

## 5. Project Structure

```text
hostel-ops-platform/
├── README.md                     The short argument: what this is and why it looks like this
├── docker-compose.yml            Postgres + Spring + Next, with health-check ordering
├── .env.example                  Every environment variable, placeholders only
├── .github/workflows/ci.yml      Three parallel required jobs
├── docs/
│   ├── concurrency.md            The design argument, five sites, two rejected designs
│   └── DOCUMENTATION.md          This document
├── backend/                      Spring Boot service (189 files under src/main)
│   ├── pom.xml                   Dependencies, the surefire/failsafe split, JaCoCo
│   ├── mvnw, mvnw.cmd, .mvn/     Maven wrapper: no local Maven install needed
│   ├── Dockerfile                Maven build stage -> JRE runtime stage
│   └── src/
│       ├── main/java/com/hostelops/
│       │   ├── audit/            3   @Audited, the aspect, the AuditAction enum
│       │   ├── config/           11  Security, transactions, scheduling, OpenAPI, dev seed
│       │   ├── controller/       24  72 endpoints, grouped by console and resource
│       │   ├── domain/           28  JPA entities and the 13 enums
│       │   ├── dto/              42  Request and response records, plus mappers
│       │   ├── exception/        4   ErrorCode, ApiError, ApiException, the handler
│       │   ├── job/              2   The two cron triggers
│       │   ├── notify/           4   Notifier interface + LoggingNotifier
│       │   ├── payment/          7   Gateway interface, mock + razorpay, registry, HMAC
│       │   ├── repository/       22  Spring Data interfaces, including the locking queries
│       │   ├── security/         12  JWT, refresh rotation, login throttle, current user
│       │   └── service/          15  <-- the business rules live here
│       ├── main/resources/
│       │   ├── application.yml   All configuration, every value overridable by env var
│       │   └── db/migration/     V1__init.sql, V2__room_reference_data.sql
│       └── test/java/com/hostelops/
│           ├── ...Test.java      15 unit classes (surefire)
│           ├── ...IT.java        8 integration classes (failsafe, needs Docker)
│           └── support/          AbstractPostgresIT and ~30 seeding helpers
└── frontend/                     Next.js console (46 source files, 28 routes)
    ├── package.json              4 scripts: dev, build, start, typecheck
    ├── next.config.mjs           output: 'standalone', and why there is no proxy route
    ├── Dockerfile                deps -> build -> runtime, NEXT_PUBLIC_* as build args
    ├── README.md                 Frontend-specific notes and its own limitations list
    ├── .env.example              Frontend variables, all public by construction
    └── src/
        ├── app/                  App Router: login/, student/, warden/, admin/
        ├── components/           4 files: app-shell, dialog, status-badges, ui
        └── lib/                  10 files: the client contract and session machinery
```

### Where to look for what

| Looking for | Go to |
| --- | --- |
| **The main business logic** | `backend/src/main/java/com/hostelops/service/` - 15 classes. `AllocationService` and `PaymentService` are the two that carry the concurrency argument. |
| **The design argument** | [`docs/concurrency.md`](concurrency.md). Read this before changing a lock. |
| **All configuration** | `backend/src/main/resources/application.yml`, mirrored as environment variables in `.env.example`. |
| **The schema** | `backend/src/main/resources/db/migration/`. Nothing else may alter it. |
| **The API contract, backend side** | `backend/src/main/java/com/hostelops/controller/` for routes, `dto/` for bodies, `exception/ErrorCode.java` for the error codes. |
| **The API contract, frontend side** | `frontend/src/lib/endpoints.ts` - the only file in which a URL string appears - and `frontend/src/lib/types.ts`, the hand-written mirror of the DTOs. |
| **Tests** | `backend/src/test/java/com/hostelops/`. `*Test` is a unit test, `*IT` is an integration test. |
| **Scripts** | There are none, deliberately. `./mvnw`, `npm run` and `docker compose` are the entry points; no `Makefile` or shell wrapper is needed. |
| **CI** | `.github/workflows/ci.yml`. |

---

## 6. Setup and Installation

### 6.1 Prerequisites

| Path | You need | Notes |
| --- | --- | --- |
| **Docker (recommended)** | Docker Engine 24+ with Compose v2 | Nothing else. No JDK, no Node, no Postgres on the host. Verified here with Docker 29.7.2. |
| **Running the halves directly** | JDK 17 or newer, Node.js 20+ (22 verified), PostgreSQL 17 | Maven is not required - the repository ships the Maven wrapper (`./mvnw`). |
| **Running the integration tests** | A running Docker daemon | Testcontainers starts `postgres:16.4-alpine` itself; you do not create a database. |

> The build targets Java 17. A newer JDK compiles it happily - the machine used to verify
> this document had JDK 21 installed and both the wrapper build and the Docker build
> worked - but 17 is what CI uses and what the Docker build stage pins.

### 6.2 Clone

```bash
git clone https://github.com/Bipin583/hms.git hostel-ops-platform
cd hostel-ops-platform
```

That URL is the `origin` remote configured in the working copy this document was written
from. The directory name is yours to choose; the commands below assume you are inside it.

### 6.3 Configure

Copy the template and fill in two values:

```bash
cp .env.example .env
openssl rand -base64 48        # paste the output as JWT_SECRET
```

A complete `.env` for local development. Every value here is a placeholder - **do not use
these in anything reachable from a network:**

```dotenv
# --- required -------------------------------------------------------------
# No default exists for this. An unset JWT_SECRET stops the application rather
# than falling back to a value anyone could mint an admin token with.
JWT_SECRET=REPLACE_ME_WITH_OPENSSL_RAND_BASE64_48

# --- demo accounts --------------------------------------------------------
# Seeds admin, lh_warden, mh_warden, 24 students and dashboard-ready demo
# records, all protected by this password.
# Leave it EMPTY and no accounts are created at all: there is no hardcoded
# fallback password anywhere in the codebase.
DEV_SEED_PASSWORD=change-me-locally

# --- addresses (these two are a pair; changing one alone breaks sign-in) ---
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
CORS_ALLOWED_ORIGINS=http://localhost:3000

# --- ports ----------------------------------------------------------------
FRONTEND_PORT=3000
BACKEND_PORT=8080
DATABASE_PORT=5432

# --- database (Compose creates this database with these credentials) ------
DATABASE_NAME=hostelops
DATABASE_USERNAME=hostelops
DATABASE_PASSWORD=hostelops

# --- behaviour ------------------------------------------------------------
PAYMENT_PROVIDER=mock
SCHEDULING_ENABLED=true
APP_LOG_LEVEL=INFO
```

### 6.4 Run everything with Docker

```bash
docker compose up --build
```

That starts three containers in dependency order. Postgres comes up first and Compose
waits for `pg_isready` to succeed before starting Spring, because Flyway runs during
startup and a container that is merely "started" is not yet accepting connections.

| Service | URL | Ready when |
| --- | --- | --- |
| Console | <http://localhost:3000> | `/login` renders |
| API | <http://localhost:8080> | `/actuator/health` returns `{"status":"UP"}` |
| API docs | <http://localhost:8080/swagger-ui.html> | - |
| Postgres | `localhost:5432` | `pg_isready` |

Check the stack from the shell:

```bash
docker compose ps                                  # all three should be (healthy)
curl -s http://localhost:8080/actuator/health      # {"status":"UP"}
docker compose logs backend | grep -i flyway       # "Successfully applied 2 migrations"
```

Sign in at <http://localhost:3000> as `admin`, `lh_warden`, `mh_warden`, or a student such
as `asha.rao`, using whatever you set as `DEV_SEED_PASSWORD`.

Shut down, keeping the database:

```bash
docker compose down
```

Shut down and delete the database volume, which is how you get back to a clean seed:

```bash
docker compose down -v
```

### 6.5 Database migration

There is no migration command to run. Flyway executes `V1__init.sql` and
`V2__room_reference_data.sql` during backend startup, in order, with checksum validation
(`validate-on-migrate: true`) so an edited migration that has already been applied fails
the boot rather than silently diverging. Hibernate is configured with `ddl-auto: none` and
alters nothing.

The second migration creates the room inventory - 120 rooms, 360 beds - because rooms are
reference data rather than test fixtures: a fresh clone must have something to allocate
against.

```text
LH block A + B    2 blocks x 4 floors x 6 rooms x 3 beds = 144 beds  (female)
BH block A + B    2 blocks x 4 floors x 6 rooms x 3 beds = 144 beds  (male)
MH block A        1 block  x 4 floors x 6 rooms x 3 beds =  72 beds  (male)
                                                          --------
                                                           360 beds
```

Room names encode the block, floor and number (`A101`, `B406`), and **floor N houses
year-N students** - the eligibility rule the allocation matcher applies.

Demo *users*, by contrast, are deliberately **not** in a migration: migrations run in
every environment including production, and seeded logins in `V*.sql` would be exactly
the default-credential problem this rebuild set out to remove. They are created by
`DevDataSeeder`, which runs only under the `dev` Spring profile and only when
`DEV_SEED_PASSWORD` is set.

### 6.6 Running the halves directly

Useful when you want a debugger attached or a hot-reloading console.

**Postgres.** Either use the one from Compose (`docker compose up -d db`) or point
`DATABASE_URL` at your own instance.

**Backend:**

```bash
cd backend

export DATABASE_URL=jdbc:postgresql://localhost:5432/hostelops
export DATABASE_USERNAME=hostelops
export DATABASE_PASSWORD=hostelops
export JWT_SECRET="$(openssl rand -base64 48)"
export SPRING_PROFILES_ACTIVE=dev
export DEV_SEED_PASSWORD=change-me-locally
export CORS_ALLOWED_ORIGINS=http://localhost:3000
export REFRESH_COOKIE_SECURE=false      # see the warning below

./mvnw spring-boot:run
```

**Frontend:**

```bash
cd frontend
cp .env.example .env.local     # NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
npm ci
npm run dev                    # http://localhost:3000
```

> **The one setting that trips everybody up.** The refresh-token cookie is `Secure` by
> default, and `http://localhost` is not a secure origin, so the browser silently drops
> the cookie and every session ends at the first token refresh - which looks like a
> random logout, not a configuration error. Set `REFRESH_COOKIE_SECURE=false` for local
> HTTP, and never in a deployment. Docker Compose already sets it for you.

---

## 7. Usage Guide

The console at <http://localhost:3000> is the intended interface, and every workflow below
can be clicked through. The `curl` commands are given because they are unambiguous, and
because they are how the flows in this section were verified on 2026-09-03.

Two conventions apply to every request:

- **Authentication** is `Authorization: Bearer <accessToken>` on everything except
  `/api/v1/auth/login` and `/api/v1/auth/refresh`.
- **Money** is always an integer count of paise. `45000` means 450.00 rupees.

### 7.1 Sign in

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"asha.rao","password":"<DEV_SEED_PASSWORD>"}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "user": {
    "id": 4, "username": "asha.rao", "fullName": "Asha Rao",
    "email": "asha.rao@example.edu", "role": "STUDENT",
    "hostelScope": null, "studentId": 1
  }
}
```

The response also carries `Set-Cookie: hostelops_refresh=...; HttpOnly; SameSite=Strict;
Path=/api/v1/auth`. The access token is valid for 15 minutes; the console renews it
silently 60 seconds before expiry, and again reactively if a request comes back 401.

Save the token for the commands that follow:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"asha.rao","password":"<DEV_SEED_PASSWORD>"}' \
  | python -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
```

Calling a protected endpoint without a token returns a 401 with the standard error body
(this is the actual response, verified at runtime):

```json
{"error":{"code":"UNAUTHENTICATED","message":"Authentication is required for this endpoint",
"details":null,"traceId":null,"timestamp":"2026-09-03T13:24:34.736304314Z"}}
```

An unknown path under `/api/v1/**` returns the same 401 rather than a 404, because the
filter chain denies by default before routing happens - so an unauthenticated caller
cannot enumerate which endpoints exist.

### 7.2 Workflow: a student gets a bed

This is the flow the whole system is arranged around. In the console: the student opens
**Student -> Application** and clicks *Apply*; the warden opens **Warden -> Applications**
and clicks *Approve*.

```bash
# 1. the student applies (no body: the server knows who is asking)
curl -s -X POST http://localhost:8080/api/v1/student/applications \
  -H "Authorization: Bearer $STUDENT_TOKEN"
# -> {"id":1,"studentId":1,"status":"PENDING","appliedAt":"2026-09-03T13:31:12.4Z", ...}

# 2. the warden sees it in their queue - and only for their own hostel
curl -s "http://localhost:8080/api/v1/warden/applications/pending" \
  -H "Authorization: Bearer $WARDEN_TOKEN"

# 3. the warden approves: status change + room match + bed reservation, one transaction
curl -s -X POST http://localhost:8080/api/v1/warden/applications/1/approve \
  -H "Authorization: Bearer $WARDEN_TOKEN" -H 'Content-Type: application/json' -d '{}'
# -> {"id":1,"status":"APPROVED","decidedAt":"...","allocation":{"roomName":"A101", ...}}

# 4. the student now has a room
curl -s http://localhost:8080/api/v1/student/me -H "Authorization: Bearer $STUDENT_TOKEN"
# -> {..., "allocationStatus":"ALLOCATED", "currentRoom":{"roomName":"A101","hostelType":"LH", ...}}

# 5. occupancy moved - counted from allocation rows, never from a stored counter
curl -s http://localhost:8080/api/v1/warden/rooms/occupancy \
  -H "Authorization: Bearer $WARDEN_TOKEN"
# -> [{"hostelType":"LH","block":"A","roomCount":24,"totalBeds":72,
#      "occupiedBeds":1,"freeBeds":71,"occupancyPercent":1.4}, ...]
```

Step 5 is the point of the rebuild. `occupiedBeds` is a `COUNT` over active allocations
evaluated on every read, so it cannot drift from the allocations the way the predecessor's
cached `occupied_beds` column did.

Refusals you should expect, and what they mean:

| Situation | Response |
| --- | --- |
| The student already has a pending application | `409 DUPLICATE_APPLICATION` |
| Every eligible room is full | `409 NO_ROOM_AVAILABLE` |
| Approving an application that is already decided | `409 ILLEGAL_STATE_TRANSITION` |
| Allocating a female student to a men's block | `409 ROOM_NOT_ELIGIBLE` |
| A warden touching another hostel's student | `403 OUT_OF_SCOPE` |

Vacating a bed is `DELETE /api/v1/warden/allocations/student/{studentId}`, which is also how
you undo the demo state above.

### 7.3 Workflow: attendance and an absence alert

In the console: **Warden -> Attendance** shows the register for a date with a
present/absent toggle per student and one *Save* button.

```bash
# mark a whole hostel in one request (up to 1000 rows)
curl -s -X POST http://localhost:8080/api/v1/warden/attendance/register \
  -H "Authorization: Bearer $WARDEN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"attendanceDate":"2026-09-03","marks":[{"studentId":1,"status":"ABSENT"},
                                              {"studentId":2,"status":"PRESENT"}]}'
# -> {"created":2,"updated":0,"skippedStudentIds":[]}

# re-sending it corrects rather than duplicates: {"created":0,"updated":2, ...}

# after enough consecutive absent working days, the nightly scan raises an alert.
# force a run for a specific date from the admin console:
curl -s -X POST "http://localhost:8080/api/v1/admin/jobs/absence-scans?date=2026-09-03" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# -> {"scanDate":"2026-09-03","studentsExamined":6,"raised":1,"extended":0,"closed":0}

# run it again for the same date - the counters change, the data does not
# -> {"scanDate":"2026-09-03","studentsExamined":6,"raised":0,"extended":0,"closed":0}
```

The threshold is ten consecutive absent **working** days by default
(`app.attendance.absence-alert-threshold`): Saturdays, Sundays and any configured holidays
are skipped rather than counted, and one scan reads back 90 calendar days of register.
Marking a future date is refused with `400 ATTENDANCE_DATE_INVALID`.

### 7.4 Workflow: raising and paying a fee

In the console: **Warden -> Fees** raises the invoice; the student sees it under
**Student -> Fees** and opens the pay panel.

```bash
# 1. the warden raises an invoice: 45000 paise = Rs. 450.00
curl -s -X POST http://localhost:8080/api/v1/warden/fees \
  -H "Authorization: Bearer $WARDEN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"studentId":1,"academicTerm":"2026-ODD","amountPaise":45000,
       "dueDate":"2026-09-30","description":"Hostel fee, odd semester"}'
# -> {"id":1,"amountPaise":45000,"amountPaidPaise":0,"outstandingPaise":45000,
#     "status":"UNPAID","overdue":false, ...}

# 2. the student initiates a payment. The Idempotency-Key header is REQUIRED.
curl -s -X POST http://localhost:8080/api/v1/student/payments \
  -H "Authorization: Bearer $STUDENT_TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 9f1c2b7e-3d40-4a11-9c6e-0d5b7a2f88c1' \
  -d '{"feeId":1,"amountPaise":22500}'
# -> {"paymentId":1,"feeId":1,"amountPaise":22500,"currency":"INR","provider":"mock",
#     "providerOrderId":"mock_order_3f8a1c2d4e5b6a70","publicKey":"mock_public_key",
#     "alreadyInitiated":false}
```

Re-sending step 2 with **the same** `Idempotency-Key` returns the same `paymentId` and the
same `providerOrderId` with `"alreadyInitiated":true`. It does not open a second order, and
that is guaranteed by the unique constraint `uq_fee_payments_idempotency`, not by the
lookup that precedes the insert - two simultaneous requests with one key cannot both win.

```bash
# 3. the gateway calls back. For the mock gateway you can sign it yourself:
#    HMAC-SHA256 over "<orderId>|<paymentId>", hex, with the mock development secret.
ORDER=mock_order_3f8a1c2d4e5b6a70
PAYMENT=mock_pay_0001
SIG=$(printf '%s|%s' "$ORDER" "$PAYMENT" \
      | openssl dgst -sha256 -hmac 'mock-gateway-development-secret' -hex \
      | sed 's/^.*= //')

curl -s -X POST http://localhost:8080/api/v1/student/payments/callback \
  -H "Authorization: Bearer $STUDENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"providerOrderId\":\"$ORDER\",\"providerPaymentId\":\"$PAYMENT\",\"signature\":\"$SIG\"}"
# -> {"id":1,"status":"SUCCEEDED","amountPaise":22500, ...}

# 4. the invoice is now half paid
curl -s http://localhost:8080/api/v1/student/fees/1 -H "Authorization: Bearer $STUDENT_TOKEN"
# -> {"amountPaise":45000,"amountPaidPaise":22500,"outstandingPaise":22500,
#     "status":"PARTIALLY_PAID", ...}
```

`mock-gateway-development-secret` is a development constant that ships in the repository so
the signature path can be exercised offline; it is not a credential for anything. The
`razorpay` adapter takes its key and secret from the environment instead.

**What makes step 3 safe.** The callback verifies the signature *before* taking any lock,
then locks the attempt row, then locks the invoice row - always in that order. The status
check that decides whether to credit the invoice happens on the *locked* read of the
attempt, which is the subtle part: a check made before the lock would be evaluated against
a stale snapshot, and two redeliveries of the same part payment would each pass it and
credit 22,500 paise twice against a 45,000-paise invoice. There is an integration test that
does exactly that with the lock removed, and it fails.

Refusals to expect:

| Situation | Response |
| --- | --- |
| Amount is zero, negative, or larger than the outstanding balance | `400 PAYMENT_AMOUNT_INVALID` |
| Signature does not match | `400 PAYMENT_VERIFICATION_FAILED` |
| The invoice is already fully paid or cancelled | `409 FEE_ALREADY_SETTLED` |
| This attempt has already been settled | `409 PAYMENT_ALREADY_SETTLED` |
| The gateway itself failed | `502 PAYMENT_GATEWAY_ERROR` - retry the identical request with the same `Idempotency-Key` |

### 7.5 Workflow: a complaint

```bash
# the student files it
curl -s -X POST http://localhost:8080/api/v1/student/complaints \
  -H "Authorization: Bearer $STUDENT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"category":"PLUMBING","urgency":"HIGH","title":"Tap leaking in A101",
       "description":"Constant drip since yesterday evening."}'
# -> {"id":1,"status":"OPEN","category":"PLUMBING","urgency":"HIGH","raisedAt":"...", ...}

# the warden picks it up, then resolves it with a note
curl -s -X POST http://localhost:8080/api/v1/warden/complaints/1/status \
  -H "Authorization: Bearer $WARDEN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"IN_PROGRESS"}'

curl -s -X POST http://localhost:8080/api/v1/warden/complaints/1/status \
  -H "Authorization: Bearer $WARDEN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"RESOLVED","resolutionNote":"Washer replaced by the plumber."}'
```

Resolving without a note is refused with `400 RESOLUTION_NOTE_REQUIRED` - the workflow
insists that closing a complaint says what was done. Illegal jumps (`RESOLVED` back to
`OPEN`) are `409 ILLEGAL_STATE_TRANSITION`.

`GET /api/v1/warden/complaints/analytics?windowDays=30` returns average and 90th-percentile
resolution times, the split between queue time and work time, backlog age, and a
per-category breakdown.

### 7.6 Workflow: admin oversight

```bash
# who changed what: by entity, by actor, or the whole trail
curl -s http://localhost:8080/api/v1/admin/audit/entity/ALLOCATION/1 \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# -> a page of {"id":..,"actorUsername":"lh_warden","entityType":"ALLOCATION",
#               "entityId":1,"action":"CREATE","payloadDiff":{...},"occurredAt":"..."}

# how busy has the system been since a moment
curl -s "http://localhost:8080/api/v1/admin/audit/activity?since=2026-09-01T00:00:00Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# re-run either nightly job for a specific business date
curl -s -X POST "http://localhost:8080/api/v1/admin/jobs/fee-reminders?date=2026-09-03" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# -> {"reminderDate":"2026-09-03","invoicesExamined":12,"sent":3,"skipped":9,"failed":0}

# run it again the same day: everything is skipped, nothing is sent twice
# -> {"reminderDate":"2026-09-03","invoicesExamined":12,"sent":0,"skipped":12,"failed":0}
```

Reminders are "sent" to the log, not to a mail server - see section 14. The guarantee the
system makes, and tests, is about the *decision* to send: once per invoice per day, provably.

### 7.7 The console, route by route

| Route | Who | What it does |
| --- | --- | --- |
| `/` | anyone | Not a page: it waits for the session to resolve and forwards to the right console, or to `/login` |
| `/login` | anyone | Sign in |
| `/student` | student | Dashboard: outstanding fees, open complaints, alerts, notices |
| `/student/application` | student | Apply for a place, and see the decision |
| `/student/profile` | student | Own details, current room and roommates; edit contact numbers |
| `/student/attendance` | student | Own register and attendance percentage |
| `/student/alerts` | student | Absence alerts raised against them |
| `/student/fees` | student | Invoices, with status and outstanding balance |
| `/student/fees/[id]` | student | One invoice, its payment history, and the pay panel |
| `/student/complaints` | student | File and track complaints |
| `/student/notices` | student | Notices addressed to them |
| `/warden` | warden | Dashboard for one hostel |
| `/warden/students` | warden | Roster, with year / allocation-status filters and a search box |
| `/warden/students/[id]` | warden | One student: details, room, attendance, fees |
| `/warden/applications` | warden | Approve, or reject with a reason |
| `/warden/allocations` | warden | Allocate manually or automatically, vacate, history |
| `/warden/rooms` | warden | Room list by block, with occupants |
| `/warden/occupancy` | warden | Beds occupied and free, per block |
| `/warden/attendance` | warden | The daily register, and trends over a range |
| `/warden/absence-alerts` | warden | Open alerts, and acknowledge |
| `/warden/fees` | warden | Raise invoices; collections summary |
| `/warden/fees/[id]` | warden | One invoice and its payments; cancel |
| `/warden/payments` | warden | The payment ledger |
| `/warden/complaints` | warden | Queue, filters, analytics |
| `/warden/complaints/[id]` | warden | One complaint; move its status |
| `/warden/notices` | warden | Post and withdraw notices |
| `/admin` | admin | Operations overview: 24h telemetry, job status, live audit feed |
| `/admin/audit` | admin | The audit trail: by entity, by actor, or whole |
| `/admin/jobs` | admin | Re-run either nightly job for a chosen date |

29 route files in total. A signed-in user who types another console's URL is redirected by
the client *and* refused by the server - the client redirect is a convenience, the 403 is
the actual boundary.

---

## 8. API / Interface Documentation

**24 controllers, 72 endpoints, all under `/api/v1`.** Interactive documentation is
generated from these same controllers and served at
<http://localhost:8080/swagger-ui.html>, with the raw OpenAPI description at
`/v3/api-docs`. Both can be disabled with `SPRINGDOC_ENABLED=false`.

### 8.1 Conventions

| Aspect | Rule |
| --- | --- |
| **Base path** | `/api/v1`, then one of `auth`, `warden`, `student`, `admin` |
| **Authentication** | `Authorization: Bearer <accessToken>` on everything except `POST /auth/login` and `POST /auth/refresh` |
| **Authorisation** | The URL prefix is the role gate: `/admin/**` needs `ADMIN`; `/warden/**` needs `ADMIN` or `WARDEN`; `/student/**` needs `ADMIN` or `STUDENT`. Anything not matched is `authenticated()` - default deny. Row visibility is narrowed on top of that by hostel scope. |
| **Content type** | `application/json` in and out; request bodies are validated with Jakarta Bean Validation before the controller body runs |
| **Money** | Integer paise. `45000` is 450.00 rupees. There is no decimal anywhere in the wire format. |
| **Dates** | A calendar day is a string, `"2026-09-03"`. A moment is an ISO-8601 instant in UTC, `"2026-09-03T13:24:34.736304314Z"`. The two are never interchanged. |
| **Pagination** | Any endpoint returning a page accepts `?page=0&size=20&sort=field,asc`. Default size is 20; rooms default to sorting by `roomName` and students by `rollNumber`. |
| **Idempotency** | `POST /student/payments` requires an `Idempotency-Key` header. Re-sending the same key returns the original attempt. |
| **Errors** | Always the same envelope, never a stack trace: `server.error.include-message`, `include-stacktrace`, `include-binding-errors` and `include-exception` are all off. |

Every error looks like this:

```json
{
  "error": {
    "code": "ROOM_FULL",
    "message": "Room A101 has no free bed",
    "details": { "roomId": 1, "capacity": 3 },
    "traceId": null,
    "timestamp": "2026-09-03T13:24:34.736304314Z"
  }
}
```

`code` is the contract. It is a closed enumeration, and the HTTP status is a property of
the code rather than of the throw site - so a given code always arrives with the same
status, and the typed frontend switches on `code` instead of parsing prose.

### 8.2 Error codes

| Status | Codes |
| --- | --- |
| **400** | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `BAD_REQUEST`, `ATTENDANCE_DATE_INVALID`, `RESOLUTION_NOTE_REQUIRED`, `PAYMENT_AMOUNT_INVALID`, `PAYMENT_VERIFICATION_FAILED` |
| **401** | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `TOKEN_INVALID`, `REFRESH_TOKEN_INVALID` |
| **403** | `FORBIDDEN`, `OUT_OF_SCOPE` |
| **404** | `NOT_FOUND` |
| **405** | `METHOD_NOT_ALLOWED` |
| **409** | `CONFLICT`, `ROOM_FULL`, `STUDENT_ALREADY_ALLOCATED`, `STUDENT_NOT_ALLOCATED`, `ROOM_NOT_ELIGIBLE`, `DUPLICATE_APPLICATION`, `NO_ROOM_AVAILABLE`, `ILLEGAL_STATE_TRANSITION`, `DUPLICATE_RESOURCE`, `FEE_ALREADY_SETTLED`, `PAYMENT_ALREADY_SETTLED`, `ALERT_ALREADY_ACKNOWLEDGED` |
| **429** | `RATE_LIMITED`, with a `Retry-After` header |
| **500** | `INTERNAL` |
| **502** | `PAYMENT_GATEWAY_ERROR` |

`PAYMENT_GATEWAY_ERROR` is deliberately a 502 rather than a 500: it tells the caller the
failure was downstream, and that retrying the identical request with the same
`Idempotency-Key` is the correct response.

### 8.3 Authentication - `/api/v1/auth`

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `POST` | `/login` | Exchange credentials for tokens | `{username, password}` | `200` `AuthResponse` + `Set-Cookie: hostelops_refresh` |
| `POST` | `/refresh` | Rotate the refresh cookie, get a new access token | The cookie; no body | `200` `AuthResponse` + a new cookie |
| `POST` | `/logout` | Revoke the refresh token server-side and clear the cookie | The cookie | `204 No Content` |
| `GET` | `/me` | Who am I, according to the token | Bearer token | `200` `UserSummaryResponse` |

```text
AuthResponse        { accessToken, tokenType: "Bearer", expiresInSeconds: 900, user }
UserSummaryResponse { id, username, fullName, email, role, hostelScope, studentId }
```

`role` is `ADMIN | WARDEN | STUDENT`. `hostelScope` is `LH | MH` for a warden and `null`
otherwise; `studentId` is populated only for a student. Failures: `401 INVALID_CREDENTIALS`,
`401 REFRESH_TOKEN_INVALID`, `429 RATE_LIMITED` after five failed attempts from the same
(username, client IP) inside fifteen minutes.

### 8.4 Warden console - `/api/v1/warden`

**Dashboard**

| Method | Path | Purpose | Output |
| --- | --- | --- | --- |
| `GET` | `/dashboard` | Counters for one hostel | `{unpaidFees, openComplaints, openAbsenceAlerts, noticesPosted}` |

**Students**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/students` | Roster, filtered and searched | `?yearOfStudy=&allocationStatus=&query=` + paging | `Page<StudentSummaryResponse>` |
| `GET` | `/students/{studentId}` | One student in full | path id | `StudentDetailResponse` |

```text
StudentSummaryResponse { id, rollNumber, fullName, gender, yearOfStudy, branch,
                         allocationStatus, roomName }
StudentDetailResponse  { ...summary, email, mobileNo, parentMobileNo,
                         currentRoom: RoomSummaryResponse | null }
```

A student outside the warden's hostel is `403 OUT_OF_SCOPE`, not `404` - the row exists,
the caller simply may not see it.

**Applications**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/applications` | All applications, optionally by status | `?status=PENDING\|APPROVED\|REJECTED` | `Page<ApplicationResponse>` |
| `GET` | `/applications/pending` | The decision queue | paging | `Page<ApplicationResponse>` |
| `GET` | `/applications/{id}` | One application | path id | `ApplicationResponse` |
| `POST` | `/applications/{id}/approve` | Approve **and** allocate a bed, in one transaction | `{note?}` | `ApplicationResponse` |
| `POST` | `/applications/{id}/reject` | Reject with a reason | `{reason}` | `ApplicationResponse` |

Failures: `409 ILLEGAL_STATE_TRANSITION` if it is already decided, `409 NO_ROOM_AVAILABLE`
if nothing eligible is free.

**Allocations**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/allocations` | Active allocations | paging | `Page<AllocationResponse>` |
| `POST` | `/allocations` | Allocate a named student to a named room | `{studentId, roomId}` | `AllocationResponse` |
| `POST` | `/allocations/auto` | Let the matcher choose the room | `{studentId}` | `AllocationResponse` |
| `DELETE` | `/allocations/student/{studentId}` | Vacate the bed | path id | `200`, empty body |
| `GET` | `/allocations/student/{studentId}` | Current allocation | path id | `AllocationResponse` |
| `GET` | `/allocations/student/{studentId}/history` | Every allocation ever | path id | `List<AllocationResponse>` |

```text
AllocationResponse { id, studentId, rollNumber, studentName, roomId, roomName,
                     hostelType, block, floor, active, allocatedAt, vacatedAt }
```

Failures: `409 ROOM_FULL`, `409 ROOM_NOT_ELIGIBLE` (wrong gender or year for that room),
`409 STUDENT_ALREADY_ALLOCATED`, `409 STUDENT_NOT_ALLOCATED` on vacating.

**Rooms**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/rooms` | Rooms in scope, one block at a time | `?block=A` + paging, sorted by `roomName` | `Page<RoomResponse>` |
| `GET` | `/rooms/{roomId}` | One room | path id | `RoomResponse` |
| `GET` | `/rooms/{roomId}/occupants` | Who is in it | path id | `List<StudentSummaryResponse>` |
| `GET` | `/rooms/occupancy` | Beds occupied and free, per block | - | `List<OccupancyResponse>` |

```text
RoomResponse      { id, roomName, hostelType, block, floor, capacity, occupied,
                    free, eligibleYear, eligibleGender }
OccupancyResponse { hostelType, block, roomCount, totalBeds, occupiedBeds,
                    freeBeds, occupancyPercent }
```

`occupied`, `occupiedBeds` and `freeBeds` are counted from allocation rows on every read.
Nothing caches them; that cache is the bug this project was written to eliminate.

**Attendance**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/attendance/register` | The register for one day | `?date=2026-09-03` + paging | `Page<AttendanceResponse>` |
| `POST` | `/attendance/register` | Mark up to 1000 students at once | `{attendanceDate, marks:[{studentId, status}]}` | `{created, updated, skippedStudentIds}` |
| `POST` | `/attendance/mark` | Mark or correct one student | `{studentId, attendanceDate, status}` | `AttendanceResponse` |
| `GET` | `/attendance/trend` | Present/absent totals per day | `?from=&to=` | `AttendanceTrendResponse` |
| `GET` | `/attendance/student/{id}` | One student's marks | `?from=&to=` | `List<AttendanceResponse>` |
| `GET` | `/attendance/student/{id}/summary` | Present/absent counts and a percentage | `?from=&to=` | `StudentAttendanceSummaryResponse` |

`status` is `PRESENT | ABSENT`. Re-posting the same register corrects existing rows rather
than duplicating them - the response distinguishes `created` from `updated`. A future date
is `400 ATTENDANCE_DATE_INVALID`.

**Absence alerts**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/absence-alerts` | Open alerts, oldest first - the queue | paging | `Page<AbsenceAlertResponse>` |
| `GET` | `/absence-alerts/all` | Open and acknowledged | paging | `Page<AbsenceAlertResponse>` |
| `POST` | `/absence-alerts/{alertId}/acknowledge` | Mark it handled | path id | `AbsenceAlertResponse` |

```text
AbsenceAlertResponse { id, studentId, rollNumber, studentName, streakStartDate,
                       lastAbsentDate, absentDays, acknowledged, acknowledgedAt,
                       acknowledgedBy }
```

Acknowledging twice is `409 ALERT_ALREADY_ACKNOWLEDGED`.

**Fees and payments**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/fees` | Invoices in scope | `?status=UNPAID\|PARTIALLY_PAID\|PAID\|CANCELLED` + paging | `Page<FeeResponse>` |
| `GET` | `/fees/collections` | Billed, collected, outstanding, collection rate | - | `FeeCollectionResponse` |
| `GET` | `/fees/{feeId}` | One invoice | path id | `FeeResponse` |
| `POST` | `/fees` | Raise an invoice | `{studentId, academicTerm, amountPaise, dueDate, description?}` | `FeeResponse` |
| `POST` | `/fees/{feeId}/cancel` | Cancel an unpaid invoice | path id | `FeeResponse` |
| `GET` | `/payments` | The payment ledger | paging | `Page<FeePaymentResponse>` |
| `GET` | `/payments/fee/{feeId}` | Attempts against one invoice | path id | `List<FeePaymentResponse>` |

```text
FeeResponse        { id, studentId, rollNumber, studentName, academicTerm, description,
                     amountPaise, amountPaidPaise, outstandingPaise, dueDate, status,
                     overdue, createdAt }
FeePaymentResponse { id, feeId, studentId, amountPaise, status, provider,
                     providerOrderId, providerPaymentId, initiatedAt, completedAt }
```

Cancelling an invoice that has money against it is `409 FEE_ALREADY_SETTLED`.

**Complaints**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/complaints` | Queue, optionally by status | `?status=OPEN\|IN_PROGRESS\|RESOLVED` + paging | `Page<ComplaintResponse>` |
| `GET` | `/complaints/analytics` | Resolution times and backlog | `?windowDays=30` | `ComplaintAnalyticsResponse` |
| `GET` | `/complaints/{complaintId}` | One complaint | path id | `ComplaintResponse` |
| `POST` | `/complaints/{complaintId}/status` | Move it along | `{status, resolutionNote?}` | `ComplaintResponse` |

```text
ComplaintResponse { id, studentId, rollNumber, studentName, roomName, category,
                    urgency, title, description, status, resolutionNote,
                    raisedAt, updatedAt, resolvedAt }
```

`category` is one of `ELECTRICAL, PLUMBING, FURNITURE, CLEANLINESS, INTERNET, FOOD,
SECURITY`; `urgency` is `LOW | MEDIUM | HIGH`. Resolving without a note is
`400 RESOLUTION_NOTE_REQUIRED`; an impossible transition is `409 ILLEGAL_STATE_TRANSITION`.
`ComplaintAnalyticsResponse` carries average and 90th-percentile resolution hours, the
queue-time versus work-time split, backlog age and a per-category breakdown.

**Notices**

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `POST` | `/notices` | Post a notice, optionally targeted | `{title, body, audienceHostel?, audienceGender?, audienceYear?, expiresAt?}` | `NoticeResponse` |
| `GET` | `/notices` | Notices this warden posted | paging | `Page<NoticeResponse>` |
| `DELETE` | `/notices/{noticeId}` | Withdraw it | path id | `200`, empty body |

All three audience fields are optional and independent: leave them null for "everyone in
scope", or set any combination to narrow by hostel, gender and year. `expiresAt` must be
after publication - the database enforces that with a `CHECK` constraint, not the service.

### 8.5 Student console - `/api/v1/student`

Every path here that takes a `{studentId}` still checks that the caller *is* that student;
the id in the URL is a convenience for the client, never the authority on identity.

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/me` | Own profile, room included | - | `StudentDetailResponse` |
| `PUT` | `/me` | Update own contact details | `{mobileNo, parentMobileNo?, branch?}` | `StudentDetailResponse` |
| `GET` | `/me/roommates` | Who else is in the room | - | `List<StudentSummaryResponse>` |
| `GET` | `/dashboard` | Own counters | - | `StudentDashboardResponse` |
| `POST` | `/applications` | Apply for a place | no body | `ApplicationResponse` |
| `GET` | `/applications` | Own applications | - | `List<ApplicationResponse>` |
| `GET` | `/allocations/{studentId}` | Current room | path id | `AllocationResponse` |
| `GET` | `/allocations/{studentId}/history` | Past rooms | path id | `List<AllocationResponse>` |
| `GET` | `/attendance/{studentId}` | Own marks | `?from=&to=` | `List<AttendanceResponse>` |
| `GET` | `/attendance/{studentId}/summary` | Own percentage | `?from=&to=` | `StudentAttendanceSummaryResponse` |
| `GET` | `/absence-alerts/{studentId}` | Alerts about them | path id | `List<AbsenceAlertResponse>` |
| `GET` | `/fees` | Own invoices | - | `List<FeeResponse>` |
| `GET` | `/fees/{feeId}` | One invoice | path id | `FeeResponse` |
| `GET` | `/payments` | Own payment attempts | paging | `Page<FeePaymentResponse>` |
| `POST` | `/payments` | Start a payment | header `Idempotency-Key`, body `{feeId, amountPaise}` | `PaymentInitiationResponse` |
| `POST` | `/payments/callback` | Settle a verified payment | `{providerOrderId, providerPaymentId, signature}` | `FeePaymentResponse` |
| `GET` | `/complaints` | Own complaints | paging | `Page<ComplaintResponse>` |
| `POST` | `/complaints` | File one | `{category, urgency, title, description}` | `ComplaintResponse` |
| `GET` | `/complaints/{complaintId}` | One of their own | path id | `ComplaintResponse` |
| `GET` | `/notices` | Notices addressed to them | paging | `Page<NoticeResponse>` |

```text
PaymentInitiationResponse { paymentId, feeId, amountPaise, currency: "INR", provider,
                            providerOrderId, publicKey, alreadyInitiated }
```

`alreadyInitiated: true` means the `Idempotency-Key` had been seen before and this is the
original attempt, returned again rather than a second order. `publicKey` is the gateway's
*publishable* key, which is what the browser checkout widget needs; the signing secret never
leaves the server.

Applying twice while a decision is pending is `409 DUPLICATE_APPLICATION`.

### 8.6 Admin console - `/api/v1/admin`

| Method | Path | Purpose | Input | Output |
| --- | --- | --- | --- | --- |
| `GET` | `/audit` | The whole trail, newest first | paging | `Page<AuditEventResponse>` |
| `GET` | `/audit/entity/{entityType}/{entityId}` | Everything that happened to one row | path | `Page<AuditEventResponse>` |
| `GET` | `/audit/actor/{actorId}` | Everything one person did | path | `Page<AuditEventResponse>` |
| `GET` | `/audit/activity` | How much has happened since a moment | `?since=2026-09-01T00:00:00Z` | `AuditActivityCountResponse` |
| `POST` | `/jobs/fee-reminders` | Re-run the reminder job for a date | `?date=2026-09-03` | `FeeReminderRunResponse` |
| `GET` | `/jobs/fee-reminders` | How many reminders went out that day | `?date=2026-09-03` | `FeeReminderDayCountResponse` |
| `POST` | `/jobs/absence-scans` | Re-run the absence scan for a date | `?date=2026-09-03` | `AbsenceScanResultResponse` |

```text
AuditEventResponse       { id, actorId, actorUsername, entityType, entityId, action,
                           payloadDiff, occurredAt }
FeeReminderRunResponse   { reminderDate, invoicesExamined, sent, skipped, failed }
AbsenceScanResultResponse{ scanDate, studentsExamined, raised, extended, closed }
```

`action` is `CREATE | UPDATE | DELETE`. `payloadDiff` is arbitrary JSON, stored as `JSONB`,
describing the call - with any argument whose name or type matches
`password|passwd|secret|token|credential|signature|otp|pin` replaced by a redaction marker
before the row is written.

Both `POST` job endpoints are safe to call repeatedly for the same date. That is the point
of exposing them: recovery from a failed night is "run it again", and the counters in the
response tell you what the re-run actually did.

### 8.7 Operational endpoints

| Path | Purpose | Notes |
| --- | --- | --- |
| `GET /actuator/health` | Liveness and readiness | `{"status":"UP"}` only. `show-details: never`, so it never describes the datasource to an anonymous caller. |
| `GET /actuator/info` | Build information | - |
| `GET /swagger-ui.html` | Interactive API browser | `SPRINGDOC_ENABLED=false` removes it |
| `GET /v3/api-docs` | OpenAPI 3 description | Same switch |

---

## 9. Testing

### 9.1 Strategy

Two suites, split by what they need to run and by what they can prove.

**Unit tests** (`*Test.java`, run by the Maven Surefire plugin) need nothing but a JVM.
They cover the pieces whose logic is self-contained: the audit aspect's redaction and
payload shedding, the scope arithmetic, JWT issue and verification, the rate limiter's
token accounting, refresh-token hashing and rotation, and every service's decision-making
with its repositories mocked.

**Integration tests** (`*IT.java`, run by the Maven Failsafe plugin) boot the whole
application against a real PostgreSQL started by Testcontainers, and they exist because of
a specific limitation: a mock cannot block. `SELECT ... FOR UPDATE` behaviour under READ
COMMITTED, partial unique indexes, `CHECK` constraints and transaction rollback semantics
are all properties of the database, so a test that mocks the database cannot see them.

This is not a theoretical concern. The first real integration run found **three production
defects that a fully green unit suite had missed** - an entity/DDL type mismatch and two
transactions whose rollback semantics were wrong - because each lived in behaviour a mock
does not have. All three are fixed; `docs/concurrency.md` §6 has the detail.

```text
                    needs           proves                            count
  *Test   surefire  a JVM           logic, in isolation               232 executions
  *IT     failsafe  Docker + PG     locks, constraints, rollback      127 executions
```

### 9.2 Commands

```bash
cd backend

./mvnw test                     # unit only (232 executions) - no Docker needed
./mvnw verify                   # unit + integration (359 total) - Docker REQUIRED
./mvnw -Dtest=PaymentServiceTest test          # one unit class
./mvnw -Dit.test=PaymentCallbackIT verify      # one integration class
```

`verify`, not `test`, is the command that means "everything". Coverage lands in
`backend/target/site/jacoco/index.html` after a green `verify`.

The frontend has no test runner. Its gate is the type checker:

```bash
cd frontend
npm run typecheck               # tsc --noEmit
npm run build                   # next build also typechecks, and fails on an error
```

### 9.3 What lives where

```text
backend/src/test/java/com/hostelops/
├── audit/          AuditAspectTest             17
├── config/         AttendancePropertiesTest     9
├── domain/         HostelScopeTest              6
├── security/       AccessScopeTest             19
│                   JwtServiceTest              13
│                   LoginRateLimiterTest        10
│                   RefreshTokenServiceTest     20
├── service/        AllocationServiceTest       12
│                   ApplicationServiceTest      11
│                   FeeReminderServiceTest      12
│                   FeeServiceTest              13
│                   PaymentServiceTest          25
│                   RoomServiceTest             15
│                   StudentServiceTest          13
├── validation/     PhoneNumberValidatorTest     6
│                                            -----
│                   15 classes, 201 declared methods -> 232 executions
│
├── support/        AbstractPostgresIT + ~30 seeding helpers
└── (integration)   AuthFlowIT                  22
                    WardenScopeIT               15
                    ApplicationLifecycleIT      16
                    AllocationConcurrencyIT      7
                    PaymentCallbackIT           15
                    FeeReminderIdempotencyIT     9
                    AbsenceScanIdempotencyIT     8
                    SchemaAgreementIT           11
                                             -----
                    8 classes, 103 declared methods -> 127 executions
```

Declared methods differ from executions because parameterised tests (`@ParameterizedTest`)
run once per case.

`AbstractPostgresIT` starts one container in a static initialiser and never stops it, so all
eight classes share a single PostgreSQL for the whole run. It exposes about thirty
`protected` helpers - `seedWarden`, `seedStudent`, `seedRoom`, `seedFee`, `seedAbsentRun`,
`activeAllocationCount`, `feeStatusOf`, `amountPaidPaiseOf` and so on - so a test body reads
as the scenario it describes rather than as fixture assembly.

### 9.4 The four tests that carry the argument

`docs/concurrency.md` makes five claims about correctness under concurrency. Four
integration classes are the executable evidence for them; everything else is support.

| Class | What it proves |
| --- | --- |
| `AllocationConcurrencyIT` | Two transactions racing for the last bed in a room: one wins, one gets `ROOM_FULL`, and the room never holds more students than beds. |
| `PaymentCallbackIT` | A redelivered gateway callback credits an invoice exactly once, including the partial-payment case; a bad signature settles nothing. |
| `FeeReminderIdempotencyIT` | Running the reminder job twice for the same date sends one reminder per invoice, not two - and the second run reports them as `skipped`. |
| `AbsenceScanIdempotencyIT` | Running the absence scan repeatedly for the same date converges: alerts are raised once, extended while the absence continues, closed when the student returns. |

**A concurrency test that passes proves nothing on its own** - a test that never actually
interleaves would pass too. So the decisive one was checked the other way round. With the
attempt-row lock removed, `concurrentRedeliveriesOfAPartPaymentCreditTheInvoiceOnce` fails:
a 22,500-paise part payment is credited **twice** against a 45,000-paise invoice. That
failure is what makes its green result mean something, and the output is recorded in
`docs/concurrency.md` under "What is actually verified".

`SchemaAgreementIT` deserves a separate mention: it boots the application with Hibernate's
`validate` mode purely to assert that the JPA entities and the Flyway migrations still
describe the same tables. It asserts almost nothing itself; its value is that entity/DDL
drift becomes a red build instead of a failed production start.

### 9.5 Test prioritisation

**Not applicable: this project has no test prioritisation or test-selection mechanism.**
There is no ConTest-style scheduler, no config-driven ordering, no flakiness-ranked or
history-ranked execution, and no coverage-based test selection. Every test runs on every
build, in whatever order JUnit chooses, and no test depends on another having run first.

What the build *does* have is a two-tier split, which serves a related purpose - getting
fast feedback first - by a much simpler mechanism. It is a filename convention, configured
in `backend/pom.xml`:

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>   <!-- unit: bound to `test` -->
  <configuration>
    <includes><include>**/*Test.java</include></includes>
    <excludes><exclude>**/*IT.java</exclude></excludes>
  </configuration>
</plugin>

<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>   <!-- integration: bound to `verify` -->
  <configuration>
    <includes><include>**/*IT.java</include></includes>
  </configuration>
</plugin>
```

To configure it: **name the file**. `FooTest.java` runs under `./mvnw test` with no Docker;
`FooIT.java` runs only under `./mvnw verify` and gets a real PostgreSQL. That is the whole
mechanism, and there is nothing else to switch on.

The integration profile (`backend/src/test/resources/application-integration.yml`) does
adjust four settings, each for a stated reason:

| Setting | Value | Why |
| --- | --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | `32` | So `AllocationConcurrencyIT` measures the row lock rather than a connection-pool queue. With the production pool of 10, threads would block waiting for a connection and the test would prove nothing about the lock. |
| `spring.datasource.hikari.connection-timeout` | `30000` | Same reason: a test that starves for connections should be slow, not failed. |
| `app.rate-limit.auth.enabled` | `false` | `AuthFlowIT` signs in far more than five times in fifteen minutes. |
| `app.scheduling.enabled` | `false` | The jobs are invoked directly with a fixed business date; a background timer firing mid-test would be a race in the test harness. |

The datasource URL is deliberately absent from that file - Spring Boot's `@ServiceConnection`
injects whatever address Testcontainers assigned. The signing key in it is a non-secret test
value, with a comment in the file saying so.

---

## 10. Configuration and Customization

### 10.1 Every environment variable

All backend configuration lives in one file,
`backend/src/main/resources/application.yml`, and every value in it that varies by
environment is an environment-variable placeholder. This is the complete list, with the
default that applies when the variable is unset.

| Variable | Default | What it controls |
| --- | --- | --- |
| `JWT_SECRET` | *(none)* | HS256 signing key for access tokens. **No default on purpose** - an unset value stops the application rather than falling back to something an attacker could guess and use to mint an admin token. Generate with `openssl rand -base64 48`. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/hostelops` | JDBC URL |
| `DATABASE_USERNAME` | `hostelops` | Database role |
| `DATABASE_PASSWORD` | `hostelops` | Database password |
| `DATABASE_POOL_SIZE` | `10` | HikariCP maximum pool size |
| `SERVER_PORT` | `8080` | Listening port |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated origins the browser may call from. Must name the console's origin exactly. |
| `REFRESH_COOKIE_SECURE` | `true` | Whether the refresh cookie is `Secure`. **Must be `false` for local HTTP**, and must never be `false` anywhere else. |
| `PAYMENT_PROVIDER` | `mock` | Which gateway adapter to use: `mock` or `razorpay`. Resolved at startup; a bad name fails the boot. |
| `RAZORPAY_KEY_ID` | *(empty)* | Razorpay publishable key. Required only when `PAYMENT_PROVIDER=razorpay`. |
| `RAZORPAY_KEY_SECRET` | *(empty)* | Razorpay signing secret. Same condition. |
| `SCHEDULING_ENABLED` | `true` | `false` means the timer threads are never created at all |
| `SPRINGDOC_ENABLED` | `true` | `false` removes Swagger UI and `/v3/api-docs` |
| `APP_LOG_LEVEL` | `INFO` | Log level for `com.hostelops` |
| `SPRING_PROFILES_ACTIVE` | *(none)* | `dev` enables `DevDataSeeder`; `integration` is used by the test suite |
| `DEV_SEED_PASSWORD` | *(empty)* | Password for the demo accounts. Empty means **no accounts are created at all**. |

Frontend variables, all of which are inlined into the JavaScript bundle at build time and
are therefore public by construction:

| Variable | Default | What it controls |
| --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080` | The origin **the browser** uses to reach Spring |
| `NEXT_PUBLIC_MOCK_GATEWAY_SECRET` | *(unset)* | Development only: lets the pay panel sign a mock callback locally. Unset means the completion step is not rendered at all. Never set this in a built image - it would publish a signing key to every visitor. |

### 10.2 Fixed settings worth knowing about

These are not environment variables, but they are decisions rather than accidents.

| Setting | Value | Why it is what it is |
| --- | --- | --- |
| `app.jwt.access-token-ttl` | `PT15M` | Short, because the access token is not revocable |
| `app.jwt.refresh-token-ttl` | `P30D` | Long, because it *is* revocable and is rotated on every use |
| `app.jwt.cookie` | `hostelops_refresh`, path `/api/v1/auth`, `SameSite=Strict` | Scoped to the only endpoints that need it |
| `app.rate-limit.auth` | 5 tokens, refilled 5 per `PT15M` | Per (username, client IP) |
| `app.attendance.absence-alert-threshold` | `10` | Consecutive absent working days before an alert |
| `app.attendance.weekend-days` | `SATURDAY,SUNDAY` | Not counted as absences |
| `app.attendance.holidays` | *(empty)* | Comma-separated ISO dates, also not counted |
| `app.scheduling.fee-reminder-cron` | `0 17 7 * * *` UTC | 07:17 daily |
| `app.scheduling.absence-scan-cron` | `0 23 6 * * *` UTC | 06:23 daily |
| `spring.jpa.hibernate.ddl-auto` | `none` | Flyway owns the schema; Hibernate never alters anything |
| `spring.jpa.open-in-view` | `false` | No lazy loading in a controller; the service decides what is fetched |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | Everything stored and compared in UTC |
| `spring.jackson.default-property-inclusion` | `always` | Null fields are emitted explicitly, so a client can tell "absent" from "null" |
| `spring.flyway.validate-on-migrate` | `true` | An edited, already-applied migration fails the boot |
| `server.error.include-*` | all `false` | No stack traces, messages or binding errors in a response |
| `server.shutdown` | `graceful` | In-flight requests finish before the process exits |
| `management.endpoints.web.exposure.include` | `health,info` | Nothing else is exposed |
| `management.endpoint.health.show-details` | `never` | Health says `UP`, not what the datasource is |
| `spring.threads.virtual.enabled` | `false` | Deliberate: virtual threads and pinned pessimistic locks have not been tested together here |

> **To be confirmed by author:** both crons are expressed in UTC, which puts them at 12:47
> and 11:53 India Standard Time. The source calls each one a "nightly" job, so if the
> intended local time is overnight the cron expressions - or the `zone` on the
> `@Scheduled` annotations - need revisiting. Nothing about correctness depends on it: both
> jobs are idempotent and re-runnable for any business date, so the schedule only decides
> when the work happens unattended.

### 10.3 Behaviour by environment

| | Local (`dev`) | Integration tests | Deployment |
| --- | --- | --- | --- |
| Spring profile | `dev` | `integration` | *(none)* |
| Demo accounts | Created if `DEV_SEED_PASSWORD` is set | Seeded per test | Never - `DevDataSeeder` is `@Profile("dev")` |
| Refresh cookie | `Secure=false` (HTTP) | n/a | `Secure=true` |
| Login throttle | On | **Off** | On |
| Scheduler | On | **Off** | On, and safe on every instance |
| Database | Compose container, persistent volume | Throwaway Testcontainers instance | Managed PostgreSQL |
| Pool size | 10 | 32 | `DATABASE_POOL_SIZE` |
| Swagger UI | On | On | Consider `SPRINGDOC_ENABLED=false` |

### 10.4 Extension points

The seams that exist because something was expected to change:

**Add a payment gateway.** Implement `PaymentGateway` (five methods), annotate it
`@Component`, and set `PAYMENT_PROVIDER` to whatever `name()` returns.

```java
@Component
public class NewGateway implements PaymentGateway {
    @Override public String name() { return "newgateway"; }
    @Override public String publicKey() { return keyId; }
    @Override public PaymentOrder createOrder(PaymentOrderRequest request) { ... }
    @Override public boolean verify(String orderId, String paymentId, String signature) { ... }
    @Override public void assertConfigured() { ... }  // called at startup
}
```

Three rules the interface documents: an implementation must be stateless and thread-safe;
a *declined* payment is `verify() == false`, not an exception, because a decline is an
answer rather than a failure; and `assertConfigured()` is called during startup so missing
credentials fail the boot rather than the first payment. `PaymentGatewayRegistry` detects
duplicate names and an unknown `PAYMENT_PROVIDER` at construction time. Existing invoices
keep settling through the provider recorded on their attempt rows, so switching the default
is safe mid-flight.

**Actually send notifications.** Implement `Notifier` - one method - and the reminder and
alert paths start delivering instead of logging. Today the only implementation is
`LoggingNotifier`, which writes `NOTIFY to=... subject=...` at `INFO` and the body at
`DEBUG`. Nothing else in the codebase knows how a message leaves.

```java
public interface Notifier {
    void send(String to, String subject, String body);
}
```

**Audit a new operation.** Annotate the service method. The aspect records actor, entity,
action and a JSON payload, inside the caller's transaction:

```java
@Audited(entity = AuditEntity.ROOM, action = AuditAction.UPDATE, idParam = "roomId")
@Transactional
public RoomResponse renameRoom(Long roomId, String name) { ... }
```

`idParam` is matched by *parameter name*, which works because the build compiles with
`-parameters`; a name that does not exist throws at startup with a list of the ones that
do, rather than silently recording no id. Two limits are documented rather than hidden: a
method calling another method on the same object bypasses the proxy and is not audited, and
a method that throws produces no event (failed logins are recorded separately by
`AuthService`).

**Change the academic calendar.** `app.attendance.weekend-days` and
`app.attendance.holidays` are configuration, and the properties class rejects a threshold
below 1 at startup rather than behaving strangely later.

**Add a hostel.** `HostelScope` maps a warden's scope to a gender and a set of hostel
types - `LH` covers female students in `{LH}`, `MH` covers male students in `{BH, MH}`.
Adding a hostel means extending that enum and adding rooms in a new migration.

> **To be confirmed by author:** `REMINDER_LEAD_DAYS = 7` in `FeeReminderService` - how many
> days before the due date a reminder goes out - is a `private static final int`, not a
> configuration property. Making it configurable would be a one-line change if a different
> lead time is ever wanted.

---

## 11. Deployment

### 11.1 Local

```bash
docker compose up --build          # everything
docker compose up -d db            # just Postgres, for running the halves natively
docker compose down                # stop, keep the data
docker compose down -v             # stop, delete the volume, back to a clean seed
docker compose logs -f backend     # follow one service
```

### 11.2 Building the images

Both images are multi-stage, so the build tooling never reaches the runtime layer.

```bash
docker compose build                                   # both, via Compose
docker build -t hostel-ops-backend ./backend           # backend alone
docker build -t hostel-ops-frontend \
  --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.edu ./frontend
```

**The backend image**: `maven:3.9-eclipse-temurin-17` resolves dependencies in a layer keyed
on `pom.xml` alone (so editing Java does not re-download the world), packages the jar with
`-DskipTests`, and copies it into `eclipse-temurin:17-jre-alpine`. It runs as an
unprivileged user, health-checks itself with BusyBox `wget` against `/actuator/health`, and
sets `-XX:MaxRAMPercentage=75.0` rather than a fixed `-Xmx` so the JVM sizes its heap from
the container's cgroup limit instead of the host's total memory - which is the difference
between honouring a memory limit and being OOM-killed by it.

Tests are not run inside the image build: running the integration suite there would need
Docker inside Docker to prove what CI has already proven.

**The frontend image**: three stages (`npm ci`, `next build`, runtime). Only Next's
`standalone` output plus `.next/static` and `public/` reach the runtime image - no npm, no
lockfile, no dev dependencies, no source. It also runs as an unprivileged user.

**The one thing to get right when building the frontend**: `NEXT_PUBLIC_*` variables are
inlined into the JavaScript bundle **at build time**. They are build arguments, not runtime
environment - setting `NEXT_PUBLIC_API_BASE_URL` in `docker run` is silently ignored,
because the string is already compiled into the chunks the browser downloads. The same fact
means every `NEXT_PUBLIC_*` value is public. Pass a URL; never pass a secret.

### 11.3 Deploying somewhere real

> **To be confirmed by author: no cloud deployment is configured in this repository.** There
> is no Terraform, no Kubernetes manifest, no Helm chart, no platform configuration file
> and no deployment job in CI. The images are built and never pushed. What follows is
> therefore guidance rather than documentation of an existing setup.

The stack is two stateless containers and a managed PostgreSQL, so it fits any platform that
runs a container and injects environment variables - a container service, a small Kubernetes
cluster, or two application-platform services.

A deployment checklist, drawn from what the code actually requires:

| Step | Detail |
| --- | --- |
| 1. Provision PostgreSQL 17 | Set `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`. Flyway migrates on first boot. |
| 2. Generate a real `JWT_SECRET` | `openssl rand -base64 48`, stored in the platform's secret manager. Not in an image, not in Compose, not in git. |
| 3. **Do not set `SPRING_PROFILES_ACTIVE=dev`** | That is what keeps `DevDataSeeder` from creating demo logins. Leave `DEV_SEED_PASSWORD` unset as well. |
| 4. Serve HTTPS and leave `REFRESH_COOKIE_SECURE=true` | The default is correct; only local HTTP needs it off. |
| 5. Set `CORS_ALLOWED_ORIGINS` to the console's real origin | And build the frontend with `NEXT_PUBLIC_API_BASE_URL` set to the API's real origin. These two are a pair. |
| 6. Decide about Swagger | `SPRINGDOC_ENABLED=false` if the API description should not be public. |
| 7. Size the pool | `DATABASE_POOL_SIZE` per instance, against the database's connection limit. |
| 8. Consider `SCHEDULING_ENABLED` | It is safe to leave on for every instance - the jobs are bounded by unique constraints, not by being run once - but a dedicated worker keeps the logs simpler. |
| 9. Create the first admin | There is no bootstrap endpoint and no seeded admin outside `dev`. **To be confirmed by author:** the intended production path for creating the first account is not documented in the repository. Inserting one `users` row with a BCrypt hash is the obvious answer. |

The health endpoint `/actuator/health` is what a load balancer should poll; it returns
`{"status":"UP"}` and nothing about the datasource.

### 11.4 CI/CD

`.github/workflows/ci.yml` runs on every push and pull request: **three jobs in parallel,
all required, nothing deployed.**

| Job | Steps | Timeout |
| --- | --- | --- |
| `backend` | JDK 17 (Temurin) with the Maven cache, then `./mvnw -B --no-transfer-progress verify`. Test reports are uploaded when it fails; the JaCoCo report when it passes. | 25 min |
| `frontend` | Node 22, `npm ci`, `npm run typecheck`, `npm run build` with `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`. | - |
| `images` | Docker Buildx, then `docker compose build`. Nothing is pushed and no container is started. | - |

Two details worth copying:

- The job runs **`verify`, not `test`** - so the integration suite is what gates a pull
  request, not just the unit tests. GitHub's runners provide a Docker daemon, which is what
  Testcontainers needs.
- The `images` job sets `JWT_SECRET: ci-interpolation-placeholder-no-container-is-started`.
  That is not a secret and does not need to be: `docker-compose.yml` declares
  `${JWT_SECRET:?...}`, which fails interpolation if the variable is missing, and this job
  only builds images. The value is a comment written in the shape of a variable.

The workflow also sets `permissions: contents: read` (least privilege for the token) and
`concurrency: cancel-in-progress: true` (a new push supersedes the previous run).

There is no release, publish or deploy job. **To be confirmed by author:** whether images
are meant to be pushed to a registry, and where.

---

## 12. Security and Privacy

### 12.1 Authentication

Stateless JSON Web Tokens (JWT - a signed, self-describing token the server can verify
without storing anything), in two parts with deliberately different properties:

| | Access token | Refresh token |
| --- | --- | --- |
| Lifetime | 15 minutes | 30 days |
| Algorithm | HS256 (HMAC-SHA256) | Not a JWT - opaque random bytes |
| Where the browser keeps it | A module variable in JavaScript memory | An `HttpOnly` cookie |
| Readable by page scripts | Yes, by design | **No** |
| Survives a page reload | No | Yes |
| Revocable | No - hence 15 minutes | Yes, and rotated on every use |
| Stored server-side | Nothing | An unsalted SHA-256 hash of the value |

**No token is ever put in `localStorage`.** The access token lives in a module variable and
is lost on reload, which is what makes a cross-site scripting bug unable to walk off with a
long-lived credential; the refresh token is an `HttpOnly`, `Secure`, `SameSite=Strict`
cookie scoped to `/api/v1/auth`, which page JavaScript cannot read at all. On reload the
console calls `/auth/refresh` and gets a fresh access token from the cookie.

The refresh token is **rotated on every redemption**: redeeming it issues a replacement and
invalidates the old value, so a stolen token stops working the moment the real user's
console refreshes - and a replayed one is a detectable event rather than a silent
continuation.

Storing only a SHA-256 hash of the refresh token, without a salt, is deliberate and worth
explaining because it looks like a mistake: the token is 30+ bytes of cryptographically
random data, not a human-chosen password, so it is not vulnerable to dictionary or
rainbow-table attack, and the lookup has to be *by* the hash - a per-row salt would make
finding the row impossible without scanning every one. Passwords, which *are* human-chosen,
use BCrypt through Spring's delegating encoder, so stored hashes carry a `{bcrypt}` prefix
and the algorithm can be migrated later without invalidating existing ones.

**Login throttling** is a token bucket per (username, client IP): five attempts, refilled
five per fifteen minutes, and a 429 with `Retry-After` when it is empty. Keying on the pair
rather than the IP alone is intentional - a campus behind one NAT address must not be able
to lock itself out collectively.

**Why CSRF protection is disabled**, given that the refresh token *is* a cookie. Every
endpoint except `/auth/refresh` and `/auth/logout` authenticates from the `Authorization`
header, which a browser never attaches automatically, so those are structurally immune to
cross-site request forgery. The two cookie-bearing endpoints are protected by
`SameSite=Strict` on the cookie itself: a cross-site POST does not carry it. And a same-site
forgery would gain nothing readable, because the response is JSON that the attacker's origin
cannot read past CORS - the worst outcome is rotating the victim's own token. (The
predecessor was not in this position: it used session cookies with CSRF exemptions
sprinkled across the API.)

### 12.2 Authorisation

Two independent layers, and the second is the one that matters.

**Layer 1 - the URL prefix is the role gate.** `/api/v1/admin/**` requires `ADMIN`,
`/warden/**` requires `ADMIN` or `WARDEN`, `/student/**` requires `ADMIN` or `STUDENT`, and
`anyRequest().authenticated()` catches everything else. That last line is the important
one: **a new endpoint is protected the moment it exists**, which is the opposite of the
pattern where each view has to remember to add its own decorator. It is also why an unknown
path returns 401 rather than 404.

**Layer 2 - row visibility is a scope, not a role check.** A warden may reach
`/warden/students`, but *which* students they see is decided by an `AccessScope` value that
is a required parameter of every scoped repository query. A warden cannot accidentally
write a query that ignores the scope, because there is no overload that omits it.

```java
// HostelScope maps a warden to the students they may see
LH -> female students in hostels {LH}
MH -> male students   in hostels {BH, MH}
```

There is **not a single `@PreAuthorize` or `@Secured` annotation in the codebase**. That is
the design: an annotation guards a method, while a scope parameter guards a row, and it is
the row that needs guarding. `WardenScopeIT` asserts the narrowing from the outside - a
warden requesting another hostel's student gets `403 OUT_OF_SCOPE` from Spring, not a
redirect from the client *(verified at runtime)*.

### 12.3 Secret management

| Secret | Where it lives | What stops it leaking |
| --- | --- | --- |
| `JWT_SECRET` | Environment only | No default. An unset value **stops the application** instead of falling back to something guessable. `JwtService` also rejects a key shorter than the HMAC digest rather than padding it to fit. |
| Demo account password | `DEV_SEED_PASSWORD`, environment only | There is **no hardcoded fallback password anywhere in the codebase**. Unset means no accounts are created at all - not "created with a default". |
| Database password | Environment only | - |
| Razorpay key secret | Environment only, and only needed when that provider is selected | `assertConfigured()` fails the boot if it is missing |
| Mock gateway secret | A constant in `MockPaymentGateway` | It signs nothing real. It exists so the signature path is exercisable offline. |
| Refresh tokens at rest | `refresh_tokens`, as SHA-256 hashes | The raw value is returned once, in a cookie, and never stored |
| Passwords at rest | `users.password_hash`, BCrypt | - |

Two supporting details. `DevDataSeeder` is `@Profile("dev")` and is **not a migration**,
precisely because migrations run in every environment including production - seeded logins
in `V*.sql` would be exactly the default-credential problem this rebuild set out to remove.
And the audit trail redacts before writing: any argument whose parameter name or declared
type matches `password|passwd|secret|token|credential|signature|otp|pin` is replaced with a
marker, so a credential cannot reach `audit_events` even by accident.

### 12.4 Input validation and injection

| Concern | How it is handled |
| --- | --- |
| **SQL injection** | Every query is either a Spring Data derived method or a parameterised JPQL/native query. No string concatenation builds SQL anywhere. |
| **Request validation** | Jakarta Bean Validation on the DTO records - `@NotNull`, `@NotBlank`, `@Positive`, `@Size`, plus a custom `PhoneNumberValidator`. A violation is `400 VALIDATION_FAILED` with per-field details, and the controller body never runs. |
| **Defence in depth** | The database repeats the important rules as `CHECK` constraints: `amount_paise > 0`, statuses drawn from a closed set, a notice's expiry after its publication. A bug in Java cannot write a row that breaks them. |
| **Cross-site scripting** | React escapes interpolated text by default, and the console never calls `dangerouslySetInnerHTML`. Notice bodies are rendered as text, not as HTML. |
| **Mass assignment** | Request and response DTOs are separate records; an entity is never bound directly to a request body, so a client cannot set a field the endpoint did not intend to expose. |
| **Error disclosure** | `server.error.include-message`, `include-stacktrace`, `include-binding-errors` and `include-exception` are all off. Clients get an error *code*; the stack trace goes to the log. |
| **Timing attacks** | Payment signatures are compared with `MessageDigest.isEqual`, which takes the same time whether the mismatch is in the first byte or the last. |
| **Enumeration** | An unauthenticated request to any `/api/v1/**` path returns the same 401, so an anonymous caller cannot discover which endpoints exist *(verified at runtime)*. |
| **Health disclosure** | `/actuator/health` returns `{"status":"UP"}` with `show-details: never` - it does not describe the datasource to whoever asks. |

### 12.5 Privacy

The system stores names, roll numbers, email addresses, mobile numbers, a parent's mobile
number, gender, year of study, room assignment, attendance and payment history. That is a
minor's personal data in many cases, and the honest summary is:

- Access is narrowed by scope: a warden sees their own hostel's students, a student sees
  themselves.
- The audit trail records every mutation with an actor, which is what makes misuse
  detectable after the fact.
- Every seeded email is `@example.edu`, a reserved documentation domain, so demo data
  cannot reach a real inbox.

> **To be confirmed by author:** there is no data-retention policy, no deletion or export
> endpoint, and no consent record. For a college deployment those are likely to be
> requirements rather than nice-to-haves, and none of them exist today.

### 12.6 Security limitations, stated rather than discovered later

- **The rate limiter is in-memory.** Buckets live in a `ConcurrentHashMap`, so with N
  instances behind a load balancer the effective limit is N x 5 attempts. The code says so
  and names the fix (a shared Redis backend, which Bucket4j supports).
- **Rate limiting covers `/auth` only.** No other endpoint is throttled, so an
  authenticated client can hammer any of the other 68.
- **`mock` is the default payment gateway.** A deployment that forgets to set
  `PAYMENT_PROVIDER` will accept mock signatures - and the mock secret is in the source.
  `MockPaymentGateway.assertConfigured()` logs a warning at startup; it does not refuse to
  start.
- **The access token cannot be revoked** before its 15 minutes are up. Revoking the refresh
  token stops renewal but does not invalidate a token already issued.
- **Swagger UI is on by default**, including in a deployment, unless `SPRINGDOC_ENABLED` is
  set to `false`.
- **`traceId` appears only on a 500.** An unhandled exception generates a random
  12-character id, logs it with the stack trace, and returns it - so a user's report can be
  matched to a log line without exposing the trace. Every expected error (401, 403, 404,
  409 and so on) carries `"traceId": null`, since its `code` already says what happened.
  That is the intended behaviour rather than a gap, but it is worth knowing before you go
  looking for a trace id that will not be there.
- **No secrets scanning or dependency scanning in CI.** Three jobs run; none of them is
  `dependabot`, `npm audit` or a secret scan.

---

## 13. Performance

### 13.1 Characteristics

The workload is small and read-dominated, which is worth stating plainly because it justifies
several choices that would be wrong at a different scale. A hostel has a few hundred students;
a warden loads a page a few dozen times a day; the register is written once per day per
hostel. Nothing here is a high-throughput system, and it is not designed as one.

Actual figures, from the run used to verify this document *(single Docker container each,
laptop-class hardware, `postgres:17-alpine`)*:

| Measurement | Observed |
| --- | --- |
| Backend cold start, including Flyway | a few seconds to `{"status":"UP"}` |
| Authenticated API read | tens of milliseconds |
| Full integration suite, 127 executions | inside CI's 25-minute budget, one shared container for all 8 classes |

> **To be confirmed by author:** there is no load test, no benchmark and no profiling run in
> the repository, so there are no throughput or percentile-latency figures to quote. The
> numbers above are informal observations from one manual session, not measurements.

### 13.2 What was done deliberately

**Indexes for the queries that actually run.** `V1__init.sql` adds indexes keyed to real
access patterns rather than to every column: `idx_fee_payments_fee`,
`idx_fee_payments_student`, `idx_audit_entity`, `idx_audit_recent`, `idx_audit_actor`, and a
*partial* index for the reminder job:

```sql
CREATE INDEX idx_hostel_fees_outstanding ON hostel_fees (due_date)
  WHERE status IN ('UNPAID','PARTIALLY_PAID');
```

The `WHERE` clause is the point: the reminder job only ever asks about unsettled invoices,
so the index does not carry the paid ones - which, over years of terms, is most of the table.

**A bounded window for the absence scan.** One scan reads back 90 calendar days, not the
whole register. The query it feeds returns one row per student per day; over a year that
would mean hydrating the entire register nightly to answer a question about the last
fortnight.

**JDBC batching.** `hibernate.jdbc.batch_size: 25` with `order_inserts` and `order_updates`
enabled, which matters for the one write that is genuinely bulk - marking a hostel's register,
up to 1000 rows in a request.

**`open-in-view: false`.** The JPA session does not stay open into view rendering, so a
controller cannot trigger a lazy load and turn one response into N queries. What the service
fetched is what the controller has.

**Locks held for as little as possible.** Payment settlement verifies the gateway signature
*before* taking any lock, because an HTTP round trip to a payment provider is orders of
magnitude slower than a database write and holding a row lock across it would serialise
every payment behind the slowest network call.

**Counting instead of caching.** Occupancy is a `COUNT` over allocation rows on every read.
That is slower than reading a counter column and it is the entire reason this rebuild exists:
the predecessor's cached `occupied_beds` drifted and reported full rooms that had free beds.
A correct number computed each time beats a fast number that is wrong.

### 13.3 Known bottlenecks

| Bottleneck | Why it is there | When it would matter |
| --- | --- | --- |
| Row lock on a room during allocation | Two allocations into the *same* room serialise, by design - that is the correctness guarantee | Only if many students were allocated into one room simultaneously. Different rooms do not contend. |
| Row lock on an invoice during settlement | Same: two payments against one invoice serialise | Never, in practice - one student pays one invoice |
| Connection pool of 10 per instance | A sensible default for a small deployment | Under real concurrency; raise `DATABASE_POOL_SIZE`. The integration profile already uses 32 so that a concurrency test measures the lock and not the pool. |
| `audit_events` grows without bound | Every mutation writes a row, and nothing prunes it | Years of use. `idx_audit_recent` keeps the queries fast; the table still grows. No archival exists. |
| Absence scan is a single transaction | If one student's insert violates a constraint, Postgres aborts the whole run | Acceptable because recovery is "run it again" - the scan is idempotent. Per-student savepoints were considered and rejected as not worth the complexity for a nightly job. |
| Complaint analytics computes percentiles per request | Nothing is materialised | A large complaint history with a wide `windowDays` |

### 13.4 Ideas for later

- **Cursor pagination** for the audit trail. Offset pagination re-reads and discards
  everything before the page, which gets slower the deeper you go.
- **A shared rate-limit backend** (Redis), which would also make the limit correct across
  instances rather than merely present.
- **A materialised occupancy view**, refreshed transactionally, *if* profiling ever shows the
  `COUNT` to be a real cost. Worth naming only to say what the guardrail is: it must be
  maintained by the database inside the same transaction as the allocation, or it reintroduces
  precisely the bug the project was written to remove.
- **Archiving `audit_events`** to a cold table or an object store past a retention horizon.
- **A load test**, so this section can contain measurements instead of observations.

---

## 14. Known Issues and Limitations

Stated here rather than discovered later. None of these is a defect in the sense of "the code
does not do what it says"; they are places where the system does less than a reader might
assume, and it is cheaper to know now.

### 14.1 Functional gaps

- **No frontend tests.** The only automated gate on the console is the TypeScript compiler:
  `next build` typechecks, and a type error fails both CI and the Docker image build. That
  catches contract drift against `src/lib/types.ts` - a renamed API field breaks the build -
  and catches nothing at all about behaviour. No component renders in a test, no click is
  simulated, and no route is asserted.
- **Notifications are logged, not sent.** `Notifier` has one implementation,
  `LoggingNotifier`, which writes a line to the log. There is no SMTP, no SMS provider and no
  push transport. The fee-reminder and absence-alert paths prove *when* a notification is
  decided and that the decision happens exactly once per day per invoice; they prove nothing
  about delivery, because nothing is delivered.
- **`mock` is the default payment gateway, and the real one is untested.** The Razorpay
  adapter implements the same `PaymentGateway` interface and the same HMAC-SHA256 signature
  scheme, so switching is a matter of setting `PAYMENT_PROVIDER=razorpay` with credentials.
  It has never been pointed at the live provider. Treat it as a correct-looking
  implementation, not a proven one.
- **No rate limiting outside `/auth`.** Login is throttled; every other endpoint is not. An
  authenticated client can call any read endpoint as fast as it likes.
- **The rate limiter is per-instance.** Buckets live in a `ConcurrentHashMap` inside the JVM.
  Two backend replicas means two independent limits, so the effective allowance is doubled and
  a client that reconnects to the other instance starts fresh. This is fine for the
  single-instance deployment the compose file describes and wrong the moment you scale out;
  a shared store (Redis) is the fix, listed in §15.
- **No self-service account creation, and no documented path to the first production admin.**
  `DevDataSeeder` creates accounts, but only under the `dev` profile and only when
  `DEV_SEED_PASSWORD` is set. In a `prod` profile it seeds nothing, which leaves no way to
  authenticate for the first time.
  > **To be confirmed by author:** whether the intended answer is a one-off SQL insert, an
  > admin bootstrap flag, or a separate provisioning step. No mechanism exists in the
  > repository today.
- **`REMINDER_LEAD_DAYS` is not configurable.** The fee reminder fires 7 days before a due
  date because `FeeReminderService` declares `REMINDER_LEAD_DAYS = 7` as a constant. Changing
  it is a recompile, not a setting.

### 14.2 Operational gaps

- **No cloud deployment exists.** There is no Terraform, no Kubernetes manifest, no Helm
  chart, no `deploy` job in CI, and no registry the images are pushed to. §11 documents how to
  build and run the images; where they run is an open question.
  > **To be confirmed by author:** the intended hosting target, and whether the images are
  > meant to be published to a registry.
- **No `LICENSE` file.** Confirmed by listing the tracked files: the repository has none. That
  means the code is, strictly, all-rights-reserved by default - not open source, whatever the
  README's tone suggests. See §16.
- **`audit_events` is never pruned.** Every mutating service call writes a row and nothing
  deletes or archives one. The indexes keep reads fast; the table still grows for as long as
  the system runs.
- **No backup or restore procedure.** The compose file keeps Postgres data in a named volume
  (`db-data`); nothing dumps it, rotates it or restores it. `docker compose down -v` deletes
  it outright.
- **Metrics are exposed but nothing scrapes them.** `/actuator/health` and `/actuator/info`
  are open for a load balancer; there is no Prometheus target, dashboard or alert.
- **A single Postgres instance.** No replica, no failover, no read scaling. Appropriate for
  one hostel, worth saying out loud.

### 14.3 Interface shapes that are not contractually stable

**Paged list endpoints return Spring's `Page<T>` directly.** 18 endpoints across 14
controllers - the warden's student, application, allocation, room, attendance, fee, payment,
complaint, notice and absence-alert lists, the student's own complaint, notice and payment
lists, and the three audit reads - return `org.springframework.data.domain.Page<T>` straight
out of the controller method. Spring Data logs a warning about this at startup:

```text
Serializing PageImpl instances as-is is not supported
```

The warning is telling you something real: `PageImpl`'s JSON shape is an implementation
detail, not a published contract, and a future Spring Data version may serialise it
differently. The console's `types.ts` declares the shape it currently sees, so a change would
break the frontend at runtime - and the strict typecheck could not warn about it, because
nothing in the TypeScript build knows what Java will emit. The stable fix is a `PagedResponse`
DTO of the project's own, mapped in the controller; that is a breaking API change and has not
been made.

### 14.4 Documentation drift found while writing this document

Four places where the repository's own prose disagrees with the repository's own code. All
four are wording, not behaviour, and are recorded here rather than silently corrected:

| Where | Says | Actually |
| --- | --- | --- |
| `README.md` line 45 | "26 controllers" | **24** classes annotated `@RestController`. The count of 26 includes `GlobalExceptionHandler` and a comment mentioning `@RestControllerAdvice`. The other figures in that block - 189 backend source files, 72 endpoints, 28 routes - are correct. |
| `backend/.../exception/ApiError.java` Javadoc | "Null members are omitted from JSON" | They are **included**. `spring.jackson.default-property-inclusion` is `always`, so a 401 body carries `"details": null, "traceId": null`. The Javadoc describes the previous `non_null` setting; the change to `always` was deliberate and is explained in `application.yml`, but the Javadoc was not updated with it. |
| `.github/workflows/ci.yml` line 34 comment | "Testcontainers pulls `postgres:17-alpine`" | It pulls **`postgres:16.4-alpine`**, pinned in `AbstractPostgresIT`. Compose runs `postgres:17-alpine`, so both images are correct for their own context - the CI comment simply names the wrong one. |
| `frontend/README.md` line 198 | the backend integration tests "have not been run on the machine this was built on" | They have, since 2026-08-31. The root `README.md` records the local run; the frontend one was not updated alongside it. |

### 14.5 State left in the local development database

Verifying §7 of this document meant driving the running application, which wrote real rows.
If you are looking at a local database that already has data in it, that is why: **application
1 is approved and the student `asha.rao` is allocated to room A101.** Reset it either way:

```bash
# Nuclear: drop the volume and let Flyway plus DevDataSeeder rebuild from scratch
docker compose down -v && docker compose up --build

# Surgical: release the one allocation, as a warden or admin
curl -X DELETE http://localhost:8080/api/v1/warden/allocations/student/1 \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

## 15. Future Work / Roadmap

Ordered by what would most change the system's usefulness, not by what would be most fun to
build. Nothing here is scheduled - this is a list of the next honest steps, not a commitment.

> **Assumption:** this ordering is the author of *this document*'s reading of the codebase and
> its stated limitations. It has not been agreed with anyone.

### 15.1 Close the gaps that make it not-yet-deployable

1. **A real notification transport.** Implement `Notifier` over SMTP (or a provider's API) and
   register it by profile, leaving `LoggingNotifier` as the `dev` implementation. The seam
   already exists - this is one class and a configuration property, and it converts "the
   system decided to remind you" into "you were reminded". Highest value per unit of work in
   the whole list.
2. **A first-admin bootstrap.** Something explicit and auditable: a one-shot command, or a
   startup path that creates an admin from an environment variable and refuses to run twice.
   Until this exists, a `prod` deployment cannot be signed into. See §14.1.
3. **A shared rate-limit store.** Move Bucket4j's buckets to Redis so the login throttle is
   correct across instances rather than merely present, and extend it beyond `/auth` with a
   per-principal allowance.
4. **Backup and restore.** A documented `pg_dump` schedule and a *tested* restore. An untested
   restore is not a backup.

### 15.2 Make the contract stable

5. **Replace `Page<T>` with a project-owned `PagedResponse<T>` DTO.** Removes the Spring Data
   warning, removes the dependency on an implementation detail, and makes the pagination
   envelope something `types.ts` can be generated from rather than transcribed against. This
   is a breaking change to 18 endpoints and wants doing in one commit with the frontend.
6. **Generate the TypeScript client from the OpenAPI document.** springdoc already publishes
   `/v3/api-docs`; generating `types.ts` and `endpoints.ts` from it would make backend/frontend
   drift a build failure instead of a runtime surprise. This is the single change that would
   most reduce the class of bug the strict typecheck currently cannot see.
7. **Version the API properly.** The path already says `/api/v1`, which is the easy half. The
   hard half is deciding what happens at `v2` - parallel controllers, or a deprecation window.

### 15.3 Prove more of it

8. **Frontend tests.** Playwright end-to-end over the flows in §7 (sign in, apply, allocate,
   pay) would cover exactly the behaviour the typecheck cannot. The console was driven
   manually to verify this document; that work is repeatable and should not be manual.
9. **A load test.** Enough to replace §13's observations with numbers, and to find out whether
   counting occupancy per read is actually a cost or merely a theoretical one.
10. **Mutation testing on the concurrency paths.** `docs/concurrency.md`'s claims rest on four
    integration classes; one of them already earns its keep by failing when the attempt lock is
    removed. Making that check systematic - remove a lock, assert the test goes red - would
    turn a manual demonstration into a standing guarantee.

### 15.4 Features the domain obviously wants

11. **Room change and swap requests**, as a first-class workflow rather than a delete followed
    by an allocate. The constraint `uq_allocations_active_student` already guarantees a student
    holds at most one active bed, so the transactional shape is straightforward.
12. **Term rollover.** Nothing currently expires an allocation at the end of an academic year,
    generates the next term's invoices, or archives last year's register.
13. **Fee receipts as PDFs**, and a payment history a student can hand to a parent.
14. **Leave applications**, so a planned absence does not look like ten consecutive missed days
    to `AbsenceScanJob`.
15. **Complaint assignment and SLAs.** The analytics endpoint already computes resolution
    times; a target to compare them against would make the number actionable.
16. **Bulk student import** from a CSV, which is how a hostel actually gets its first 300
    students into a new system.

### 15.5 Operational maturity

17. **Prometheus scraping and a dashboard**, over the actuator endpoints already exposed.
18. **Structured JSON logging** with a request id propagated from the edge, so `traceId` on a
    500 (§12) can be joined to everything else that request touched.
19. **Archive `audit_events`** past a retention horizon, and write down what that horizon is -
    which is a privacy decision as much as a performance one (§12.5).
20. **A `LICENSE` file**, which is the smallest item on this list and blocks anyone else from
    legally using the code until it exists.

---

## 16. References and Credits

### 16.1 What this project is a response to

The system's shape is an argument with a previous one. The original hostel management system
was a Django application with jQuery templates that kept a cached `occupied_beds` counter on
each room. Two wardens allocating at the same moment both read the stale count, both wrote it
back, and the room ended up reporting itself full while beds stood empty - or worse, two
students were assigned the same bed. Nothing about that bug was exotic; it is what happens
when a read and a write are two statements and something else runs in between.

Every distinctive decision in this codebase descends from that: occupancy is counted rather
than cached, the database holds the locks rather than Java holding a flag, uniqueness is a
constraint rather than a lookup, and each of the four claims has an integration test that goes
red when the guard is removed. [`docs/concurrency.md`](concurrency.md) is where those
decisions are argued in full, including the two that were rejected and why - it is the
document this one summarises, and the more interesting read of the two.

### 16.2 Influences worth naming

- **Martin Fowler's writing on optimistic vs pessimistic offline locks**, which is the
  vocabulary `docs/concurrency.md` uses to explain why bed allocation takes a pessimistic lock
  and other paths do not.
- **The PostgreSQL manual's chapter on transaction isolation**, specifically on what
  `READ COMMITTED` does and does not promise. The reason this project does not rely on
  `SERIALIZABLE` is that chapter.
- **Spring Boot's own reference documentation** for the layered-jar and actuator conventions
  the Dockerfiles follow.
- **The Twelve-Factor App** for configuration-through-environment, which is why every setting
  in §10 is an environment variable with a default and no secret has a fallback.

> **Assumption:** these are the influences visible in the code's structure and comments. They
> are not a bibliography the author supplied.

### 16.3 Licence of this project

**There is no `LICENSE` file in the repository.** This was confirmed by listing every tracked
file; no `LICENSE`, `LICENCE`, `COPYING` or equivalent exists.

The practical consequence is worth being blunt about: absent a licence, the code is
all-rights-reserved by default under copyright law. Nobody else may legally copy, modify or
redistribute it, regardless of it being on a public host. For a final-year project that is
often fine; if the intent is for anyone to use or build on this, a licence file is a
one-line fix (§15, item 20).

> **To be confirmed by author:** the intended licence. MIT and Apache-2.0 are the usual
> choices for a project like this - MIT if brevity matters, Apache-2.0 if an explicit patent
> grant does.

### 16.4 Third-party dependencies and their licences

Every dependency is permissively licensed. Nothing here is copyleft in a way that reaches the
application: EPL-2.0 applies to JUnit and JaCoCo, which are build- and test-time only and are
not distributed inside either image.

**Backend** - declared in `backend/pom.xml`:

| Dependency | Version | Licence | What it does here |
| --- | --- | --- | --- |
| Spring Boot (`web`, `data-jpa`, `security`, `validation`, `actuator`, `aop`, `test`) | 3.5.16 | Apache-2.0 | The framework: HTTP, persistence, authentication, bean validation, health endpoints, the AOP that makes `@Audited` work |
| Spring Framework, Spring Data JPA, Spring Security | managed by the parent | Apache-2.0 | Pulled in transitively; Spring Security supplies the filter chain and BCrypt |
| Hibernate ORM | managed by the parent | LGPL-2.1 **or** Apache-2.0 (dual, since 5.x) | The JPA implementation behind Spring Data |
| PostgreSQL JDBC driver (`org.postgresql:postgresql`) | managed by the parent | BSD-2-Clause | Talks to the database |
| Flyway (`flyway-core`, `flyway-database-postgresql`) | managed by the parent | Apache-2.0 | Owns the schema; runs `V1__init.sql` and `V2__room_reference_data.sql` |
| springdoc-openapi (`starter-webmvc-ui`) | 2.8.17 | Apache-2.0 | Generates `/v3/api-docs` and serves Swagger UI |
| JJWT (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`) | 0.12.7 | Apache-2.0 | Signs and verifies the HS256 access tokens |
| Bucket4j (`bucket4j_jdk17-core`) | 8.19.0 | Apache-2.0 | The token-bucket login throttle |
| Lombok | managed by the parent | MIT | Compile-time boilerplate; an annotation processor, not a runtime dependency |
| Jackson | managed by the parent | Apache-2.0 | JSON in and out, including the `default-property-inclusion: always` behaviour of §14.4 |
| Testcontainers (`spring-boot-testcontainers`) | managed by the parent | MIT | Starts the real `postgres:16.4-alpine` the integration suite runs against |
| JUnit 5 (`junit-jupiter`) | managed by the parent | EPL-2.0 | Test framework - test scope only |
| Mockito, AssertJ (via `spring-boot-starter-test`) | managed by the parent | MIT, Apache-2.0 | Unit-test doubles and assertions - test scope only |
| Spring Security Test | managed by the parent | Apache-2.0 | Authenticated request helpers - test scope only |
| JaCoCo Maven plugin | 0.8.13 | EPL-2.0 | Coverage report - build time only |
| Maven Surefire / Failsafe / Compiler plugins | managed by the parent | Apache-2.0 | The unit/integration test split of §9, and the `-parameters` flag `@Audited(idParam=…)` depends on |

**Frontend** - declared in `frontend/package.json`:

| Dependency | Version | Licence | What it does here |
| --- | --- | --- | --- |
| Next.js | ^16.3.3 | MIT | App Router, the build, and `output: 'standalone'` |
| React, React DOM | ^19.2.0 | MIT | The component model |
| TypeScript | ^5.9.0 | **Apache-2.0** | The only automated gate on the console (§14.1). Note: Apache-2.0, not MIT - TypeScript is often misremembered as MIT. |
| `@types/node`, `@types/react`, `@types/react-dom` | ^22.15.0 / ^19.2.0 | MIT (DefinitelyTyped) | Type declarations - dev only |

**Runtime images** - referenced from the Dockerfiles and `docker-compose.yml`:

| Image | Licence of the software it carries |
| --- | --- |
| `eclipse-temurin:17-jre-alpine` | GPLv2 with Classpath Exception (OpenJDK); the exception is what allows an application to link against it without inheriting the GPL |
| `node:22-alpine` | MIT (Node.js) |
| `postgres:17-alpine` (compose) and `postgres:16.4-alpine` (Testcontainers) | PostgreSQL Licence - permissive, OSI-approved, close to BSD |
| `maven:3.9-eclipse-temurin-17` (build stage only) | Apache-2.0 (Maven) |
| Alpine Linux base layers | MIT and other permissive licences per package |

> **To be confirmed by author:** the licences above are the ones each project publishes, read
> from their own documentation rather than extracted from the artifacts on this machine. Before
> distributing anything built from this repository, regenerate the list from the actual
> dependency tree:
>
> ```bash
> # Backend: every third-party licence, resolved from the real dependency tree
> cd backend && ./mvnw license:aggregate-add-third-party
>
> # Frontend: the same for npm
> cd frontend && npx license-checker --summary
> ```

### 16.5 Credits

Written and built by the repository's author (`git log` records the commits;
`Bipin583` is the GitHub account the `origin` remote belongs to). The concurrency argument,
the test design that makes it checkable, and the decision to count occupancy rather than cache
it are the parts worth crediting - they are what the rest of the system is built to protect.

This documentation was assembled by reading the repository: 189 backend source files, 47
frontend source files, both Dockerfiles, the compose file, the CI workflow, the two Flyway
migrations, and all 24 files in the test tree - 15 unit classes, 8 integration classes and
the abstract Testcontainers base they share. Claims about runtime behaviour - the 28 console routes,
the error-body shapes in §8, the occupancy output in §7.2 - were verified by starting the full
stack and driving it on 2026-09-03. Everything that could not be established that way is
marked **Assumption** or **To be confirmed by author** rather than guessed at.
