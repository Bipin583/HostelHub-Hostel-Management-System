'use client';

/**
 * The warden's landing page: four counters, a fortnight of attendance, and the two
 * queues that need working.
 *
 * <p>The counters come from `GET /warden/dashboard`, which is one query per figure on
 * the server rather than four round trips from here -- a dashboard that fires a
 * request per tile is the classic way to make a landing page the slowest screen in an
 * app.
 *
 * <p>The three cards below it are separate requests on purpose. Each one is a slice of
 * a paged endpoint (`size: 5`), so the dashboard shows the head of a queue and links
 * to the whole thing rather than duplicating a table. They also fail independently:
 * if the alerts endpoint is down, the applications card still renders, because each
 * has its own `DataState`.
 */

import Link from 'next/link';
import { useMemo } from 'react';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import {
  formatDate,
  formatDayMonth,
  formatFraction,
  formatMoney,
  plural,
  shiftIsoDate,
  todayIso,
} from '@/lib/format';
import {
  Avatar,
  Card,
  DataState,
  EmptyState,
  Meter,
  PageHead,
  StatCard,
  TableWrap,
} from '@/components/ui';
import {
  IconAlert,
  IconCalendar,
  IconChart,
  IconCheckCircle,
  IconClipboard,
  IconMegaphone,
  IconMessage,
  IconReceipt,
  IconWallet,
} from '@/components/icons';
import { AlertStateBadge, ApplicationStatusBadge } from '@/components/status-badges';
import type { AttendanceTrend } from '@/lib/types';

