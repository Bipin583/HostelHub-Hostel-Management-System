'use client';

/**
 * A student's own attendance: the percentage, then the days behind it.
 *
 * <p><b>The summary and the register are two requests, not one derived from the other.</b>
 * `presentPercentage` comes from the server's summary endpoint rather than being counted
 * from the rows on screen, because the rows are a window and the percentage is a fact
 * about that window -- computing it here would mean recomputing it identically to the
 * backend forever, and quietly disagreeing the day either changes.
 *
 * <p><b>Only marked days count.</b> `markedDays` is not the number of days in the range;
 * a register not taken is not an absence. Dividing present days by calendar days would
 * make a hostel that skips roll call on Sundays look like one where everybody vanishes
 * every Sunday, which is why the denominator on screen is always `markedDays`.
 *
 * <p>The range is two date inputs defaulting to the last 30 days. Both are `LocalDate`
 * strings end to end -- typed as dates, sent as dates, formatted by splitting the string
 * -- so no timezone ever touches them. `daysBetween` guards the inverted range before a
 * request is made.
 */

import { useState } from 'react';
import { student } from '@/lib/endpoints';
import { useCurrentUser } from '@/lib/auth';
import { useQuery } from '@/lib/use-query';
import {
  daysBetween,
  formatDate,
  formatDateTime,
  formatDayMonth,
  formatPercent,
  plural,
  shiftIsoDate,
  todayIso,
} from '@/lib/format';
import {
  Card,
  DataState,
  EmptyState,
  Notice,
  PageHead,
  StatCard,
  TableWrap,
  TextField,
} from '@/components/ui';
import { AttendanceStatusBadge } from '@/components/status-badges';

export default function StudentAttendancePage() {
  const user = useCurrentUser();
  const studentId = user?.studentId ?? null;

  const [to, setTo] = useState(todayIso());
  const [from, setFrom] = useState(shiftIsoDate(todayIso(), -29));

  const span = daysBetween(from, to);
  const rangeValid = span !== null && span > 0;
  // Unwrapped to a plain number rather than leaning on `rangeValid` to narrow `span`:
  // the narrowing would have to survive into a render callback, and a nullable read
  // that only type-checks because of an aliased condition is a fragile thing to build
  // a denominator on. Zero is never rendered -- the guard above shows a notice instead.
  const spanDays = span ?? 0;
  const enabled = studentId !== null && rangeValid;
  const range = { from, to };

  const summary = useQuery(
    (signal) => student.attendance.summary(studentId ?? 0, range, signal),
    [studentId, from, to],
    { enabled },
  );
  const records = useQuery(
    (signal) => student.attendance.list(studentId ?? 0, range, signal),
    [studentId, from, to],
    { enabled },
  );

  return (
    <>
      <PageHead title="Attendance" subtitle="Your record for a range of dates" />

      <Card>
        <div className="filters">
          <TextField
            label="From"
            type="date"
            max={to}
            value={from}
            onChange={(event) => setFrom(event.target.value)}
          />
          <TextField
            label="To"
            type="date"
            min={from}
            max={todayIso()}
            value={to}
            onChange={(event) => setTo(event.target.value)}
          />
        </div>
      </Card>

      {studentId === null ? (
        <Notice tone="warning" title="This account has no student record">
          Attendance is kept against a student profile. Ask the hostel office to link your account.
        </Notice>
      ) : !rangeValid ? (
        <Notice tone="error" title="That range does not work">
          The &ldquo;to&rdquo; date has to fall on or after the &ldquo;from&rdquo; date.
        </Notice>
      ) : (
        <>
          <DataState query={summary} skeletonRows={2} errorTitle="Could not load your summary">
            {(data) => (
              <div className="stat-grid">
                <StatCard
                  label="Present"
                  value={formatPercent(data.presentPercentage, 1)}
                  note={
                    data.markedDays === 0
                      ? 'No register taken in this range'
                      : `${data.presentDays} of ${data.markedDays} marked days`
                  }
                />
                <StatCard
                  label="Days marked"
                  value={data.markedDays}
                  note={`Out of ${plural(spanDays, 'day')} in range`}
                />
                <StatCard label="Present days" value={data.presentDays} />
                <StatCard
                  label="Absent days"
                  value={data.absentDays}
                  note={data.absentDays === 0 ? 'Clean record' : 'Three in a row raises an alert'}
                />
              </div>
            )}
          </DataState>

          <Card flush title="The register" subtitle="Newest first; days with no roll call are absent from the list">
            <DataState query={records} skeletonRows={8} errorTitle="Could not load the register">
              {(rows) =>
                rows.length === 0 ? (
                  <EmptyState title="Nothing marked">
                    No register was taken for you in this range.
                  </EmptyState>
                ) : (
                  <TableWrap>
                    <thead>
                      <tr>
                        <th>Date</th>
                        <th className="shrink">Status</th>
                        <th>Marked by</th>
                        <th>Marked at</th>
                      </tr>
                    </thead>
                    <tbody>
                      {/* Newest first, sorted on a copy. A student scanning their own
                          record starts from today; the endpoint returns ascending,
                          which is right for a chart and wrong for reading. */}
                      {[...rows]
                        .sort((left, right) =>
                          right.attendanceDate.localeCompare(left.attendanceDate),
                        )
                        .map((record) => (
                          <tr key={record.id}>
                            <td className="nowrap">
                              {formatDate(record.attendanceDate)}
                              <span className="cell-sub">
                                {formatDayMonth(record.attendanceDate)}
                              </span>
                            </td>
                            <td className="shrink">
                              <AttendanceStatusBadge status={record.status} />
                            </td>
                            <td className="small">{record.markedByName ?? '--'}</td>
                            <td className="nowrap small">{formatDateTime(record.markedAt)}</td>
                          </tr>
                        ))}
                    </tbody>
                  </TableWrap>
                )
              }
            </DataState>
          </Card>
        </>
      )}
    </>
  );
}
