'use client';

/**
 * One complaint, in full, with its own clock.
 *
 * <p>The list page truncates the description to a title; this is where the actual
 * report is readable, which is the difference between "Internet not working" and
 * "Internet not working in B-block since Tuesday, the switch in the corridor cupboard
 * clicks every few seconds". A queue screen cannot show that, so this page must.
 *
 * <p><b>The timeline shows the two gaps, not the two timestamps.</b> Filed at 09:12 and
 * picked up at 16:40 are facts a reader has to subtract; "waited 7h 28m" is the fact
 * they were subtracting for. The same split as the analytics card -- queue time, then
 * work time -- so a single complaint can be read against the hostel's average.
 *
 * <p>The elapsed figures are computed from `Instant`s with `Date`, which is correct
 * here and would be a bug on a `LocalDate`: these are moments in time, not calendar
 * days, so arithmetic on them is timezone-independent. See the note in format.ts.
 */

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime, formatDuration, humanise } from '@/lib/format';
import {
  Button,
  Card,
  DataState,
  ErrorNotice,
  Notice,
  PageHead,
  TextAreaField,
} from '@/components/ui';
import { Dialog } from '@/components/dialog';
import { ComplaintStatusBadge, UrgencyMark } from '@/components/status-badges';

export default function WardenComplaintDetailPage() {
  const routeParams = useParams();
  const rawId = Array.isArray(routeParams.id) ? routeParams.id[0] : routeParams.id;
  const complaintId = Number(rawId);
  const validId = Number.isInteger(complaintId) && complaintId > 0;

  const [resolveOpen, setResolveOpen] = useState(false);
  const [note, setNote] = useState('');

  const complaint = useQuery(
    (signal) => warden.complaints.byId(complaintId, signal),
    [complaintId],
    { enabled: validId },
  );

  const start = useAction(() => warden.complaints.setStatus(complaintId, 'IN_PROGRESS'));
  const resolve = useAction((resolutionNote: string) =>
    warden.complaints.setStatus(complaintId, 'RESOLVED', resolutionNote.trim()),
  );

  if (!validId) {
    return (
      <>
        <PageHead title="Complaint" crumb={<Link href="/warden/complaints">Complaints</Link>} />
        <Notice tone="error" title="That is not a complaint id">
          The address should end in a number, as in /warden/complaints/12.
        </Notice>
      </>
    );
  }

  const data = complaint.data;
  const noteMissing = note.trim() === '';

  return (
    <>
      <PageHead
        title={data?.title ?? 'Complaint'}
        crumb={<Link href="/warden/complaints">Complaints</Link>}
        subtitle={data ? `Filed by ${data.student.fullName} · ${humanise(data.category)}` : undefined}
        actions={
          data?.status === 'OPEN' ? (
            <Button
              variant="primary"
              pending={start.running}
              onClick={async () => {
                const updated = await start.run();
                if (updated) complaint.refetch();
              }}
            >
              Start work
            </Button>
          ) : data?.status === 'IN_PROGRESS' ? (
            <Button
              variant="primary"
              onClick={() => {
                setNote('');
                resolve.reset();
                setResolveOpen(true);
              }}
            >
              Resolve
            </Button>
          ) : null
        }
      />

      {start.error ? <ErrorNotice error={start.error} title="Could not start work" /> : null}

      <DataState query={complaint} skeletonRows={6} errorTitle="Could not load this complaint">
        {(detail) => (
          <>
            <Card title="Report">
              <dl className="facts">
                <dt className="fact-label">Student</dt>
                <dd className="fact-value">
                  <Link href={`/warden/students/${detail.student.id}`}>
                    {detail.student.fullName}
                  </Link>
                  <span className="cell-sub mono">{detail.student.rollNumber}</span>
                </dd>
                <dt className="fact-label">Category</dt>
                <dd className="fact-value">{humanise(detail.category)}</dd>
                <dt className="fact-label">Urgency</dt>
                <dd className="fact-value">
                  <UrgencyMark urgency={detail.urgency} />
                </dd>
                <dt className="fact-label">Status</dt>
                <dd className="fact-value">
                  <ComplaintStatusBadge status={detail.status} />
                </dd>
              </dl>
              {/* `white-space: pre-wrap` so a student's line breaks survive. A report
                  typed as three paragraphs should not arrive as one. */}
              <p style={{ whiteSpace: 'pre-wrap' }}>{detail.description}</p>
            </Card>

            <Card title="Timeline">
              <dl className="facts">
                <dt className="fact-label">Filed</dt>
                <dd className="fact-value">{formatDateTime(detail.createdAt)}</dd>
                <dt className="fact-label">Picked up</dt>
                <dd className="fact-value">
                  {detail.inProgressAt === null ? (
                    <span className="faint">Not yet</span>
                  ) : (
                    <>
                      {formatDateTime(detail.inProgressAt)}
                      <span className="cell-sub">
                        waited {formatDuration(secondsBetween(detail.createdAt, detail.inProgressAt))}
                      </span>
                    </>
                  )}
                </dd>
                <dt className="fact-label">Resolved</dt>
                <dd className="fact-value">
                  {detail.resolvedAt === null ? (
                    <span className="faint">Not yet</span>
                  ) : (
                    <>
                      {formatDateTime(detail.resolvedAt)}
                      <span className="cell-sub">
                        {/* Measured from when work started when that is known, and from
                            filing when it is not -- a complaint resolved without ever
                            being marked in progress has no work interval to report. */}
                        {detail.inProgressAt === null
                          ? `${formatDuration(secondsBetween(detail.createdAt, detail.resolvedAt))} end to end`
                          : `fixed in ${formatDuration(secondsBetween(detail.inProgressAt, detail.resolvedAt))}`}
                      </span>
                    </>
                  )}
                </dd>
                <dt className="fact-label">Resolved by</dt>
                <dd className="fact-value">
                  {detail.resolvedByName ?? <span className="faint">--</span>}
                </dd>
              </dl>
            </Card>

            {detail.resolutionNote ? (
              <Card title="Resolution" subtitle="Visible to the student">
                <p style={{ whiteSpace: 'pre-wrap' }}>{detail.resolutionNote}</p>
              </Card>
            ) : null}
          </>
        )}
      </DataState>

      <Dialog
        open={resolveOpen}
        onClose={() => setResolveOpen(false)}
        title="Resolve this complaint"
        footer={
          <>
            <Button variant="ghost" onClick={() => setResolveOpen(false)} disabled={resolve.running}>
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={noteMissing}
              pending={resolve.running}
              onClick={async () => {
                const updated = await resolve.run(note);
                if (updated) {
                  setResolveOpen(false);
                  complaint.refetch();
                }
              }}
            >
              Resolve
            </Button>
          </>
        }
      >
        <div className="stack-sm">
          <TextAreaField
            label="What was done"
            rows={4}
            value={note}
            hint="Required, and the student sees it. Say what changed, not that it is closed."
            error={noteMissing && note !== '' ? 'A resolution note is required.' : undefined}
            onChange={(event) => setNote(event.target.value)}
          />
          {resolve.error ? (
            <ErrorNotice error={resolve.error} title="The complaint was not resolved" />
          ) : null}
        </div>
      </Dialog>
    </>
  );
}

/**
 * Whole seconds between two ISO instants, for `formatDuration`.
 *
 * <p>Returns null rather than a negative number if the pair is out of order or
 * unparseable, so a clock skew on the server cannot render "-3h" as though it were a
 * measurement. Callers already have a "--" for the null case.
 */
function secondsBetween(fromIso: string, toIso: string): number | null {
  const from = new Date(fromIso).getTime();
  const to = new Date(toIso).getTime();
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) return null;
  return Math.round((to - from) / 1000);
}
