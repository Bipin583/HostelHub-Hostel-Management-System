'use client';

/**
 * The application queue: approve or reject, with the decision recorded.
 *
 * <p>Defaults to PENDING rather than to everything, because this page is a worklist.
 * The filter can widen it to the decided ones, which is what makes it double as the
 * record of who decided what -- `decidedBy` and the note are on every row.
 *
 * <p>Both decisions go through a dialog, for asymmetric reasons. Rejection needs a
 * reason: the backend marks it `@NotBlank` and the student sees it, so a free-text box
 * is required, not optional politeness. Approval takes an optional note and still gets
 * a confirmation step, because approving is the action that creates a bed commitment
 * and a mis-click on a dense table is the likeliest way to make one by accident.
 *
 * <p>Approving does not allocate a room -- that is a separate decision on a separate
 * endpoint, and this UI keeps them separate too. The success notice therefore links to
 * the student, which is where the room actually gets assigned.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime } from '@/lib/format';
import { APPLICATION_STATUSES, type Application } from '@/lib/types';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  enumOptions,
  ErrorNotice,
  Notice,
  PageHead,
  Pager,
  SelectField,
  TableWrap,
  TextAreaField,
} from '@/components/ui';
import { Dialog } from '@/components/dialog';
import { ApplicationStatusBadge } from '@/components/status-badges';

type Decision = 'approve' | 'reject';

export default function WardenApplicationsPage() {
  const [status, setStatus] = useState<string>('PENDING');
  const [page, setPage] = useState(0);
  const [target, setTarget] = useState<Application | null>(null);
  const [decision, setDecision] = useState<Decision>('approve');
  const [text, setText] = useState('');
  const [decided, setDecided] = useState<Application | null>(null);

  const applications = useQuery(
    (signal) =>
      warden.applications.list(
        { page, size: 20, status: status ? (status as Application['status']) : undefined },
        signal,
      ),
    [page, status],
  );

  const act = useAction((application: Application, mode: Decision, note: string) =>
    mode === 'approve'
      ? warden.applications.approve(application.id, note.trim() || undefined)
      : warden.applications.reject(application.id, note.trim()),
  );

  const open = (application: Application, mode: Decision) => {
    setTarget(application);
    setDecision(mode);
    setText('');
    act.reset();
  };

  const close = () => setTarget(null);

  const submit = async () => {
    if (target === null) return;
    const result = await act.run(target, decision, text);
    if (result) {
      setDecided(result);
      close();
      applications.refetch();
    }
  };

  const rejecting = decision === 'reject';
  // The only client-side validation on this form, and it mirrors a server constraint
  // rather than inventing one: `@NotBlank` on the reject request record.
  const blocked = rejecting && text.trim() === '';
  // An empty box is not an error -- the user has not started. A box holding only
  // spaces is, because it looks filled in and the server will reject it.
  const whitespaceOnly = blocked && text !== '';

  return (
    <>
      <PageHead title="Applications" subtitle="Requests for accommodation" />

      {decided ? (
        <Notice tone={decided.status === 'APPROVED' ? 'success' : 'info'} title={`Application ${decided.status.toLowerCase()}`}>
          {decided.fullName} · {decided.status === 'APPROVED' ? (
            <>
              now needs a room --{' '}
              <Link href={`/warden/students/${decided.studentId}`}>allocate one</Link>.
            </>
          ) : (
            'the student can see the reason on their application page.'
          )}
        </Notice>
      ) : null}

      <Card>
        <div className="filters">
          <SelectField
            label="Status"
            placeholder="Any status"
            options={enumOptions(APPLICATION_STATUSES)}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          />
        </div>
      </Card>

      <Card flush>
        <DataState query={applications} skeletonRows={8} errorTitle="Could not load applications">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="Nothing here">
                {status === 'PENDING'
                  ? 'No application is waiting for a decision.'
                  : 'No application matches this filter.'}
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Student</th>
                      <th>Roll number</th>
                      <th>Applied</th>
                      <th>Decided</th>
                      <th>Note</th>
                      <th className="shrink">Status</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((application) => (
                      <tr key={application.id}>
                        <td>
                          <Link
                            href={`/warden/students/${application.studentId}`}
                            className="cell-strong"
                          >
                            {application.fullName}
                          </Link>
                        </td>
                        <td className="mono">{application.rollNumber}</td>
                        <td className="nowrap">{formatDateTime(application.appliedAt)}</td>
                        <td className="nowrap">
                          {application.decidedAt === null ? (
                            '--'
                          ) : (
                            <>
                              {formatDateTime(application.decidedAt)}
                              <span className="cell-sub">{application.decidedBy ?? 'Unknown'}</span>
                            </>
                          )}
                        </td>
                        <td className="small">{application.note ?? '--'}</td>
                        <td className="shrink">
                          <ApplicationStatusBadge status={application.status} />
                        </td>
                        <td className="shrink">
                          {application.status === 'PENDING' ? (
                            <span className="row-tight nowrap">
                              <Button small variant="primary" onClick={() => open(application, 'approve')}>
                                Approve
                              </Button>
                              <Button small variant="danger" onClick={() => open(application, 'reject')}>
                                Reject
                              </Button>
                            </span>
                          ) : null}
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
                    busy={applications.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>

      <Dialog
        open={target !== null}
        onClose={close}
        title={rejecting ? 'Reject this application' : 'Approve this application'}
        footer={
          <>
            <Button variant="ghost" onClick={close} disabled={act.running}>
              Cancel
            </Button>
            <Button
              variant={rejecting ? 'danger' : 'primary'}
              disabled={blocked}
              pending={act.running}
              onClick={submit}
            >
              {rejecting ? 'Reject' : 'Approve'}
            </Button>
          </>
        }
      >
        <div className="stack-sm">
          <p>
            {target?.fullName} · <span className="mono">{target?.rollNumber}</span>
          </p>
          <TextAreaField
            label={rejecting ? 'Reason' : 'Note (optional)'}
            rows={3}
            value={text}
            hint={
              rejecting
                ? 'The student sees this. Say what would need to change.'
                : 'Kept on the application record.'
            }
            error={whitespaceOnly ? 'A reason is required.' : undefined}
            onChange={(event) => setText(event.target.value)}
          />
          {act.error ? <ErrorNotice error={act.error} title="The decision was not recorded" /> : null}
        </div>
      </Dialog>
    </>
  );
}
