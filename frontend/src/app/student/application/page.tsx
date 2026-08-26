'use client';

/**
 * Apply for a hostel place, and see what happened to previous applications.
 *
 * <p>The whole page is one button and a history table, which understates how much of
 * the system it drives. Applying moves the student to PENDING; a warden approving it
 * moves them to APPROVED; allocation then moves them to ALLOCATED and gives them a
 * room. This screen is the only place a student can start that chain.
 *
 * <p><b>The button is hidden when an application is already open, and the server still
 * enforces it.</b> `POST /student/applications` answers 409 `DUPLICATE_APPLICATION`
 * when one is pending, so the check here is a courtesy -- it stops a user firing a
 * request that cannot succeed. The error is displayed anyway, because two tabs, or a
 * warden deciding while this page sits open, both produce it legitimately.
 *
 * <p>Note there is no cancel or withdraw: the backend has no such endpoint, so this
 * page does not imply one. A student who applied by mistake talks to the office.
 */

import Link from 'next/link';
import { student } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime } from '@/lib/format';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  TableWrap,
} from '@/components/ui';
import { AllocationStatusBadge, ApplicationStatusBadge } from '@/components/status-badges';

export default function StudentApplicationPage() {
  const me = useQuery((signal) => student.me.get(signal));
  const applications = useQuery((signal) => student.applications.mine(signal));
  const apply = useAction(() => student.applications.apply());

  const rows = applications.data ?? [];
  const hasPending = rows.some((application) => application.status === 'PENDING');
  const allocated = me.data?.allocationStatus === 'ALLOCATED';
  // Only offered when there is nothing open and no room already held. `me` and
  // `applications` are separate requests, so this is deliberately conservative while
  // either is still loading: no button beats a button that 409s.
  const canApply = me.data !== undefined && applications.data !== undefined && !hasPending && !allocated;

  return (
    <>
      <PageHead
        title="Hostel application"
        subtitle="Applying puts you in the queue for a room"
        actions={
          canApply ? (
            <Button
              variant="primary"
              pending={apply.running}
              onClick={async () => {
                const created = await apply.run();
                if (created) {
                  applications.refetch();
                  me.refetch();
                }
              }}
            >
              Apply for a place
            </Button>
          ) : null
        }
      />

      {apply.error ? <ErrorNotice error={apply.error} title="The application was not filed" /> : null}

      <Card title="Where you stand">
        <DataState query={me} skeletonRows={3} errorTitle="Could not load your record">
          {(detail) => (
            <div className="stack-sm">
              <AllocationStatusBadge status={detail.allocationStatus} />
              {detail.allocationStatus === 'NOT_APPLIED' ? (
                <p className="small muted">
                  You have no application on record. Applying is a single step -- there is no form,
                  because everything the warden needs is already on your student record.
                </p>
              ) : detail.allocationStatus === 'PENDING' ? (
                <p className="small muted">
                  Your application is waiting on a warden. You will not be able to file another
                  until this one is decided.
                </p>
              ) : detail.currentRoom === null ? (
                <p className="small muted">
                  Approved. A room has not been assigned yet -- the warden allocates from the
                  approved list as beds free up.
                </p>
              ) : (
                <p className="small muted">
                  You are in <span className="mono">{detail.currentRoom.roomName}</span>. See{' '}
                  <Link href="/student">your overview</Link> for the details.
                </p>
              )}
            </div>
          )}
        </DataState>
      </Card>

      {hasPending ? (
        <Notice tone="info" title="One application at a time">
          Filing a second while one is pending is refused by the server, so the button is hidden
          until this one is decided.
        </Notice>
      ) : null}

      <Card flush title="History" subtitle="Every application you have filed">
        <DataState query={applications} skeletonRows={3} errorTitle="Could not load your applications">
          {(data) =>
            data.length === 0 ? (
              <EmptyState title="No applications yet">
                Nothing has been filed from this account.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Filed</th>
                    <th>Decided</th>
                    <th>By</th>
                    <th>Note</th>
                    <th className="shrink">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {data.map((application) => (
                    <tr key={application.id}>
                      <td className="nowrap small">{formatDateTime(application.appliedAt)}</td>
                      <td className="nowrap small">
                        {application.decidedAt === null ? (
                          <span className="faint">Pending</span>
                        ) : (
                          formatDateTime(application.decidedAt)
                        )}
                      </td>
                      <td className="small">{application.decidedBy ?? '--'}</td>
                      {/* The warden's own words, whether that is an approval note or
                          the mandatory rejection reason. Paraphrasing a rejection into
                          "not approved" would remove the only thing a student can act
                          on. */}
                      <td className="small">
                        {application.note ?? <span className="faint">--</span>}
                      </td>
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
