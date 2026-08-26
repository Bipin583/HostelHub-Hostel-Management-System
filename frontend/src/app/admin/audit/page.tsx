'use client';

/**
 * The audit log: who changed what, and what the change was.
 *
 * <p><b>Three ways in, one table.</b> "Everything, newest first" answers *what just
 * happened*; "by entity" answers *what happened to this room / this fee / this student*;
 * "by actor" answers *what has this member of staff been doing*. Those are the three
 * questions an audit trail actually gets asked, and they are three endpoints on the server
 * rather than one endpoint with optional filters -- so the scope selector picks the
 * endpoint instead of adding query parameters.
 *
 * <p><b>The activity counter is a separate request on purpose.</b> "How many events in the
 * last hour" is not derivable from a page of twenty rows, and counting the rows on screen
 * would give a number that changes with the page size. It comes from
 * `GET /admin/audit/activity`, which takes an instant and counts server-side.
 *
 * <p><b>`payloadDiff` is rendered as raw JSON.</b> It is typed `unknown` because its shape
 * depends on the entity that changed, and inventing a per-entity renderer would mean a
 * frontend that silently omits fields it has not been taught about -- the one thing an
 * audit log must never do. The `AuditAspect` on the server already redacts anything
 * matching password, secret, token, credential, signature, otp or pin before it is stored,
 * so what arrives here is safe to show verbatim.
 *
 * <p>The trail is append-only and this page has no controls, which is the correct amount of
 * interaction for a record whose value comes entirely from not being editable.
 */

import { useMemo, useState } from 'react';
import { admin } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatDateTime, humanise, plural } from '@/lib/format';
import type { AuditAction } from '@/lib/types';
import {
  Badge,
  Card,
  DataState,
  EmptyState,
  Notice,
  PageHead,
  Pager,
  SelectField,
  StatCard,
  TableWrap,
  TextField,
} from '@/components/ui';

type Scope = 'all' | 'entity' | 'actor';

const SCOPE_OPTIONS = [
  { value: 'all', label: 'Everything' },
  { value: 'entity', label: 'One entity' },
  { value: 'actor', label: 'One actor' },
];

const WINDOW_OPTIONS = [
  { value: '1', label: 'Last hour' },
  { value: '24', label: 'Last 24 hours' },
  { value: '168', label: 'Last 7 days' },
];

/** CREATE reads as additive, DELETE as destructive; UPDATE is neither. */
const ACTION_TONES: Record<AuditAction, 'success' | 'info' | 'danger'> = {
  CREATE: 'success',
  UPDATE: 'info',
  DELETE: 'danger',
};

