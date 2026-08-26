'use client';

/**
 * Absence alerts raised against the student, read-only.
 *
 * <p><b>A student can see their alerts and cannot clear them.</b> Acknowledgement is a
 * warden action -- it records that a human looked and dealt with it -- so there is no
 * button here. Showing the alerts anyway matters: an absence streak triggers a message
 * to a parent or guardian, and a student finding out about that from home rather than
 * from the portal is how a system loses trust.
 *
 * <p><b>What each field means, since the shape is not obvious.</b> `streakStartDate` is
 * the first day missed; `consecutiveDays` is how long the run was when it was last
 * scanned; `triggeredOn` is the day the scan raised it; `ageDays` is how long it has sat
 * unacknowledged. An alert is not one absence -- the scan raises it at a threshold and
 * extends the same alert as the streak grows, which is why there is one row for a week
 * away rather than seven.
 *
 * <p>Sorted newest-triggered first, on a copy. Open alerts are what a student needs to
 * see, so those float above the acknowledged ones regardless of date.
 */

import { student } from '@/lib/endpoints';
import { useCurrentUser } from '@/lib/auth';
import { useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, plural } from '@/lib/format';
import {
  Card,
  DataState,
  EmptyState,
  Notice,
  PageHead,
  StatCard,
  TableWrap,
} from '@/components/ui';
import { AlertStateBadge } from '@/components/status-badges';

export default function StudentAlertsPage() {
  const user = useCurrentUser();
  const studentId = user?.studentId ?? null;

  const alerts = useQuery(
    (signal) => student.absenceAlerts.list(studentId ?? 0, signal),
    [studentId],
    { enabled: studentId !== null },
  );

  const rows = alerts.data ?? [];
  const open = rows.filter((alert) => !alert.acknowledged);
  const longest = rows.reduce((most, alert) => Math.max(most, alert.consecutiveDays), 0);

  if (studentId === null) {
    return (
      <>
        <PageHead title="Absence alerts" />
        <Notice tone="warning" title="This account has no student record">
          Alerts are raised against a student profile. Ask the hostel office to link your account.
        </Notice>
      </>
    );
  }

  return (
    <>
      <PageHead
        title="Absence alerts"
        subtitle="Raised automatically when the register shows a run of absences"
      />

      <div className="stat-grid">
        <StatCard
          label="Open"
          value={alerts.data === undefined ? '--' : open.length}
          note={open.length === 0 ? 'Nothing outstanding' : 'Your warden has been notified'}
        />
        <StatCard
          label="Raised in total"
          value={alerts.data === undefined ? '--' : rows.length}
          note="Across your whole record"
        />
        <StatCard
          label="Longest run"
          value={alerts.data === undefined ? '--' : longest === 0 ? '--' : plural(longest, 'day')}
          note="Consecutive days marked absent"
        />
      </div>

      {open.length > 0 ? (
        <Notice tone="warning" title="Talk to your warden">
          An open alert usually means a message has gone to the contact on your record. Only a
          warden can close it, and they do that after speaking to you -- so the fastest way to
          clear this is to go and see them.
        </Notice>
      ) : null}

      <Card flush>
        <DataState query={alerts} skeletonRows={5} errorTitle="Could not load your alerts">
          {(data) =>
            data.length === 0 ? (
              <EmptyState title="No alerts">
                Nothing has been raised against your attendance record.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Streak began</th>
                    <th className="num">Days</th>
                    <th>Raised on</th>
                    <th>Parent notified</th>
                    <th>Closed</th>
                    <th className="shrink">State</th>
                  </tr>
                </thead>
                <tbody>
                  {/* Open first, then newest. Two sort keys because an acknowledged
                      alert from yesterday is less urgent than an open one from last
                      week, and a plain date sort buries the row that still needs
                      something done about it. */}
                  {[...data]
                    .sort((left, right) => {
                      if (left.acknowledged !== right.acknowledged) {
                        return left.acknowledged ? 1 : -1;
                      }
                      return right.triggeredOn.localeCompare(left.triggeredOn);
                    })
                    .map((alert) => (
                      <tr key={alert.id}>
                        <td className="nowrap">{formatDate(alert.streakStartDate)}</td>
                        <td className="num nums">{alert.consecutiveDays}</td>
                        <td className="nowrap">
                          {formatDate(alert.triggeredOn)}
                          {!alert.acknowledged ? (
                            <span className="cell-sub">
                              open {plural(alert.ageDays, 'day')}
                            </span>
                          ) : null}
                        </td>
                        <td className="nowrap small">
                          {alert.notifiedAt === null ? (
                            <span className="faint">Not sent</span>
                          ) : (
                            formatDateTime(alert.notifiedAt)
                          )}
                        </td>
                        <td className="nowrap small">
                          {alert.acknowledgedAt === null ? (
                            <span className="faint">--</span>
                          ) : (
                            <>
                              {formatDateTime(alert.acknowledgedAt)}
                              <span className="cell-sub">{alert.acknowledgedByName ?? '--'}</span>
                            </>
                          )}
                        </td>
                        <td className="shrink">
                          <AlertStateBadge acknowledged={alert.acknowledged} />
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