export default function WardenDashboardPage() {
  const dashboard = useQuery((signal) => warden.dashboard(signal));
  const collections = useQuery((signal) => warden.fees.collections(signal));

  // A fortnight, ending today. Memoised so the object identity is stable -- useQuery
  // serialises its deps, so this is about not recomputing the strings, not about
  // preventing a refetch loop.
  const range = useMemo(() => {
    const to = todayIso();
    return { from: shiftIsoDate(to, -13), to };
  }, []);

  const trend = useQuery((signal) => warden.attendance.trend(range, signal), [range]);
  const alerts = useQuery((signal) => warden.absenceAlerts.open({ size: 5 }, signal));
  const applications = useQuery((signal) => warden.applications.pending({ size: 5 }, signal));

  return (
    <>
      <PageHead title="Dashboard" subtitle={`Today is ${formatDate(todayIso())}`} />

      <DataState query={dashboard} skeletonRows={2} errorTitle="Could not load the dashboard">
        {(data) => (
          <div className="stat-grid">
            <StatCard
              label="Unpaid fees"
              value={data.unpaidFees}
              href="/warden/fees"
              note="Invoices outstanding"
              icon={IconReceipt}
            />
            <StatCard
              label="Open complaints"
              value={data.openComplaints}
              href="/warden/complaints"
              note="Not yet resolved"
              icon={IconMessage}
            />
            <StatCard
              label="Absence alerts"
              value={data.openAbsenceAlerts}
              href="/warden/absence-alerts"
              note="Awaiting acknowledgement"
              icon={IconAlert}
            />
            <StatCard
              label="Notices posted"
              value={data.noticesPosted}
              href="/warden/notices"
              note="By you"
              icon={IconMegaphone}
            />
          </div>
        )}
      </DataState>

      <Card
        title="Attendance, last 14 days"
        subtitle="Present against absent, per marked day"
        icon={IconChart}
        actions={<Link href="/warden/attendance">Open register</Link>}
      >
        <DataState query={trend} skeletonRows={3} errorTitle="Could not load the trend">
          {(data) => <TrendBars trend={data} />}
        </DataState>
      </Card>

      <div className="grid-2">
        <Card
          title="Fee collection"
          subtitle="Across every term"
          icon={IconWallet}
          actions={<Link href="/warden/fees">All fees</Link>}
        >
          <DataState query={collections} skeletonRows={3}>
            {(data) => (
              <div className="stack">
                <div className="stack-sm">
                  <div className="spread">
                    <span className="muted small">Billed</span>
                    <span className="nums">{formatMoney(data.totals.billedPaise)}</span>
                  </div>
                  <div className="spread">
                    <span className="muted small">Collected</span>
                    <span className="nums">{formatMoney(data.totals.collectedPaise)}</span>
                  </div>
                  <div className="spread">
                    <span className="muted small">Outstanding</span>
                    {/* The one figure a warden acts on, so it is the one marked up as
                        important rather than merely styled that way. */}
                    <strong className="nums">{formatMoney(data.totals.outstandingPaise)}</strong>
                  </div>
                </div>
                <div className="stack-sm">
                  <Meter
                    value={data.totals.collectionRate * 100}
                    tone={meterTone(data.totals.collectionRate)}
                    label={`${formatFraction(data.totals.collectionRate, 1)} of billed fees collected`}
                  />
                  <span className="small faint">
                    {formatFraction(data.totals.collectionRate, 1)} collected ·{' '}
                    {plural(data.totals.overdueCount, 'overdue invoice')}
                  </span>
                </div>
              </div>
            )}
          </DataState>
        </Card>

        <Card
          title="Absence alerts"
          subtitle="Longest first"
          icon={IconAlert}
          actions={<Link href="/warden/absence-alerts">All alerts</Link>}
        >
          <DataState query={alerts} skeletonRows={4}>
            {(page) =>
              page.content.length === 0 ? (
                <EmptyState title="Nothing open" icon={IconCheckCircle}>
                  No student is currently past the absence threshold.
                </EmptyState>
              ) : (
                <ul className="card-list">
                  {page.content.map((alert) => (
                    <li key={alert.id}>
                      <Avatar name={alert.student.fullName} size="sm" />
                      <span className="card-list-main">
                        <Link href={`/warden/students/${alert.student.id}`} className="cell-strong">
                          {alert.student.fullName}
                        </Link>
                        <span className="cell-sub">
                          {plural(alert.consecutiveDays, 'day')} from{' '}
                          {formatDate(alert.streakStartDate)}
                        </span>
                      </span>
                      <AlertStateBadge acknowledged={alert.acknowledged} />
                    </li>
                  ))}
                </ul>
              )
            }
          </DataState>
        </Card>
      </div>

      <Card
        title="Pending applications"
        subtitle="Oldest first"
        flush
        icon={IconClipboard}
        actions={<Link href="/warden/applications">All applications</Link>}
      >
        <DataState query={applications} skeletonRows={4}>
          {(page) =>
            page.content.length === 0 ? (
              <EmptyState title="No applications waiting" icon={IconCheckCircle}>
                Every request has been decided.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Roll number</th>
                    <th>Applied</th>
                    <th className="shrink">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {page.content.map((application) => (
                    <tr key={application.id}>
                      <td>
                        <span className="row-tight">
                          <Avatar name={application.fullName} size="sm" />
                          <Link href="/warden/applications" className="cell-strong">
                            {application.fullName}
                          </Link>
                        </span>
                      </td>
                      <td className="mono">{application.rollNumber}</td>
                      <td className="nowrap">{formatDate(application.appliedAt.slice(0, 10))}</td>
                      <td className="shrink">
                        <ApplicationStatusBadge status={application.status} />
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

/**
 * Fourteen stacked columns, drawn with divs.
 *
 * <p>Scaled to the busiest day rather than to a fixed ceiling, so a hostel of forty
 * and a hostel of four hundred both produce a readable shape. Days with nothing marked
 * render as an empty track -- which is information, not a gap: it means nobody took
 * the register, and that is exactly the state a warden should notice.
 */
function TrendBars({ trend }: { trend: AttendanceTrend }) {
  const busiest = Math.max(1, ...trend.days.map((day) => day.marked));
  if (trend.days.length === 0) {
    return (
      <EmptyState title="No attendance in this window" icon={IconCalendar}>
        Mark a register to see the trend.
      </EmptyState>
    );
  }
  return (
    <div>
      <div className="bars">
        {trend.days.map((day) => (
          <div
            key={day.date}
            className="bar-col"
            // The native tooltip is deliberate: it is one attribute, it works with
            // keyboard focus on touch, and a custom popover for a hover detail on a
            // sparkline is a component nobody needs to review.
            title={`${formatDate(day.date)} · ${day.present} present, ${day.absent} absent`}
          >
            <div className="bar-absent" style={{ height: `${(day.absent / busiest) * 100}%` }} />
            <div className="bar-present" style={{ height: `${(day.present / busiest) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="bar-axis">
        <span>{formatDayMonth(trend.days[0]?.date)}</span>
        <span>{formatDayMonth(trend.days[trend.days.length - 1]?.date)}</span>
      </div>
    </div>
  );
}

/**
 * Red under half collected, amber under four fifths, brand above.
 *
 * <p>The thresholds live here rather than in `Meter` because they are specific to fee
 * collection: 45% of beds occupied is a quiet term, and 45% of fees collected is a
 * problem. The component draws a track; deciding what a number means is this page's job.
 */
function meterTone(rate: number): 'default' | 'warning' | 'danger' {
  if (rate < 0.5) return 'danger';
  if (rate < 0.8) return 'warning';
  return 'default';
}
