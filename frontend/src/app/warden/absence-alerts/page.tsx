'use client';

/**
 * The absence queue.
 *
 * <p>Two lists behind one toggle, because they answer different questions: the open
 * list is a worklist ("who needs a call today"), the full list is a record ("was this
 * ever followed up"). They are separate endpoints on the server for the same reason,
 * and the toggle makes that visible rather than hiding it behind a status filter.
 *
 * <p>Acknowledging is the only write, and it can lose a race: two wardens on the same
 * queue, and the second one gets a 409 `ALERT_ALREADY_ACKNOWLEDGED`. That is handled
 * as information rather than as an error -- the alert *is* acknowledged, which is the
 * outcome the click wanted -- so the list refetches and the row shows who got there
 * first. Only genuinely failed acknowledgements surface as an error.
 *
 * <p>Alerts cannot be created or deleted from anywhere in this app, which mirrors
 * `AbsenceAlertController`: an alert is derived from the register, so the only honest
 * way to make one appear is for the register to say a student is missing, and the only
 * way to make one go away on its merits is for the register to say they came back.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { codeOf } from '@/lib/api-error';
import { useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, plural, yearLabel } from '@/lib/format';
import {
  Badge,
  Button,
  Card,
  DataState,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  Pager,
  TableWrap,
} from '@/components/ui';
import { AlertStateBadge } from '@/components/status-badges';
import type { AbsenceAlert } from '@/lib/types';

export default function WardenAbsenceAlertsPage() {
  const [showAll, setShowAll] = useState(false);
  const [page, setPage] = useState(0);
  const [raced, setRaced] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);

  const alerts = useQuery(
    (signal) =>
      showAll
        ? warden.absenceAlerts.all({ page, size: 20 }, signal)
        : warden.absenceAlerts.open({ page, size: 20 }, signal),
    [showAll, page],
  );

  /**
   * The one write on this page, and the one place `useAction` is deliberately not used.
   *
   * <p>That hook stores the rejection and resolves to `undefined`, which is the right
   * shape when the error only has to be displayed -- but this handler has to *branch*
   * on the error code, and reading `action.error` straight after `await run()` would
   * read the previous render's value, not the failure that just happened. So the
   * failure is caught where it is thrown.
   *
   * <p>A 409 is treated as success: the alert is acknowledged, which is what the click
   * was for. Anything else is a real error and is shown as one.
   */
  const onAcknowledge = async (alert: AbsenceAlert) => {
    setBusyId(alert.id);
    setRaced(null);
    setError(null);
    try {
      await warden.absenceAlerts.acknowledge(alert.id);
      alerts.refetch();
    } catch (cause) {
      if (codeOf(cause) === 'ALERT_ALREADY_ACKNOWLEDGED') {
        setRaced(alert.student.fullName);
        alerts.refetch();
      } else {
        setError(cause);
      }
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      <PageHead
        title="Absence alerts"
        subtitle="Raised by the nightly scan over the register"
        actions={
          <Button
            onClick={() => {
              setShowAll((current) => !current);
              setPage(0);
            }}
          >
            {showAll ? 'Show open only' : 'Show all'}
          </Button>
        }
      />

      {raced ? (
        <Notice tone="info" title="Already acknowledged">
          Another warden acknowledged {raced}&apos;s alert first. The row below shows who.
        </Notice>
      ) : null}

      {error ? <ErrorNotice error={error} title="Could not acknowledge the alert" /> : null}

      <Card flush>
        <DataState query={alerts} skeletonRows={8} errorTitle="Could not load alerts">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title={showAll ? 'No alerts have been raised' : 'Nothing open'}>
                {showAll
                  ? 'The scan has not found a qualifying absence streak yet.'
                  : 'Every raised alert has been acknowledged.'}
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Student</th>
                      <th>Roll number</th>
                      <th className="num">Days</th>
                      <th>Streak began</th>
                      <th>Raised</th>
                      <th>Notified</th>
                      <th>Acknowledged</th>
                      <th className="shrink">State</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((alert) => (
                      <tr key={alert.id}>
                        <td>
                          <Link href={`/warden/students/${alert.student.id}`} className="cell-strong">
                            {alert.student.fullName}
                          </Link>
                          <span className="cell-sub">{yearLabel(alert.student.yearOfStudy)}</span>
                        </td>
                        <td className="mono">{alert.student.rollNumber}</td>
                        <td className="num nums">
                          {/* The streak length, not the alert's age. A four-day-old alert
                              for a nine-day absence is the urgent row, and sorting by the
                              wrong one of those buries it. */}
                          <Badge tone={alert.consecutiveDays >= 7 ? 'danger' : 'warning'}>
                            {alert.consecutiveDays}
                          </Badge>
                        </td>
                        <td className="nowrap">{formatDate(alert.streakStartDate)}</td>
                        <td className="nowrap">
                          {formatDate(alert.triggeredOn)}
                          <span className="cell-sub">{plural(alert.ageDays, 'day')} ago</span>
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
                              {alert.acknowledgedByName ?? 'Unknown'}
                              <span className="cell-sub">{formatDateTime(alert.acknowledgedAt)}</span>
                            </>
                          )}
                        </td>
                        <td className="shrink">
                          <AlertStateBadge acknowledged={alert.acknowledged} />
                        </td>
                        <td className="shrink">
                          {alert.acknowledged ? null : (
                            <Button
                              small
                              variant="primary"
                              pending={busyId === alert.id}
                              disabled={busyId !== null}
                              onClick={() => onAcknowledge(alert)}
                            >
                              Acknowledge
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
                <div className="card-foot">
                  <Pager
                    page={data.number}
                    totalPages={data.totalPages}
                    totalElements={data.totalElements}
                    shown={data.numberOfElements}
                    onPage={setPage}
                    busy={alerts.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>
    </>
  );
}
