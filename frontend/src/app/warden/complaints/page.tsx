'use client';

/**
 * The complaint queue, with the analytics that say whether it is being worked.
 *
 * <p>The two halves answer different questions and are sized accordingly. The
 * analytics card is small and sits at the top because it is the only thing on this
 * screen a warden reads *about themselves*; the table below is the work.
 *
 * <p><b>Queue time and work time are shown separately.</b> `ComplaintAnalytics` splits
 * the average resolution into the wait before anybody looked (`avgQueueSeconds`) and
 * the time it took once somebody did (`avgWorkSeconds`), and this page keeps them
 * apart rather than adding them up. The total alone cannot distinguish a hostel that
 * triages instantly and fixes slowly from one that fixes instantly and ignores its
 * inbox for a week -- and those two need opposite interventions.
 *
 * <p><b>p90 sits next to the average</b> for the usual reason: one complaint left open
 * for a month barely moves a mean over fifty, and it is exactly the complaint that
 * turns into an escalation.
 *
 * <p>The status buttons follow the server's state machine (OPEN to IN_PROGRESS to
 * RESOLVED) and show only the transition that is legal from the row's current state.
 * That is a convenience, not the rule: `ILLEGAL_STATE_TRANSITION` and
 * `RESOLUTION_NOTE_REQUIRED` still come from the server, and are still displayed if
 * something changed underneath the page.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime, formatDuration, humanise } from '@/lib/format';
import { COMPLAINT_STATUSES, type Complaint } from '@/lib/types';
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
  StatCard,
  TableWrap,
  TextAreaField,
} from '@/components/ui';
import { Dialog } from '@/components/dialog';
import { ComplaintStatusBadge, UrgencyMark } from '@/components/status-badges';

/** Bounded 1..366 by the controller, so 365 is the widest honest option. */
const WINDOW_OPTIONS = [
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
  { value: '365', label: 'Last year' },
];

