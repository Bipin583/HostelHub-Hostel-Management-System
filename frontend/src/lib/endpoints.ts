/**
 * Every backend route this app uses, in one file.
 *
 * <p>Pages call `warden.students.list(...)`, never `get('/api/v1/warden/students')`.
 * The indirection buys three things that matter more than the extra layer costs:
 *
 * <ul>
 *   <li><b>One place to check against the controllers.</b> A reviewer can diff this
 *       file against `com.hostelops.controller` and see the whole surface. Scattered
 *       string literals in twenty page components cannot be audited that way.</li>
 *   <li><b>The response type is attached to the route.</b> `get<Fee>()` at a call
 *       site is an unchecked claim about a URL; here the claim is made once, next to
 *       the path it belongs to.</li>
 *   <li><b>Role prefixes stay honest.</b> `SecurityConfig` gates on the literal
 *       prefixes `/api/v1/admin`, `/api/v1/warden`, `/api/v1/student`, so the same
 *       three groupings organise this file. A student page that reaches for
 *       `warden.*` is then visibly wrong while reading the import, rather than at
 *       runtime as a 403.</li>
 * </ul>
 *
 * <p>Note the deliberate duplication between `warden` and `student` for allocations,
 * attendance and alerts. Those are different endpoints on the server -- the student
 * ones enforce "this must be your own studentId" -- and collapsing them here would
 * hide the scope check that is the entire reason both exist.
 */

import { del, get, getPage, post, put, type RequestOptions } from './api';
import type {
  AbsenceAlert,
  AbsenceScanResult,
  Allocation,
  Application,
  ApplicationStatus,
  AttendanceBulkMark,
  AttendanceBulkMarkResult,
  AttendanceRecord,
  AttendanceStatus,
  AttendanceTrend,
  AuditActivityCount,
  AuditEvent,
  Complaint,
  ComplaintAnalytics,
  ComplaintCreate,
  ComplaintStatus,
  Fee,
  FeeCollections,
  FeeCreate,
  FeeReminderDayCount,
  FeeReminderRun,
  FeeStatus,
  Notice,
  NoticeCreate,
  Occupancy,
  Page,
  Payment,
  PaymentCallback,
  PaymentInitiation,
  Room,
  StudentAttendanceSummary,
  StudentDashboard,
  StudentDetail,
  StudentProfileUpdate,
  StudentSummary,
  WardenDashboard,
} from './types';

/**
 * Page/sort parameters, matching Spring Data's `Pageable` binding.
 *
 * <p>A `type` rather than an `interface`, and that is load-bearing: TypeScript grants
 * an implicit index signature to an object *type alias* but not to an interface, so
 * only this form is assignable to `QueryParams` when it is passed through to
 * `toSearchParams`. Declaring it as an interface compiles everywhere except at the
 * one call site that matters, which is a confusing error to inherit.
 */
export type PageParams = {
  page?: number;
  size?: number;
  /** `field,ASC|DESC`. Each controller declares a default, so this is usually omitted. */
  sort?: string;
};

/** A date range, as the `from`/`to` query pair the attendance endpoints take. */
export type DateRange = {
  from: string;
  to: string;
};

// =============================================================== warden scope

