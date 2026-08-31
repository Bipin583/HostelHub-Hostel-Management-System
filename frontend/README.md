# hostel-ops — frontend

The Next.js console for the hostel-ops platform. Three consoles behind one login: a warden
console for running a hostel, a student portal, and a small admin surface for scheduled
jobs and the audit trail.

The backend lives in `../backend` (Spring Boot 3.5). This app talks to it over HTTP and
has no server of its own beyond Next's static render.

---

## Running it

```bash
cp .env.example .env.local        # then edit if the backend is not on :8080
npm install
npm run dev                       # http://localhost:3000
```

The backend has to be up, and its `CORS_ALLOWED_ORIGINS` has to include
`http://localhost:3000` — the browser calls Spring directly, so CORS is load-bearing
rather than incidental.

| Script | What it does |
| --- | --- |
| `npm run dev` | Development server, hot reload |
| `npm run build` | Production build; fails on a type error |
| `npm start` | Serve a build |
| `npm run typecheck` | `tsc --noEmit` on its own |

### Environment

| Variable | Required | Notes |
| --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | Production yes, development no | Defaults to `http://localhost:8080` in development. A production build throws at startup if it is missing rather than silently calling its own origin. |
| `NEXT_PUBLIC_MOCK_GATEWAY_SECRET` | No, and leave it unset outside development | See [The payment flow](#the-payment-flow). |

### Signing in during development

Seeded accounts are `admin`, `lh_warden`, `mh_warden`, and students such as `asha.rao` and
`arjun.das`. They all share whatever password the backend was started with in
`DEV_SEED_PASSWORD`. The login page lists them, and that hint is compiled out of production
builds.

If sign-in succeeds and the next request 401s, the refresh cookie is being dropped: it is
set `Secure` by default, and `http://localhost` is not secure. Start the backend with
`REFRESH_COOKIE_SECURE=false` for local HTTP.

---

## How it is put together

### One file is the API contract

`src/lib/endpoints.ts` is the only place a URL string appears. Every page calls a named
function on `warden`, `student` or `admin`, and those functions are the single description
of what the backend offers. A page that wants an endpoint that does not exist cannot
quietly invent one — it has to add it there first, next to the others, where the shape is
visible.

`src/lib/types.ts` mirrors the backend DTOs by hand. Generated clients were considered and
rejected: a hand-written mirror is a place to write down *why* a field is nullable, and
those notes are worth more here than the few minutes of typing they cost.

### Auth: access token in memory, refresh token in an httpOnly cookie

There is no BFF and no proxy route. The browser calls Spring directly.

The access token lives in a module-level variable — not `localStorage`, not a
readable cookie — so a successful XSS cannot read it out of storage after the fact. It is
lost on reload, which is fine, because the refresh token is an httpOnly `Secure`
`SameSite` cookie the JavaScript cannot see at all, and the session is rebuilt from it on
first load.

`src/lib/session.ts` holds that state and publishes it through `useSyncExternalStore`, so
every component sees one session and React never tears. Concurrent 401s are funnelled into
a single in-flight refresh; the callers wait on the same promise and retry once.

### Data fetching is deliberately small

`src/lib/use-query.ts` is about a hundred lines: `useQuery` for reads (abortable, with
`enabled`, `refetch`, and a `loading`/`initialLoading` split so a refetch does not blank the
screen), `useAction` for writes. No React Query, no SWR, no client-side cache.

The reason is that nothing in this app needs a cache. Every screen is a fresh look at
operational state — who is in, what is owed, what is broken — and a stale-while-revalidate
layer would mostly be a way to show a warden yesterday's occupancy. The trade is real:
navigating away and back refetches. That is the correct behaviour here.

`useAction` swallows the rejection and stores it, so a handler that needs to *branch* on an
error code needs its own `try`/`catch` — reading `action.error` right after `await run()`
gets the previous render's value. Where a handler only needs to know whether the call
worked, `run()` resolving to `undefined` is the failure signal.

### Money

Every amount crosses the wire as an integer count of paise and stays an integer in this
app. `formatMoney` divides by 100 only inside `Intl.NumberFormat('en-IN')`, and
`rupeeInputToPaise` is the single door in the other direction. No float ever holds a
currency value, so no invoice is ever off by a paisa.

### Dates

`LocalDate` values (`2026-08-26`) are strings from end to end. `formatDate` splits the
string rather than parsing it, because `new Date('2026-08-26')` is midnight UTC and renders
as the 25th for anyone west of Greenwich — an attendance register that shifts by a day
depending on where you read it.

`Instant` values are moments, so `new Date` is correct on them, and `formatDateTime`,
`formatDuration` and the elapsed-time helpers use it. The `datetime-local` input on the
notice form goes the other way: `new Date('2026-09-01T18:00')` parses in the browser's own
timezone, which is exactly what a user typing "6pm" means.

### Server is the authority; the UI is a convenience

Buttons for illegal state transitions are hidden — no "resolve" on an already-resolved
complaint. That is courtesy, not enforcement. The server still returns
`ILLEGAL_STATE_TRANSITION`, `RESOLUTION_NOTE_REQUIRED`, `FEE_ALREADY_SETTLED`,
`ALERT_ALREADY_ACKNOWLEDGED`, `DUPLICATE_APPLICATION` and `PAYMENT_VERIFICATION_FAILED`,
and every one of those is displayed if it arrives — because two tabs, two wardens, or a
page left open for an hour all produce them legitimately.

`ApiError` carries `status`, `code`, `fieldErrors()` and the `traceId`, and `ErrorNotice`
renders all of it. The trace id is shown on purpose: it is the string that makes a support
conversation short.

---

## The payment flow

`/student/fees/[id]` is the one screen that spends money, and two decisions there are worth
reading before changing it.

**The idempotency key is minted once per attempt, not once per click.** It is generated when
the pay panel is opened, kept in state, and re-sent verbatim on every retry of that attempt.
Minting a fresh UUID inside the click handler would satisfy the header's type and defeat its
purpose — two clicks would become two orders. Editing the amount is a genuinely new attempt,
so that mints a new key. The response's `alreadyInitiated` flag is surfaced rather than
hidden, because it is the visible proof the mechanism worked.

**The browser can complete a *mock* payment, and only in development.** The callback carries
`HMAC_SHA256(secret, orderId + "|" + paymentId)` in lower-case hex — Razorpay's scheme, which
the mock gateway implements identically. In production that signature comes from the gateway;
the browser never holds the secret. There is deliberately no endpoint that hands a signature
out, since an endpoint that mints valid signatures on request is an endpoint that marks any
invoice paid.

So the page computes the mock HMAC itself, behind `NEXT_PUBLIC_MOCK_GATEWAY_SECRET`. Unset —
the default — the completion step does not render and the page explains why. Set in
`.env.local` against a development backend, the full path runs end to end. Never set it in a
deployed build: `NEXT_PUBLIC_*` is inlined into the bundle and public to every visitor.

---

## Routes

| Route | |
| --- | --- |
| `/login` | Sign in; redirects to the role's home |
| **Warden** | |
| `/warden` | Dashboard: occupancy, arrears, open complaints, alerts |
| `/warden/students`, `/warden/students/[id]` | Roster and one student's full record |
| `/warden/rooms` | Room inventory and capacity |
| `/warden/occupancy` | Occupancy by block and floor |
| `/warden/allocations` | Assign, move and release beds |
| `/warden/applications` | Approve or reject, with a mandatory reason on reject |
| `/warden/attendance` | Take and correct the register |
| `/warden/absence-alerts` | Acknowledge alerts |
| `/warden/fees`, `/warden/fees/[id]` | Raise invoices, record offline payments, cancel |
| `/warden/payments` | Gateway payments, including failures |
| `/warden/complaints`, `/warden/complaints/[id]` | Queue, analytics, resolution |
| `/warden/notices` | Post and retire notices |
| **Student** | |
| `/student` | Overview: room, arrears, attendance, alerts |
| `/student/application` | Apply for a place; application history |
| `/student/profile` | Record, roommates, the three editable contact fields |
| `/student/attendance` | Own register and summary for a date range |
| `/student/alerts` | Absence alerts raised against them, read-only |
| `/student/fees`, `/student/fees/[id]` | Ledger, payment history, and the pay flow |
| `/student/complaints` | File and follow complaints |
| `/student/notices` | Notices matching them |
| **Admin** | |
| `/admin/jobs` | Run fee reminders and the absence scan for a business date |
| `/admin/audit` | Audit trail by entity, by actor, or all |

Guards live in `src/lib/auth.tsx` and are applied in each section's `layout.tsx`. They are
navigation, not security: the server authorises every request independently, and a warden
who reaches a student URL gets a 403 from Spring, not just a redirect from here.

---

## Known limitations

- **No tests.** There is no test runner configured for this app. The typecheck is strict
  (`strict`, `noUnusedLocals`, `noUnusedParameters`) and `npm run build` fails on a type
  error, which catches contract drift against `types.ts` but nothing about behaviour.
- **No caching between navigations**, by design — see above.
- **The backend's integration tests need Docker** (Testcontainers) and have not been run on
  the machine this was built on. Nothing here reports them as passing.
