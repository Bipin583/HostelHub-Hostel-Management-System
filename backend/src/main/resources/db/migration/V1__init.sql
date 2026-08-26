-- ============================================================================
-- V1: Hostel Operations Platform - initial schema
--
-- Design notes that matter for review:
--
-- * One canonical name per concept. The Django predecessor carried both
--   `room_number` and `room_name` for the same column and papered over the
--   difference with a model __init__ override, so the two names silently
--   drifted. Here a room's identifier is `room_name`, everywhere, forever.
--
-- * Enumerated values are CHECK-constrained text rather than Postgres ENUM
--   types: adding a value to a PG enum inside a transaction that also uses it
--   is awkward, and CHECK constraints are trivially alterable in a migration.
--
-- * Money is stored in paise as BIGINT. Never floating point.
--
-- * Timestamps are TIMESTAMPTZ. The app runs in UTC; display-local conversion
--   is the frontend's job.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- users: authentication + coarse authorization
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    email         VARCHAR(255),
    full_name     VARCHAR(200) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    -- Wardens are scoped to a hostel group; admins and students are not.
    hostel_scope  VARCHAR(2),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'WARDEN', 'STUDENT')),
    CONSTRAINT ck_users_hostel_scope CHECK (hostel_scope IS NULL OR hostel_scope IN ('LH', 'MH')),
    -- A warden without a scope would silently see everything; a student with
    -- one would be meaningless. Both are rejected by the database.
    CONSTRAINT ck_users_scope_matches_role CHECK (
        (role = 'WARDEN' AND hostel_scope IS NOT NULL)
        OR (role <> 'WARDEN' AND hostel_scope IS NULL)
    )
);

CREATE INDEX idx_users_role ON users (role);

-- ---------------------------------------------------------------------------
-- refresh_tokens: rotated refresh tokens, stored hashed
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    -- SHA-256 of the opaque token. The token itself is never persisted, so a
    -- database leak does not hand out live sessions.
    token_hash     VARCHAR(64) NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id BIGINT,
    user_agent     VARCHAR(255),

    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_replaced_by FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- students
-- ---------------------------------------------------------------------------
CREATE TABLE students (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    roll_number       VARCHAR(30)  NOT NULL,
    gender            VARCHAR(1)   NOT NULL,
    year_of_study     INT          NOT NULL,
    branch            VARCHAR(100),
    mobile_no         VARCHAR(10),
    parent_mobile_no  VARCHAR(10),
    allocation_status VARCHAR(20)  NOT NULL DEFAULT 'NOT_APPLIED',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_students_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_students_user UNIQUE (user_id),
    CONSTRAINT uq_students_roll_number UNIQUE (roll_number),
    CONSTRAINT ck_students_gender CHECK (gender IN ('M', 'F')),
    CONSTRAINT ck_students_year CHECK (year_of_study BETWEEN 1 AND 5),
    CONSTRAINT ck_students_alloc_status CHECK (
        allocation_status IN ('NOT_APPLIED', 'PENDING', 'ALLOCATED')
    ),
    CONSTRAINT ck_students_mobile CHECK (mobile_no IS NULL OR mobile_no ~ '^[0-9]{10}$'),
    CONSTRAINT ck_students_parent_mobile CHECK (parent_mobile_no IS NULL OR parent_mobile_no ~ '^[0-9]{10}$')
);

-- students.user_id is the join used on every authenticated request.
CREATE INDEX idx_students_user ON students (user_id);
-- gender is the warden row-level scope predicate, usually with year.
CREATE INDEX idx_students_gender_year ON students (gender, year_of_study);
CREATE INDEX idx_students_gender_alloc ON students (gender, allocation_status);

-- ---------------------------------------------------------------------------
-- rooms
-- ---------------------------------------------------------------------------
CREATE TABLE rooms (
    id              BIGSERIAL PRIMARY KEY,
    room_name       VARCHAR(20) NOT NULL,
    hostel_type     VARCHAR(2)  NOT NULL,
    block           VARCHAR(20) NOT NULL,
    floor           INT         NOT NULL,
    capacity        INT         NOT NULL,
    eligible_year   INT         NOT NULL,
    eligible_gender VARCHAR(1)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_rooms_location UNIQUE (hostel_type, block, room_name),
    CONSTRAINT ck_rooms_hostel_type CHECK (hostel_type IN ('LH', 'BH', 'MH')),
    CONSTRAINT ck_rooms_capacity CHECK (capacity > 0 AND capacity <= 20),
    CONSTRAINT ck_rooms_floor CHECK (floor >= 0),
    CONSTRAINT ck_rooms_eligible_year CHECK (eligible_year BETWEEN 1 AND 5),
    CONSTRAINT ck_rooms_eligible_gender CHECK (eligible_gender IN ('M', 'F')),
    -- LH is the ladies' hostel; BH and MH are the men's hostels. Encoding the
    -- rule here means a mis-seeded room cannot create a cross-gender placement
    -- that the allocation matcher would then happily honour.
    CONSTRAINT ck_rooms_gender_matches_hostel CHECK (
        (hostel_type = 'LH' AND eligible_gender = 'F')
        OR (hostel_type IN ('BH', 'MH') AND eligible_gender = 'M')
    )
);

