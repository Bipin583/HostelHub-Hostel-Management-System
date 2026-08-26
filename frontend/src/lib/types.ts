/**
 * The API's vocabulary, transcribed from the backend's records and enums.
 *
 * <p>Hand-written rather than generated from `/v3/api-docs`, for one reason: a
 * generated client is only as current as the last time somebody remembered to
 * regenerate it, and a stale generated file looks exactly like a fresh one. These
 * are small enough to read in one sitting, and every field here corresponds to a
 * record component in `com.hostelops.dto` that a reviewer can check by name.
 *
 * The naming follows the backend, including `...Paise` on every money field. That
 * suffix is the point: amounts cross the wire as integer paise and are only ever
 * turned into rupees by `formatMoney`, so no arithmetic in this app touches a
 * floating-point rupee. See src/lib/format.ts.
 */

// ---------------------------------------------------------------- enumerations

export type Role = 'ADMIN' | 'WARDEN' | 'STUDENT';

/** What a warden governs. LH is the ladies' hostel; MH covers both BH and MH rooms. */
export type HostelScope = 'LH' | 'MH';

export type HostelType = 'LH' | 'BH' | 'MH';
export type Gender = 'M' | 'F';
export type AllocationStatus = 'NOT_APPLIED' | 'PENDING' | 'ALLOCATED';
export type ApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type AttendanceStatus = 'PRESENT' | 'ABSENT';
export type FeeStatus = 'UNPAID' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED';
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';
export type ComplaintStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED';
export type ComplaintUrgency = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ComplaintCategory =
  | 'ELECTRICAL'
  | 'PLUMBING'
  | 'FURNITURE'
  | 'CLEANLINESS'
  | 'INTERNET'
  | 'FOOD'
  | 'SECURITY'
  | 'GENERAL';
export type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE';

export const COMPLAINT_CATEGORIES: readonly ComplaintCategory[] = [
  'ELECTRICAL',
  'PLUMBING',
  'FURNITURE',
  'CLEANLINESS',
  'INTERNET',
  'FOOD',
  'SECURITY',
  'GENERAL',
];

export const COMPLAINT_URGENCIES: readonly ComplaintUrgency[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
export const COMPLAINT_STATUSES: readonly ComplaintStatus[] = ['OPEN', 'IN_PROGRESS', 'RESOLVED'];
export const FEE_STATUSES: readonly FeeStatus[] = ['UNPAID', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'];
export const APPLICATION_STATUSES: readonly ApplicationStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];
export const ALLOCATION_STATUSES: readonly AllocationStatus[] = ['NOT_APPLIED', 'PENDING', 'ALLOCATED'];
export const HOSTEL_TYPES: readonly HostelType[] = ['LH', 'BH', 'MH'];

/** The hostels a scope may write notices for -- mirrors HostelScope.hostelTypes(). */
export const HOSTELS_IN_SCOPE: Record<HostelScope, readonly HostelType[]> = {
  LH: ['LH'],
  MH: ['BH', 'MH'],
};

// ------------------------------------------------------------------ pagination

/**
 * Spring Data's serialised `Page`.
 *
 * Only the members this app reads are declared. `pageable` and `sort` are also on
 * the wire and are deliberately ignored: page number and size are things this
 * client already knows, because it asked for them.
 */
export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  numberOfElements: number;
}

// ------------------------------------------------------------- identity, users

export interface UserSummary {
  id: number;
  username: string;
  fullName: string;
  email: string;
  role: Role;
  /** Null for admins and students; a warden always has one. */
  hostelScope: HostelScope | null;
  /** Present only when the account is a student's. */
  studentId: number | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserSummary;
}

// ----------------------------------------------------------- students, rooms

export interface StudentSummary {
  id: number;
  rollNumber: string;
  fullName: string;
  gender: Gender;
  yearOfStudy: number;
  branch: string | null;
  allocationStatus: AllocationStatus;
}

export interface RoomSummary {
  id: number;
  roomName: string;
  hostelType: HostelType;
  block: string | null;
  floor: number | null;
  capacity: number;
  eligibleYear: number | null;
  eligibleGender: Gender | null;
}

export interface StudentDetail {
  id: number;
  rollNumber: string;
  fullName: string;
  email: string;
  gender: Gender;
  yearOfStudy: number;
  branch: string | null;
  mobileNo: string | null;
  parentMobileNo: string | null;
  allocationStatus: AllocationStatus;
  currentRoom: RoomSummary | null;
}

export interface StudentProfileUpdate {
  mobileNo: string;
  parentMobileNo?: string | null;
  branch?: string | null;
}

export interface Room extends RoomSummary {
  occupiedBeds: number;
  freeBeds: number;
}

export interface Occupancy {
  hostelType: HostelType;
  block: string | null;
  roomCount: number;
  totalBeds: number;
  occupiedBeds: number;
  freeBeds: number;
  occupancyPercent: number;
}

// -------------------------------------------------- allocations, applications

export interface Allocation {
  id: number;
  student: StudentSummary;
  room: RoomSummary;
  active: boolean;
  allocatedAt: string;
  vacatedAt: string | null;
}