export default function WardenComplaintsPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [windowDays, setWindowDays] = useState('30');
  const [startingId, setStartingId] = useState<number | null>(null);
  const [resolveTarget, setResolveTarget] = useState<Complaint | null>(null);
  const [note, setNote] = useState('');

  const complaints = useQuery(
    (signal) =>
      warden.complaints.list(
        { page, size: 20, status: status ? (status as Complaint['status']) : undefined },
        signal,
      ),
    [page, status],
  );
  const analytics = useQuery(
    (signal) => warden.complaints.analytics(Number(windowDays), signal),
    [windowDays],
  );

  const start = useAction((complaint: Complaint) =>
    warden.complaints.setStatus(complaint.id, 'IN_PROGRESS'),
  );
  const resolve = useAction((complaint: Complaint, resolutionNote: string) =>
    warden.complaints.setStatus(complaint.id, 'RESOLVED', resolutionNote.trim()),
  );

  const onStart = async (complaint: Complaint) => {
    setStartingId(complaint.id);
    const updated = await start.run(complaint);
    setStartingId(null);
    if (updated) {
      complaints.refetch();
      analytics.refetch();
    }
  };

  const onResolve = async () => {
    if (resolveTarget === null) return;
    const updated = await resolve.run(resolveTarget, note);
    if (updated) {
      setResolveTarget(null);
      complaints.refetch();
      analytics.refetch();
    }
  };

  // Mirrors `RESOLUTION_NOTE_REQUIRED` rather than inventing a rule: the server will
  // not resolve without a note, so the button does not offer to try.
  const noteMissing = note.trim() === '';

  return (
    <>
      <PageHead title="Complaints" subtitle="Maintenance and welfare reports from residents" />

      <Card
        title="How the queue is being worked"
        subtitle="Resolution times over the chosen window; the backlog is current"
        actions={
          <SelectField
            label="Window"
            options={WINDOW_OPTIONS}
            value={windowDays}
            onChange={(event) => setWindowDays(event.target.value)}
          />
        }
      >
        <DataState query={analytics} skeletonRows={3} errorTitle="Could not load analytics">
          {(data) => (
            <>
              <div className="stat-grid">
                <StatCard
                  label="Open now"
                  value={data.backlog.openCount}
                  note={
                    data.backlog.openCount === 0
                      ? 'Nothing waiting'
                      : `Oldest has waited ${formatDuration(data.backlog.oldestOpenSeconds)}`
                  }
                />
                <StatCard
                  label="Resolved in window"
                  value={data.resolution.resolvedCount}
                  note={`Average ${formatDuration(data.resolution.avgTotalSeconds)} end to end`}
                />
                <StatCard
                  label="Waiting to be picked up"
                  value={formatDuration(data.resolution.avgQueueSeconds)}
                  note="Average time before anybody started"
                />
                <StatCard
                  label="Time to fix"
                  value={formatDuration(data.resolution.avgWorkSeconds)}
                  note={`p90 end to end ${formatDuration(data.resolution.p90TotalSeconds)}`}
                />
              </div>

              {data.byCategory.length > 0 ? (
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Category</th>
                      <th className="num">Total</th>
                      <th className="num">Open</th>
                      <th className="num">In progress</th>
                      <th className="num">Resolved</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.byCategory.map((row) => (
                      <tr key={row.category}>
                        <td className="cell-strong">{humanise(row.category)}</td>
                        <td className="num nums">{row.total}</td>
                        {/* `byStatus` is a partial map: a category with nothing resolved
                            has no RESOLVED key at all, so every read needs its own
                            zero. Rendering `undefined` here would print nothing and
                            read as a missing column rather than as none. */}
                        <td className="num nums">{row.byStatus.OPEN ?? 0}</td>
                        <td className="num nums">{row.byStatus.IN_PROGRESS ?? 0}</td>
                        <td className="num nums">{row.byStatus.RESOLVED ?? 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              ) : (
                <p className="small muted">No complaint has been filed in this window.</p>
              )}
            </>
          )}
        </DataState>
      </Card>

      <Card>
        <div className="filters">
          <SelectField
            label="Status"
            placeholder="Any status"
            options={enumOptions(COMPLAINT_STATUSES)}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          />
        </div>
      </Card>

      {start.error ? <ErrorNotice error={start.error} title="Could not start work" /> : null}

      <Card flush>
        <DataState query={complaints} skeletonRows={8} errorTitle="Could not load complaints">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="Nothing here">
                {status ? 'No complaint has this status.' : 'No complaint has been filed.'}
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Complaint</th>
                      <th>Student</th>
                      <th>Category</th>
                      <th>Urgency</th>
                      <th>Filed</th>
                      <th>Resolved</th>
                      <th className="shrink">Status</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((complaint) => (
                      <tr key={complaint.id}>
                        <td>
                          <Link
                            href={`/warden/complaints/${complaint.id}`}
                            className="cell-strong"
                          >
                            {complaint.title}
                          </Link>
                        </td>
                        <td>
                          <Link href={`/warden/students/${complaint.student.id}`}>
                            {complaint.student.fullName}
                          </Link>
                          <span className="cell-sub mono">{complaint.student.rollNumber}</span>
                        </td>
                        <td className="small">{humanise(complaint.category)}</td>
                        <td>
                          <UrgencyMark urgency={complaint.urgency} />
                        </td>
                        <td className="nowrap small">{formatDateTime(complaint.createdAt)}</td>
                        <td className="nowrap small">
                          {complaint.resolvedAt === null ? (
                            <span className="faint">--</span>
                          ) : (
                            <>
                              {formatDateTime(complaint.resolvedAt)}
                              <span className="cell-sub">
                                {complaint.resolvedByName ?? 'Unknown'}
                              </span>
                            </>
                          )}
                        </td>
                        <td className="shrink">
                          <ComplaintStatusBadge status={complaint.status} />
                        </td>
                        <td className="shrink">
                          {complaint.status === 'OPEN' ? (
                            <Button
                              small
                              variant="primary"
                              pending={startingId === complaint.id}
                              disabled={start.running}
                              onClick={() => onStart(complaint)}
                            >
                              Start work
                            </Button>
                          ) : complaint.status === 'IN_PROGRESS' ? (
                            <Button
                              small
                              variant="primary"
                              onClick={() => {
                                setResolveTarget(complaint);
                                setNote('');
                                resolve.reset();
                              }}
                            >
                              Resolve
                            </Button>
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
                    busy={complaints.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>

      <Dialog
        open={resolveTarget !== null}
        onClose={() => setResolveTarget(null)}
        title="Resolve this complaint"
        footer={
          <>
            <Button variant="ghost" onClick={() => setResolveTarget(null)} disabled={resolve.running}>
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={noteMissing}
              pending={resolve.running}
              onClick={onResolve}
            >
              Resolve
            </Button>
          </>
        }
      >
        <div className="stack-sm">
          <p className="cell-strong">{resolveTarget?.title}</p>
          <p className="small muted">{resolveTarget?.description}</p>
          {resolveTarget ? (
            <Notice tone="info">
              Filed {formatDateTime(resolveTarget.createdAt)} by {resolveTarget.student.fullName}
              {resolveTarget.inProgressAt
                ? ` · picked up ${formatDateTime(resolveTarget.inProgressAt)}`
                : ''}
            </Notice>
          ) : null}
          <TextAreaField
            label="What was done"
            rows={3}
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
