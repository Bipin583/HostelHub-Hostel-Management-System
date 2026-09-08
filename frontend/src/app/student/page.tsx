'use client';

/**
 * The student's landing page: where I live, what I owe, what needs attention.
 *
 * <p>The warden dashboard gets four counters from one endpoint. This one cannot:
 * `GET /student/dashboard` returns a single figure (unresolved complaints), and the
 * rest of what a student needs is spread across their record, their fees and their
 * alerts. So this page fans out -- five small requests, each with its own `DataState`
 * -- rather than an aggregate endpoint invented to serve one screen. That is a real
 * trade: five requests are slower than one, but each of these is already the authority
 * for its slice, and a second source for the same numbers is a second thing to be
 * wrong.
 *
 * <p><b>Money is summed here, in paise.</b> `GET /student/fees` returns the whole list
 * rather than a page -- a student has a handful of invoices, not thousands -- so the
 * outstanding total is a `reduce` over integers. Cancelled invoices are excluded,
 * because a cancelled bill is not owed and counting it would tell a student they owe
 * money they do not.
 */

import Link from 'next/link';
import { useMemo } from 'react';
import { student } from '@/lib/endpoints';
import { useCurrentUser } from '@/lib/auth';
import { useQuery } from '@/lib/use-query';
import {
  formatDate,
  formatDateTime,
  formatMoney,
  formatPercent,
  hostelLabel,
  plural,
  shiftIsoDate,
  todayIso,
  yearLabel,
} from '@/lib/format';
import {
  Badge,
  Card,
  DataState,
  EmptyState,
  Notice,
  PageHead,
  StatCard,
  TableWrap,
} from '@/components/ui';
import {
  IconAlert,
  IconBed,
  IconCalendar,
  IconCheckCircle,
  IconMegaphone,
  IconMessage,
  IconReceipt,
  IconWallet,
} from '@/components/icons';
import { AllocationStatusBadge, FeeStatusBadge } from '@/components/status-badges';