export interface Application {
  id: number;
  studentId: number;
  rollNumber: string;
  fullName: string;
  status: ApplicationStatus;
  appliedAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  note: string | null;
}

// ------------------------------------------------------ attendance, absences

export interface AttendanceRecord {
  id: number;
  student: StudentSummary;
  attendanceDate: string;
  status: AttendanceStatus;
  markedByName: string | null;
  markedAt: string;
}

export interface AttendanceBulkMark {
  attendanceDate: string;
  marks: { studentId: number; status: AttendanceStatus }[];
}

export interface AttendanceBulkMarkResult {
  attendanceDate: string;
  created: number;
  updated: number;
  skippedStudentIds: number[];
}

export interface AttendanceTrend {
  from: string;
  to: string;
  days: { date: string; present: number; absent: number; marked: number }[];
}

export interface StudentAttendanceSummary {
  studentId: number;
  from: string;
  to: string;
  markedDays: number;
  presentDays: number;
  absentDays: number;
  presentPercentage: number;
}

export interface AbsenceAlert {
  id: number;
  student: StudentSummary;
  streakStartDate: string;
  consecutiveDays: number;
  triggeredOn: string;
  ageDays: number;
  notifiedAt: string | null;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  acknowledged: boolean;
}

export interface AbsenceScanResult {
  scanDate: string;
  studentsExamined: number;
  raised: number;
  extended: number;
  closed: number;
}

// ------------------------------------------------------------ fees, payments

export interface Fee {
  id: number;
  student: StudentSummary;
  title: string;
  academicYear: string;
  semester: string;
  amountPaise: number;
  amountPaidPaise: number;
  outstandingPaise: number;
  dueDate: string;
  description: string | null;
  status: FeeStatus;
  overdue: boolean;
}

export interface FeeCreate {
  studentId: number;
  title: string;
  academicYear: string;
  semester: string;
  amountPaise: number;
  dueDate: string;
  description?: string | null;
}

export interface FeeCollectionTotals {
  invoiceCount: number;
  paidInvoiceCount: number;
  overdueCount: number;
  billedPaise: number;
  collectedPaise: number;
  outstandingPaise: number;
  collectionRate: number;
}

export interface FeeCollections {
  totals: FeeCollectionTotals;
  terms: (FeeCollectionTotals & { academicYear: string; semester: string })[];
}

export interface Payment {
  id: number;
  feeId: number;
  studentId: number;
  amountPaise: number;
  currency: string;
  status: PaymentStatus;
  provider: string;
  providerOrderId: string | null;
  providerPaymentId: string | null;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface PaymentInitiation {
  paymentId: number;
  feeId: number;
  amountPaise: number;
  currency: string;
  provider: string;
  providerOrderId: string;
  publicKey: string;
  /** True when this key had already opened an attempt: the same one is returned. */
  alreadyInitiated: boolean;
}

export interface PaymentCallback {
  providerOrderId: string;
  providerPaymentId: string;
  signature: string;
}

export interface FeeReminderRun {
  reminderDate: string;
  invoicesExamined: number;
  sent: number;
  skipped: number;
  failed: number;
}

export interface FeeReminderDayCount {
  reminderDate: string;
  sent: number;
}

// ---------------------------------------------------- complaints, notices

export interface Complaint {
  id: number;
  student: StudentSummary;
  title: string;
  description: string;
  category: ComplaintCategory;
  urgency: ComplaintUrgency;
  status: ComplaintStatus;
  createdAt: string;
  inProgressAt: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  resolutionNote: string | null;
}

export interface ComplaintCreate {
  title: string;
  description: string;
  category: ComplaintCategory;
  urgency: ComplaintUrgency;
}

export interface ComplaintAnalytics {
  resolution: {
    resolvedCount: number;
    avgTotalSeconds: number | null;
    avgQueueSeconds: number | null;
    avgWorkSeconds: number | null;
    p90TotalSeconds: number | null;
  };
  backlog: { openCount: number; oldestOpenSeconds: number };
  byCategory: {
    category: ComplaintCategory;
    total: number;
    byStatus: Partial<Record<ComplaintStatus, number>>;
  }[];
}

export interface Notice {
  id: number;
  title: string;
  body: string;
  audienceHostelType: HostelType | null;
  audienceGender: Gender | null;
  audienceYear: number | null;
  audienceLabel: string;
  authorName: string | null;
  publishedAt: string;
  expiresAt: string | null;
  live: boolean;
}

export interface NoticeCreate {
  title: string;
  body: string;
  audienceHostelType?: HostelType | null;
  audienceGender?: Gender | null;
  audienceYear?: number | null;
  /** ISO-8601 instant. The backend rejects one that is already past. */
  expiresAt?: string | null;
}

// ------------------------------------------------------- dashboards, audit

export interface WardenDashboard {
  unpaidFees: number;
  openComplaints: number;
  openAbsenceAlerts: number;
  noticesPosted: number;
}

export interface StudentDashboard {
  unresolvedComplaints: number;
}

export interface AuditEvent {
  id: number;
  entityType: string;
  entityId: number;
  action: AuditAction;
  actorId: number | null;
  actorName: string | null;
  payloadDiff: unknown;
  createdAt: string;
}

export interface AuditActivityCount {
  since: string;
  events: number;
}