export default function AdminAuditPage() {
  const [scope, setScope] = useState<Scope>('all');
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [actorId, setActorId] = useState('');
  const [page, setPage] = useState(0);
  const [windowHours, setWindowHours] = useState('24');

  // Computed once per window choice rather than on every render: a `since` that moved
  // with the render clock would change the query's dependencies constantly and refetch
  // forever.
  const since = useMemo(
    () => new Date(Date.now() - Number(windowHours) * 3_600_000).toISOString(),
    [windowHours],
  );

  const entityIdNumber = Number(entityId);
  const actorIdNumber = Number(actorId);
  const entityReady =
    entityType.trim() !== '' && Number.isInteger(entityIdNumber) && entityIdNumber > 0;
  const actorReady = Number.isInteger(actorIdNumber) && actorIdNumber > 0;
  const scopeReady =
    scope === 'all' || (scope === 'entity' ? entityReady : actorReady);

  const activity = useQuery((signal) => admin.audit.activity(since, signal), [since]);

  const events = useQuery(
    (signal) => {
      const params = { page, size: 20 };
      if (scope === 'entity') {
        return admin.audit.forEntity(entityType.trim(), entityIdNumber, params, signal);
      }
      if (scope === 'actor') {
        return admin.audit.forActor(actorIdNumber, params, signal);
      }
      return admin.audit.list(params, signal);
    },
    [scope, entityType, entityId, actorId, page],
    { enabled: scopeReady },
  );

  const resetPage = () => setPage(0);

  return (
    <>
      <PageHead title="Audit log" subtitle="Append-only record of every mutation" />

      <Card
        title="Recent activity"
        actions={
          <SelectField
            label="Window"
            options={WINDOW_OPTIONS}
            value={windowHours}
            onChange={(event) => setWindowHours(event.target.value)}
          />
        }
      >
        <DataState query={activity} skeletonRows={1} errorTitle="Could not count recent activity">
          {(data) => (
            <div className="stat-grid">
              <StatCard
                label="Events"
                value={data.events}
                note={`Since ${formatDateTime(data.since)}`}
              />
            </div>
          )}
        </DataState>
      </Card>

      <Card title="Scope">
        <div className="filters">
          <SelectField
            label="Look at"
            options={SCOPE_OPTIONS}
            value={scope}
            onChange={(event) => {
              setScope(event.target.value as Scope);
              resetPage();
            }}
          />
          {scope === 'entity' ? (
            <>
              <TextField
                label="Entity type"
                value={entityType}
                placeholder="Fee"
                hint="As recorded by the server, e.g. Fee, Room, Complaint."
                onChange={(event) => {
                  setEntityType(event.target.value);
                  resetPage();
                }}
              />
              <TextField
                label="Entity id"
                inputMode="numeric"
                value={entityId}
                error={entityId !== '' && !entityReady && entityType.trim() !== '' ? 'Positive number.' : undefined}
                onChange={(event) => {
                  setEntityId(event.target.value);
                  resetPage();
                }}
              />
            </>
          ) : scope === 'actor' ? (
            <TextField
              label="Actor id"
              inputMode="numeric"
              value={actorId}
              error={actorId !== '' && !actorReady ? 'Positive number.' : undefined}
              hint="The user account id, shown in the Actor column below."
              onChange={(event) => {
                setActorId(event.target.value);
                resetPage();
              }}
            />
          ) : null}
        </div>
      </Card>

      {!scopeReady ? (
        <Notice tone="info" title="Fill in the scope">
          {scope === 'entity'
            ? 'An entity type and a positive id are both needed before a lookup is made.'
            : 'An actor id is needed before a lookup is made.'}
        </Notice>
      ) : (
        <Card flush>
          <DataState query={events} skeletonRows={10} errorTitle="Could not load the audit log">
            {(data) =>
              data.content.length === 0 ? (
                <EmptyState title="Nothing recorded">
                  {scope === 'all'
                    ? 'No mutation has been audited yet.'
                    : 'Nothing in the trail matches that scope.'}
                </EmptyState>
              ) : (
                <>
                  <TableWrap>
                    <thead>
                      <tr>
                        <th>When</th>
                        <th className="shrink">Action</th>
                        <th>Entity</th>
                        <th>Actor</th>
                        <th>Change</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.content.map((event) => (
                        <tr key={event.id}>
                          <td className="nowrap small">{formatDateTime(event.createdAt)}</td>
                          <td className="shrink">
                            <Badge tone={ACTION_TONES[event.action]}>{humanise(event.action)}</Badge>
                          </td>
                          <td className="small">
                            <span className="cell-strong">{event.entityType}</span>
                            <span className="cell-sub mono">#{event.entityId}</span>
                          </td>
                          <td className="small">
                            {/* A null actor is a system action -- the scheduled jobs run
                                with no user attached. Rendering it as blank would read as
                                missing data rather than as "nobody clicked anything". */}
                            {event.actorName ?? <span className="faint">System</span>}
                            {event.actorId !== null ? (
                              <span className="cell-sub mono">#{event.actorId}</span>
                            ) : null}
                          </td>
                          <td>
                            <DiffCell payloadDiff={event.payloadDiff} />
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
                      busy={events.loading}
                    />
                  </div>
                </>
              )
            }
          </DataState>
        </Card>
      )}
    </>
  );
}

/**
 * The stored diff, verbatim, behind a disclosure.
 *
 * <p>Collapsed by default because a diff is several lines and the table is meant to be
 * scannable; expanded it shows the JSON exactly as stored. No key is hidden and none is
 * reformatted -- an audit reader needs to see what was written, not a tidied version of
 * it. Values that fail to serialise fall back to a plain note rather than throwing and
 * blanking the row.
 */
function DiffCell({ payloadDiff }: { payloadDiff: unknown }) {
  if (payloadDiff === null || payloadDiff === undefined) {
    return <span className="faint small">No diff recorded</span>;
  }

  let json: string;
  try {
    json = JSON.stringify(payloadDiff, null, 2);
  } catch {
    return <span className="faint small">Diff could not be displayed</span>;
  }
  if (json === undefined) {
    return <span className="faint small">No diff recorded</span>;
  }

  const keys =
    typeof payloadDiff === 'object' && !Array.isArray(payloadDiff)
      ? Object.keys(payloadDiff as Record<string, unknown>)
      : [];

  return (
    <details>
      <summary className="small">
        {keys.length > 0 ? `${plural(keys.length, 'field')}: ${keys.slice(0, 4).join(', ')}` : 'Show'}
      </summary>
      <pre className="mono small" style={{ whiteSpace: 'pre-wrap', margin: '0.5rem 0 0' }}>
        {json}
      </pre>
    </details>
  );
}