-- The auto-allocation matcher filters on exactly these three columns, and the
-- warden scope filter uses hostel_type. This is the index for that query.
CREATE INDEX idx_rooms_match ON rooms (hostel_type, eligible_gender, eligible_year);
CREATE INDEX idx_rooms_location_browse ON rooms (hostel_type, block, floor);

-- ---------------------------------------------------------------------------
-- applications: Not Applied -> Pending -> Allocated lifecycle, step one
-- ---------------------------------------------------------------------------
CREATE TABLE applications (
    id          BIGSERIAL PRIMARY KEY,
    student_id  BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at  TIMESTAMPTZ,
    decided_by  BIGINT,
    note        TEXT,

    CONSTRAINT fk_applications_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT fk_applications_decided_by FOREIGN KEY (decided_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_applications_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_applications_decided CHECK (
        (status = 'PENDING' AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL)
    )
);

CREATE INDEX idx_applications_student ON applications (student_id);
CREATE INDEX idx_applications_status ON applications (status, applied_at);
-- A student may re-apply after a rejection, but may not stack pending requests.
CREATE UNIQUE INDEX uq_applications_one_pending ON applications (student_id) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- allocations: step two. Occupancy is DERIVED from this table -- there is no
-- denormalised counter column anywhere, because the predecessor's counter and
-- its allocation rows disagreed in production.
-- ---------------------------------------------------------------------------
CREATE TABLE allocations (
    id            BIGSERIAL PRIMARY KEY,
    student_id    BIGINT      NOT NULL,
    room_id       BIGINT      NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    allocated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_by  BIGINT,
    vacated_at    TIMESTAMPTZ,
    vacated_by    BIGINT,

    CONSTRAINT fk_allocations_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    -- Deleting an occupied room must fail loudly, not orphan its residents.
    CONSTRAINT fk_allocations_room FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocations_allocated_by FOREIGN KEY (allocated_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_allocations_vacated_by FOREIGN KEY (vacated_by) REFERENCES users (id) ON DELETE SET NULL,
    -- An active allocation has not been vacated, and a vacated one is inactive.
    CONSTRAINT ck_allocations_vacated_consistency CHECK (
        (active AND vacated_at IS NULL) OR (NOT active AND vacated_at IS NOT NULL)
    )
);

CREATE INDEX idx_allocations_room ON allocations (room_id);
CREATE INDEX idx_allocations_student ON allocations (student_id);
-- Occupancy counting is "active rows for this room": index exactly that.
CREATE INDEX idx_allocations_room_active ON allocations (room_id) WHERE active;

-- One bed per student. Vacated history is retained, so this is a partial
-- unique index rather than a plain UNIQUE on student_id.
CREATE UNIQUE INDEX uq_allocations_active_student ON allocations (student_id) WHERE active;

-- ---------------------------------------------------------------------------
-- Room capacity guard.
--
-- HONEST SCOPE OF THIS TRIGGER: it is defence in depth, not the primary
-- concurrency control. At Postgres' default READ COMMITTED isolation two
-- concurrent inserts each run this SELECT without seeing the other's
-- uncommitted row, so both can observe capacity-1 and both commit. What makes
-- allocation race-free is the SELECT ... FOR UPDATE the service takes on the
-- rooms row before counting (see AllocationService); this trigger catches the
-- cases that never go through the service at all -- a data fix applied by hand,
-- a future batch job, an admin console.
--
-- The fully declarative alternative is a room_beds(room_id, bed_number) table
-- with a composite FK from allocations and a partial unique index per bed; that
-- makes overfilling impossible without any application-level lock. It was not
-- taken here because it complicates capacity changes on an occupied room.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enforce_room_capacity() RETURNS TRIGGER AS $$
DECLARE
    room_capacity  INT;
    active_count   INT;
BEGIN
    IF NOT NEW.active THEN
        RETURN NEW;
    END IF;

    SELECT capacity INTO room_capacity FROM rooms WHERE id = NEW.room_id;

    SELECT count(*) INTO active_count
      FROM allocations
     WHERE room_id = NEW.room_id
       AND active
       AND id <> NEW.id;

    IF active_count >= room_capacity THEN
        RAISE EXCEPTION
            'room % is at capacity (% of %)', NEW.room_id, active_count, room_capacity
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_allocations_capacity
    BEFORE INSERT OR UPDATE OF room_id, active ON allocations
    FOR EACH ROW EXECUTE FUNCTION enforce_room_capacity();

-- ---------------------------------------------------------------------------
-- attendance
-- ---------------------------------------------------------------------------
CREATE TABLE attendance (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT      NOT NULL,
    attendance_date DATE        NOT NULL,
    status          VARCHAR(10) NOT NULL,
    marked_by       BIGINT,
    marked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_attendance_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_marked_by FOREIGN KEY (marked_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_attendance_status CHECK (status IN ('PRESENT', 'ABSENT')),
    -- Marking the same student twice for one day is a bug, not a use case.
    CONSTRAINT uq_attendance_student_date UNIQUE (student_id, attendance_date)
);

-- The absence-streak scan walks one student's records backwards by date.
CREATE INDEX idx_attendance_student_date ON attendance (student_id, attendance_date DESC);
CREATE INDEX idx_attendance_date_status ON attendance (attendance_date, status);

-- ---------------------------------------------------------------------------
-- absence_alerts: a 10-working-day absence streak becomes a durable record
-- with history, not a query someone has to remember to run.
-- ---------------------------------------------------------------------------
CREATE TABLE absence_alerts (
    id                BIGSERIAL PRIMARY KEY,
    student_id        BIGINT      NOT NULL,
    streak_start_date DATE        NOT NULL,
    consecutive_days  INT         NOT NULL,
    triggered_on      DATE        NOT NULL,
    notified_at       TIMESTAMPTZ,
    acknowledged_by   BIGINT,
    acknowledged_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_absence_alerts_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT fk_absence_alerts_ack_by FOREIGN KEY (acknowledged_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_absence_alerts_days CHECK (consecutive_days > 0),
    -- One alert per streak. This is what makes the detector idempotent: a
    -- re-run on the same ongoing streak updates rather than raises a second.
    CONSTRAINT uq_absence_alert_streak UNIQUE (student_id, streak_start_date)
);

CREATE INDEX idx_absence_alerts_open ON absence_alerts (student_id) WHERE acknowledged_at IS NULL;

-- ---------------------------------------------------------------------------
-- complaints: OPEN -> IN_PROGRESS -> RESOLVED with per-transition timestamps,
-- so time-to-resolution is a subtraction rather than a guess.
-- ---------------------------------------------------------------------------
CREATE TABLE complaints (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT         NOT NULL,
    category        VARCHAR(30)  NOT NULL DEFAULT 'GENERAL',
    urgency         VARCHAR(10)  NOT NULL DEFAULT 'LOW',
    status          VARCHAR(15)  NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    in_progress_at  TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    resolved_by     BIGINT,
    resolution_note TEXT,

    CONSTRAINT fk_complaints_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT fk_complaints_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_complaints_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    CONSTRAINT ck_complaints_urgency CHECK (urgency IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- Resolution analytics depend on resolved_at being present whenever the
    -- status says RESOLVED. Enforced rather than assumed.
    CONSTRAINT ck_complaints_resolved_at CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL)
);

CREATE INDEX idx_complaints_student ON complaints (student_id, created_at DESC);
CREATE INDEX idx_complaints_status_urgency ON complaints (status, urgency);
CREATE INDEX idx_complaints_resolution_window ON complaints (resolved_at) WHERE resolved_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- hostel_fees: the invoice. Distinct from the payments made against it.
-- ---------------------------------------------------------------------------
CREATE TABLE hostel_fees (
    id                BIGSERIAL PRIMARY KEY,
    student_id        BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    academic_year     VARCHAR(20)  NOT NULL,
    semester          VARCHAR(20)  NOT NULL,
    amount_paise      BIGINT       NOT NULL,
    amount_paid_paise BIGINT       NOT NULL DEFAULT 0,
    due_date          DATE         NOT NULL,
    description       TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'UNPAID',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_hostel_fees_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT uq_hostel_fees_term UNIQUE (student_id, academic_year, semester, title),
    CONSTRAINT ck_hostel_fees_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_hostel_fees_paid CHECK (amount_paid_paise >= 0 AND amount_paid_paise <= amount_paise),
    CONSTRAINT ck_hostel_fees_status CHECK (status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'))
);

CREATE INDEX idx_hostel_fees_student ON hostel_fees (student_id, due_date);
CREATE INDEX idx_hostel_fees_outstanding ON hostel_fees (due_date) WHERE status IN ('UNPAID', 'PARTIALLY_PAID');

-- ---------------------------------------------------------------------------
-- fee_payments: one row per payment attempt, provider-agnostic.
-- ---------------------------------------------------------------------------
CREATE TABLE fee_payments (
    id                  BIGSERIAL PRIMARY KEY,
    fee_id              BIGINT      NOT NULL,
    student_id          BIGINT      NOT NULL,
    amount_paise        BIGINT      NOT NULL,
    currency            VARCHAR(3)  NOT NULL DEFAULT 'INR',
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Which gateway handled it. The column exists so a second provider can be
    -- added without a migration or a hardcoded assumption anywhere.
    provider            VARCHAR(30) NOT NULL,
    provider_order_id   VARCHAR(120),
    provider_payment_id VARCHAR(120),
    -- Supplied by the client. A retried request reuses the key and returns the
    -- original payment instead of charging twice.
    idempotency_key     VARCHAR(80) NOT NULL,
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,

    CONSTRAINT fk_fee_payments_fee FOREIGN KEY (fee_id) REFERENCES hostel_fees (id) ON DELETE CASCADE,
    CONSTRAINT fk_fee_payments_student FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT ck_fee_payments_amount CHECK (amount_paise > 0),
    CONSTRAINT ck_fee_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_fee_payments_completed CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status <> 'PENDING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT uq_fee_payments_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_fee_payments_fee ON fee_payments (fee_id, status);
CREATE INDEX idx_fee_payments_student ON fee_payments (student_id, created_at DESC);
-- A provider's payment id must not be recorded against two rows.
CREATE UNIQUE INDEX uq_fee_payments_provider_ref
    ON fee_payments (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- fee_reminders: makes the scheduled reminder job idempotent at the database
-- level. "Already sent today" is a unique-constraint fact, not a code
-- convention that a restart mid-run could get wrong.
-- ---------------------------------------------------------------------------
CREATE TABLE fee_reminders (
    id            BIGSERIAL PRIMARY KEY,
    fee_id        BIGINT      NOT NULL,
    reminder_date DATE        NOT NULL,
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    channel       VARCHAR(20) NOT NULL DEFAULT 'EMAIL',

    CONSTRAINT fk_fee_reminders_fee FOREIGN KEY (fee_id) REFERENCES hostel_fees (id) ON DELETE CASCADE,
    CONSTRAINT uq_fee_reminders_per_day UNIQUE (fee_id, reminder_date)
);

-- ---------------------------------------------------------------------------
-- notices: warden-authored, student-visible
-- ---------------------------------------------------------------------------
CREATE TABLE notices (
    id             BIGSERIAL PRIMARY KEY,
    author_id      BIGINT       NOT NULL,
    title          VARCHAR(200) NOT NULL,
    body           TEXT         NOT NULL,
    -- NULL audience column = "everyone". A warden's notice is stamped with
    -- their own scope so students only ever see notices meant for them.
    audience_hostel_type VARCHAR(2),
    audience_gender      VARCHAR(1),
    audience_year        INT,
    published_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ,

    CONSTRAINT fk_notices_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notices_audience_hostel CHECK (audience_hostel_type IS NULL OR audience_hostel_type IN ('LH', 'BH', 'MH')),
    CONSTRAINT ck_notices_audience_gender CHECK (audience_gender IS NULL OR audience_gender IN ('M', 'F')),
    CONSTRAINT ck_notices_audience_year CHECK (audience_year IS NULL OR audience_year BETWEEN 1 AND 5),
    CONSTRAINT ck_notices_expiry CHECK (expires_at IS NULL OR expires_at > published_at)
);

CREATE INDEX idx_notices_feed ON notices (published_at DESC);
CREATE INDEX idx_notices_audience ON notices (audience_gender, audience_hostel_type);

-- ---------------------------------------------------------------------------
-- audit_events: who changed what, when. Written by an AOP interceptor around
-- the service layer, not by hand at each call site.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT,
    action       VARCHAR(20) NOT NULL,
    actor_id     BIGINT,
    payload_diff JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE'))
);

CREATE INDEX idx_audit_entity ON audit_events (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_recent ON audit_events (created_at DESC);
CREATE INDEX idx_audit_actor ON audit_events (actor_id, created_at DESC);