export default function StudentOverviewPage() {
  const user = useCurrentUser();
  const studentId = user?.studentId ?? null;

  const range = useMemo(() => {
    const to = todayIso();
    return { from: shiftIsoDate(to, -29), to };
  }, []);

  const me = useQuery((signal) => student.me.get(signal));
  const fees = useQuery((signal) => student.fees.list(signal));
  const dashboard = useQuery((signal) => student.dashboard(signal));
  // These two take the student id in the path and then check it is the caller's own.
  // Passing it from the session is not the security decision -- the server makes that
  // -- it is just the shape of the route. See the note in endpoints.ts.
  const attendance = useQuery(
    (signal) => student.attendance.summary(studentId ?? 0, range, signal),
    [studentId, range],
    { enabled: studentId !== null },
  );
  const alerts = useQuery(
    (signal) => student.absenceAlerts.list(studentId ?? 0, signal),
    [studentId],
    { enabled: studentId !== null },
  );
  const notices = useQuery((signal) => student.notices.list({ size: 5 }, signal));

  const owedFees = (fees.data ?? []).filter(
    (fee) => fee.status !== 'CANCELLED' && fee.outstandingPaise > 0,
  );
  const outstandingPaise = owedFees.reduce((total, fee) => total + fee.outstandingPaise, 0);
  const overdueCount = owedFees.filter((fee) => fee.overdue).length;
  const openAlerts = (alerts.data ?? []).filter((alert) => !alert.acknowledged).length;

  const firstName = user?.fullName?.split(' ')[0] ?? 'there';

  return (
    <>
      <PageHead title={`Hello, ${firstName}`} subtitle={`Today is ${formatDate(todayIso())}`} />

      {studentId === null ? (
        <Notice tone="warning" title="This account has no student record">
          The portal needs a student profile to show a record. Ask the hostel office to link your
          account.
        </Notice>
      ) : null}

      <div className="stat-grid">
        <StatCard
          label="Outstanding"
          value={fees.data === undefined ? '--' : formatMoney(outstandingPaise)}
          href="/student/fees"
          icon={IconReceipt}
          note={
            overdueCount > 0
              ? `${plural(overdueCount, 'invoice')} past due`
              : outstandingPaise === 0
                ? 'Nothing owed'
                : `Across ${plural(owedFees.length, 'invoice')}`
          }
        />
        <StatCard
          label="Attendance, 30 days"
          value={
            attendance.data === undefined ? '--' : formatPercent(attendance.data.presentPercentage, 1)
          }
          href="/student/attendance"
          icon={IconCalendar}
          note={
            attendance.data === undefined
              ? undefined
              : attendance.data.markedDays === 0
                ? 'No register taken yet'
                : `${attendance.data.presentDays} of ${attendance.data.markedDays} marked days`
          }
        />
        <StatCard
          label="Absence alerts"
          value={alerts.data === undefined ? '--' : openAlerts}
          href="/student/alerts"
          icon={IconAlert}
          note={openAlerts === 0 ? 'None open' : 'Your warden has been notified'}
        />
        <StatCard
          label="Open complaints"
          value={dashboard.data === undefined ? '--' : dashboard.data.unresolvedComplaints}
          href="/student/complaints"
          icon={IconMessage}
          note="Filed by you, not yet resolved"
        />
      </div>

      <div className="grid-2">
        <Card
          title="My room"
          icon={IconBed}
          actions={<Link href="/student/application">Application</Link>}
        >
          <DataState query={me} skeletonRows={4} errorTitle="Could not load your record">
            {(detail) =>
              detail.currentRoom === null ? (
                <div className="stack-sm">
                  <AllocationStatusBadge status={detail.allocationStatus} />
                  {/* Three allocation states need three different sentences: one is an
                      instruction, one is a wait, one is neither. Collapsing them into
                      "no room" leaves a student who has already applied wondering
                      whether to apply again. */}
                  <p className="small muted">
                    {detail.allocationStatus === 'NOT_APPLIED'
                      ? 'You have not applied for a hostel place yet.'
                      : detail.allocationStatus === 'PENDING'
                        ? 'Your application is with the warden. A room is assigned after it is approved.'
                        : 'Your application is approved and a room has not been assigned yet.'}
                  </p>
                </div>
              ) : (
                <dl className="facts">
                  <dt className="fact-label">Room</dt>
                  <dd className="fact-value mono">{detail.currentRoom.roomName}</dd>
                  <dt className="fact-label">Hostel</dt>
                  <dd className="fact-value">{hostelLabel(detail.currentRoom.hostelType)}</dd>
                  <dt className="fact-label">Block</dt>
                  <dd className="fact-value">{detail.currentRoom.block ?? '--'}</dd>
                  <dt className="fact-label">Floor</dt>
                  <dd className="fact-value nums">{detail.currentRoom.floor ?? '--'}</dd>
                  <dt className="fact-label">Year</dt>
                  <dd className="fact-value">{yearLabel(detail.yearOfStudy)}</dd>
                  <dt className="fact-label">Roll number</dt>
                  <dd className="fact-value mono">{detail.rollNumber}</dd>
                </dl>
              )
            }
          </DataState>
        </Card>

        <Card
          title="Notices"
          icon={IconMegaphone}
          actions={<Link href="/student/notices">All notices</Link>}
        >
          <DataState query={notices} skeletonRows={4} errorTitle="Could not load notices">
            {(page) =>
              page.content.length === 0 ? (
                <EmptyState title="Nothing posted" icon={IconMegaphone}>
                  Notices for your hostel and year appear here.
                </EmptyState>
              ) : (
                <ul className="card-list">
                  {page.content.map((notice) => (
                    <li key={notice.id}>
                      <span className="card-list-main">
                        <Link href="/student/notices" className="cell-strong">
                          {notice.title}
                        </Link>
                        <span className="cell-sub">{formatDateTime(notice.publishedAt)}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )
            }
          </DataState>
        </Card>
      </div>

      <Card
        flush
        title="What I owe"
        subtitle="Only invoices with something still outstanding"
        icon={IconWallet}
        actions={<Link href="/student/fees">All fees</Link>}
      >
        <DataState query={fees} skeletonRows={4} errorTitle="Could not load your fees">
          {() =>
            owedFees.length === 0 ? (
              <EmptyState title="Nothing outstanding" icon={IconCheckCircle}>
                Every invoice raised against you is settled.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Invoice</th>
                    <th>Term</th>
                    <th className="num">Outstanding</th>
                    <th>Due</th>
                    <th className="shrink">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {/* Soonest due first: the order a student acts on. The endpoint's own
                      order is by term, which is right for a ledger and wrong for a
                      to-do list. Sorted on a copy -- `sort` mutates, and the array
                      here is derived from query state. */}
                  {[...owedFees]
                    .sort((left, right) => left.dueDate.localeCompare(right.dueDate))
                    .map((fee) => (
                      <tr key={fee.id}>
                        <td>
                          <Link href={`/student/fees/${fee.id}`} className="cell-strong">
                            {fee.title}
                          </Link>
                        </td>
                        <td className="small nowrap">
                          {fee.academicYear}
                          <span className="cell-sub">{fee.semester}</span>
                        </td>
                        <td className="num nums">{formatMoney(fee.outstandingPaise)}</td>
                        <td className="nowrap">
                          {formatDate(fee.dueDate)}
                          {fee.overdue ? (
                            <span className="cell-sub">
                              <Badge tone="danger">Past due</Badge>
                            </span>
                          ) : null}
                        </td>
                        <td className="shrink">
                          <FeeStatusBadge status={fee.status} overdue={fee.overdue} />
                        </td>
                      </tr>
                    ))}
                </tbody>
              </TableWrap>
            )
          }
        </DataState>
      </Card>
    </>
  );
}