export const warden = {
  dashboard: (signal?: AbortSignal) => get<WardenDashboard>('/api/v1/warden/dashboard', undefined, signal),

  students: {
    /**
     * The roster, filtered.
     *
     * `query` is a free-text match on name or roll number; the other two are exact.
     * All three are optional and dropped when unset -- an empty `allocationStatus`
     * would be a 400, not "any status". See `toSearchParams`.
     */
    list: (
      params: PageParams & { yearOfStudy?: number; allocationStatus?: string; query?: string } = {},
      signal?: AbortSignal,
    ) => getPage<StudentSummary>('/api/v1/warden/students', params, signal),

    byId: (studentId: number, signal?: AbortSignal) =>
      get<StudentDetail>(`/api/v1/warden/students/${studentId}`, undefined, signal),
  },

  rooms: {
    list: (params: PageParams & { block?: string } = {}, signal?: AbortSignal) =>
      getPage<Room>('/api/v1/warden/rooms', params, signal),

    byId: (roomId: number, signal?: AbortSignal) =>
      get<Room>(`/api/v1/warden/rooms/${roomId}`, undefined, signal),

    occupants: (roomId: number, signal?: AbortSignal) =>
      get<StudentSummary[]>(`/api/v1/warden/rooms/${roomId}/occupants`, undefined, signal),

    /** Occupancy rolled up per hostel and block. A list, not a page. */
    occupancy: (signal?: AbortSignal) =>
      get<Occupancy[]>('/api/v1/warden/rooms/occupancy', undefined, signal),
  },

  allocations: {
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Allocation>('/api/v1/warden/allocations', params, signal),

    /** Pick the room yourself. 409 `ROOM_FULL` / `ROOM_NOT_ELIGIBLE` on a bad choice. */
    allocate: (studentId: number, roomId: number) =>
      post<Allocation>('/api/v1/warden/allocations', { body: { studentId, roomId } }),

    /** Let the server choose. 409 `NO_ROOM_AVAILABLE` when nothing fits. */
    autoAllocate: (studentId: number) =>
      post<Allocation>('/api/v1/warden/allocations/auto', { body: { studentId } }),

    vacate: (studentId: number) => del(`/api/v1/warden/allocations/student/${studentId}`),

    current: (studentId: number, signal?: AbortSignal) =>
      get<Allocation>(`/api/v1/warden/allocations/student/${studentId}`, undefined, signal),

    history: (studentId: number, signal?: AbortSignal) =>
      get<Allocation[]>(`/api/v1/warden/allocations/student/${studentId}/history`, undefined, signal),
  },

  applications: {
    list: (params: PageParams & { status?: ApplicationStatus } = {}, signal?: AbortSignal) =>
      getPage<Application>('/api/v1/warden/applications', params, signal),

    pending: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Application>('/api/v1/warden/applications/pending', params, signal),

    byId: (applicationId: number, signal?: AbortSignal) =>
      get<Application>(`/api/v1/warden/applications/${applicationId}`, undefined, signal),

    /** The note is optional; the endpoint accepts an absent body. */
    approve: (applicationId: number, note?: string) =>
      post<Application>(`/api/v1/warden/applications/${applicationId}/approve`, {
        body: note ? { note } : undefined,
      }),

    /** A reason is mandatory -- `@NotBlank` on the request record. */
    reject: (applicationId: number, reason: string) =>
      post<Application>(`/api/v1/warden/applications/${applicationId}/reject`, { body: { reason } }),
  },

  attendance: {
    /**
     * The marks already recorded for one date.
     *
     * <p>Only the marks -- this is a page over the attendance table, so a student
     * nobody has marked yet does not appear at all. The register *screen* therefore
     * builds its roster from `students.list` and overlays this response on top; the
     * absence of a row here is the third state ("not taken"), which the backend keeps
     * deliberately distinct from ABSENT. See `AbsenceAlertService`, section 2.
     */
    register: (date: string, params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AttendanceRecord>('/api/v1/warden/attendance/register', { ...params, date }, signal),

    /**
     * Submit the whole day at once.
     *
     * Idempotent by `uq_attendance_student_date`: a re-submit updates instead of
     * duplicating, which is why the response reports `created` and `updated`
     * separately. `skippedStudentIds` are the ones outside this warden's scope.
     */
    markAll: (body: AttendanceBulkMark) =>
      post<AttendanceBulkMarkResult>('/api/v1/warden/attendance/register', { body }),

    markOne: (studentId: number, attendanceDate: string, status: AttendanceStatus) =>
      post<AttendanceRecord>('/api/v1/warden/attendance/mark', {
        body: { studentId, attendanceDate, status },
      }),

    trend: (range: DateRange, signal?: AbortSignal) =>
      get<AttendanceTrend>('/api/v1/warden/attendance/trend', { ...range }, signal),

    forStudent: (studentId: number, range: DateRange, signal?: AbortSignal) =>
      get<AttendanceRecord[]>(`/api/v1/warden/attendance/student/${studentId}`, { ...range }, signal),

    summaryForStudent: (studentId: number, range: DateRange, signal?: AbortSignal) =>
      get<StudentAttendanceSummary>(
        `/api/v1/warden/attendance/student/${studentId}/summary`,
        { ...range },
        signal,
      ),
  },

  absenceAlerts: {
    /** Open (unacknowledged) alerts only -- the queue a warden works from. */
    open: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AbsenceAlert>('/api/v1/warden/absence-alerts', params, signal),

    all: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AbsenceAlert>('/api/v1/warden/absence-alerts/all', params, signal),

    /** 409 `ALERT_ALREADY_ACKNOWLEDGED` if someone else got there first. */
    acknowledge: (alertId: number) =>
      post<AbsenceAlert>(`/api/v1/warden/absence-alerts/${alertId}/acknowledge`),
  },

  fees: {
    list: (params: PageParams & { status?: FeeStatus } = {}, signal?: AbortSignal) =>
      getPage<Fee>('/api/v1/warden/fees', params, signal),

    /** Billed / collected / outstanding, per term and in total. */
    collections: (signal?: AbortSignal) =>
      get<FeeCollections>('/api/v1/warden/fees/collections', undefined, signal),

    byId: (feeId: number, signal?: AbortSignal) =>
      get<Fee>(`/api/v1/warden/fees/${feeId}`, undefined, signal),

    create: (body: FeeCreate) => post<Fee>('/api/v1/warden/fees', { body }),

    /** 409 `FEE_ALREADY_SETTLED` once anything has been paid against it. */
    cancel: (feeId: number) => post<Fee>(`/api/v1/warden/fees/${feeId}/cancel`),
  },

  payments: {
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Payment>('/api/v1/warden/payments', params, signal),

    forFee: (feeId: number, signal?: AbortSignal) =>
      get<Payment[]>(`/api/v1/warden/payments/fee/${feeId}`, undefined, signal),
  },

  complaints: {
    list: (params: PageParams & { status?: ComplaintStatus } = {}, signal?: AbortSignal) =>
      getPage<Complaint>('/api/v1/warden/complaints', params, signal),

    /** `windowDays` is bounded 1..366 by the controller. */
    analytics: (windowDays: number, signal?: AbortSignal) =>
      get<ComplaintAnalytics>('/api/v1/warden/complaints/analytics', { windowDays }, signal),

    byId: (complaintId: number, signal?: AbortSignal) =>
      get<Complaint>(`/api/v1/warden/complaints/${complaintId}`, undefined, signal),

    /**
     * Move a complaint along.
     *
     * The server enforces the order (OPEN to IN_PROGRESS to RESOLVED) with
     * `ILLEGAL_STATE_TRANSITION`, and requires a note to resolve
     * (`RESOLUTION_NOTE_REQUIRED`). Both are surfaced, not pre-empted: the UI hides
     * the impossible buttons, but the server stays the authority.
     */
    setStatus: (complaintId: number, status: ComplaintStatus, resolutionNote?: string) =>
      post<Complaint>(`/api/v1/warden/complaints/${complaintId}/status`, {
        body: { status, resolutionNote: resolutionNote ?? null },
      }),
  },

  notices: {
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Notice>('/api/v1/warden/notices', params, signal),

    create: (body: NoticeCreate) => post<Notice>('/api/v1/warden/notices', { body }),

    remove: (noticeId: number) => del(`/api/v1/warden/notices/${noticeId}`),
  },
} as const;

// ============================================================== student scope

export const student = {
  dashboard: (signal?: AbortSignal) => get<StudentDashboard>('/api/v1/student/dashboard', undefined, signal),

  me: {
    get: (signal?: AbortSignal) => get<StudentDetail>('/api/v1/student/me', undefined, signal),
    update: (body: StudentProfileUpdate) => put<StudentDetail>('/api/v1/student/me', { body }),
    roommates: (signal?: AbortSignal) =>
      get<StudentSummary[]>('/api/v1/student/me/roommates', undefined, signal),
  },

  applications: {
    /** No body: the server knows who is asking. 409 `DUPLICATE_APPLICATION` on a repeat. */
    apply: () => post<Application>('/api/v1/student/applications'),
    mine: (signal?: AbortSignal) => get<Application[]>('/api/v1/student/applications', undefined, signal),
  },

  allocations: {
    current: (studentId: number, signal?: AbortSignal) =>
      get<Allocation>(`/api/v1/student/allocations/${studentId}`, undefined, signal),
    history: (studentId: number, signal?: AbortSignal) =>
      get<Allocation[]>(`/api/v1/student/allocations/${studentId}/history`, undefined, signal),
  },

  attendance: {
    list: (studentId: number, range: DateRange, signal?: AbortSignal) =>
      get<AttendanceRecord[]>(`/api/v1/student/attendance/${studentId}`, { ...range }, signal),
    summary: (studentId: number, range: DateRange, signal?: AbortSignal) =>
      get<StudentAttendanceSummary>(`/api/v1/student/attendance/${studentId}/summary`, { ...range }, signal),
  },

  absenceAlerts: {
    list: (studentId: number, signal?: AbortSignal) =>
      get<AbsenceAlert[]>(`/api/v1/student/absence-alerts/${studentId}`, undefined, signal),
  },

  fees: {
    list: (signal?: AbortSignal) => get<Fee[]>('/api/v1/student/fees', undefined, signal),
    byId: (feeId: number, signal?: AbortSignal) =>
      get<Fee>(`/api/v1/student/fees/${feeId}`, undefined, signal),
  },

  payments: {
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Payment>('/api/v1/student/payments', params, signal),

    /**
     * Open a payment attempt. The `Idempotency-Key` is not optional.
     *
     * <p>It is a required parameter of this function rather than an optional header
     * because the server enforces it with `uq_fee_payments_idempotency`, and the
     * whole point of that constraint is that a double-submitted form -- or a retry
     * after a timeout the user could not see the outcome of -- reuses the existing
     * attempt instead of opening a second one against the same invoice. The response
     * says which happened via `alreadyInitiated`.
     *
     * <p>The caller must therefore keep one key for one payment *attempt* and reuse
     * it across retries. Minting a fresh UUID per click would satisfy the type and
     * defeat the constraint.
     */
    initiate: (feeId: number, amountPaise: number, idempotencyKey: string) =>
      post<PaymentInitiation>('/api/v1/student/payments', {
        body: { feeId, amountPaise },
        idempotencyKey,
      }),

    /** What the gateway would post back. Verified server-side by signature. */
    callback: (body: PaymentCallback) => post<Payment>('/api/v1/student/payments/callback', { body }),
  },

  complaints: {
    create: (body: ComplaintCreate) => post<Complaint>('/api/v1/student/complaints', { body }),
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Complaint>('/api/v1/student/complaints', params, signal),
    byId: (complaintId: number, signal?: AbortSignal) =>
      get<Complaint>(`/api/v1/student/complaints/${complaintId}`, undefined, signal),
  },

  notices: {
    /** Already filtered server-side to this student's hostel, gender and year. */
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<Notice>('/api/v1/student/notices', params, signal),
  },
} as const;

// ================================================================ admin scope

export const admin = {
  /**
   * The two scheduled jobs, exposed for manual runs.
   *
   * <p>Both are idempotent by a unique constraint rather than by a flag -- fee
   * reminders by `uq_fee_reminders_per_day`, absence alerts by
   * `uq_absence_alert_streak` -- so running one twice for the same date is safe and
   * the second run reports zero new work. That is the property the admin jobs page
   * is built to demonstrate, and the property `AbsenceScanIdempotencyIT` and
   * `FeeReminderIdempotencyIT` prove.
   */
  jobs: {
    runFeeReminders: (date: string) =>
      post<FeeReminderRun>('/api/v1/admin/jobs/fee-reminders', { query: { date } }),

    feeRemindersSentOn: (date: string, signal?: AbortSignal) =>
      get<FeeReminderDayCount>('/api/v1/admin/jobs/fee-reminders', { date }, signal),

    runAbsenceScan: (date: string) =>
      post<AbsenceScanResult>('/api/v1/admin/jobs/absence-scans', { query: { date } }),
  },

  audit: {
    list: (params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AuditEvent>('/api/v1/admin/audit', params, signal),

    forEntity: (entityType: string, entityId: number, params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AuditEvent>(`/api/v1/admin/audit/entity/${entityType}/${entityId}`, params, signal),

    forActor: (actorId: number, params: PageParams = {}, signal?: AbortSignal) =>
      getPage<AuditEvent>(`/api/v1/admin/audit/actor/${actorId}`, params, signal),

    /** `since` is an ISO instant, e.g. from `new Date(...).toISOString()`. */
    activity: (sinceIsoInstant: string, signal?: AbortSignal) =>
      get<AuditActivityCount>('/api/v1/admin/audit/activity', { since: sinceIsoInstant }, signal),
  },
} as const;

/**
 * Re-exported so a page that needs an unusual call -- a one-off header, say -- does
 * not have to import from two modules and can still be seen to be doing something
 * out of the ordinary.
 */
export type { Page, RequestOptions };
