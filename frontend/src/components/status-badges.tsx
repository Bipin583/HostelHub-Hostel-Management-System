'use client';

/**
 * Enum-to-colour mappings, in one file.
 *
 * <p>Every status in this system appears on several pages -- a fee's status is on the
 * fees table, the fee detail, and the student's own list -- and a status that is
 * amber in one place and grey in another teaches the user nothing. Centralising the
 * mapping is what makes the colour itself informative.
 *
 * <p>The mappings are exhaustive `Record`s rather than `switch` statements with a
 * default. If the backend adds a `FeeStatus`, the compiler fails here instead of the
 * UI quietly rendering a new state in neutral grey -- which is the failure mode that
 * lets an unhandled status ship.
 *
 * <p>One editorial decision worth stating: `PARTIALLY_PAID` is a warning, not a
 * success. Money has arrived, but the invoice is still owed, and colouring it green
 * would tell a warden reading down the column that the row needs no attention.
 */

import { Badge, type BadgeTone } from './ui';
import { humanise } from '@/lib/format';
import type {
  AllocationStatus,
  ApplicationStatus,
  AttendanceStatus,
  ComplaintStatus,
  ComplaintUrgency,
  FeeStatus,
  PaymentStatus,
} from '@/lib/types';

const FEE_TONE: Record<FeeStatus, BadgeTone> = {
  UNPAID: 'neutral',
  PARTIALLY_PAID: 'warning',
  PAID: 'success',
  CANCELLED: 'neutral',
};

export function FeeStatusBadge({ status, overdue }: { status: FeeStatus; overdue?: boolean }) {
  // Overdue outranks the status: an unpaid invoice past its due date is the row a
  // warden is looking for, and "Unpaid" alone does not say that.
  if (overdue && status !== 'PAID' && status !== 'CANCELLED') {
    return <Badge tone="danger">Overdue</Badge>;
  }
  return <Badge tone={FEE_TONE[status]}>{humanise(status)}</Badge>;
}

const PAYMENT_TONE: Record<PaymentStatus, BadgeTone> = {
  PENDING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
};

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={PAYMENT_TONE[status]}>{humanise(status)}</Badge>;
}

const COMPLAINT_TONE: Record<ComplaintStatus, BadgeTone> = {
  OPEN: 'danger',
  IN_PROGRESS: 'warning',
  RESOLVED: 'success',
};

export function ComplaintStatusBadge({ status }: { status: ComplaintStatus }) {
  return <Badge tone={COMPLAINT_TONE[status]}>{humanise(status)}</Badge>;
}

const APPLICATION_TONE: Record<ApplicationStatus, BadgeTone> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
};

export function ApplicationStatusBadge({ status }: { status: ApplicationStatus }) {
  return <Badge tone={APPLICATION_TONE[status]}>{humanise(status)}</Badge>;
}

const ALLOCATION_TONE: Record<AllocationStatus, BadgeTone> = {
  NOT_APPLIED: 'neutral',
  PENDING: 'warning',
  ALLOCATED: 'success',
};

export function AllocationStatusBadge({ status }: { status: AllocationStatus }) {
  return <Badge tone={ALLOCATION_TONE[status]}>{humanise(status)}</Badge>;
}

export function AttendanceStatusBadge({ status }: { status: AttendanceStatus | null }) {
  // Null is "not marked yet", which is a third state the enum does not have and the
  // register very much does -- an unmarked day is neither an absence nor a return.
  if (status === null) return <Badge tone="neutral">Not marked</Badge>;
  return <Badge tone={status === 'PRESENT' ? 'success' : 'danger'}>{humanise(status)}</Badge>;
}

const URGENCY_CLASS: Record<ComplaintUrgency, string> = {
  LOW: 'dot dot-low',
  MEDIUM: 'dot dot-medium',
  HIGH: 'dot dot-high',
  CRITICAL: 'dot dot-critical',
};

/**
 * Urgency as a coloured dot plus its word.
 *
 * <p>The word is not decorative -- colour alone is unreadable for a colour-blind
 * user and invisible to a screen reader. The dot is `aria-hidden` for the same
 * reason: it would otherwise be announced as a meaningless element before the label
 * that already says the same thing.
 */
export function UrgencyMark({ urgency }: { urgency: ComplaintUrgency }) {
  return (
    <span className="row-tight nowrap">
      <span className={URGENCY_CLASS[urgency]} aria-hidden="true" />
      <span className="small">{humanise(urgency)}</span>
    </span>
  );
}

/** An absence alert is either being worked (acknowledged) or is not. */
export function AlertStateBadge({ acknowledged }: { acknowledged: boolean }) {
  return acknowledged ? <Badge tone="success">Acknowledged</Badge> : <Badge tone="danger">Open</Badge>;
}
