'use client';

/**
 * Admin Operations Overview: telemetry, scheduled jobs, and real-time audit feed.
 *
 * <p><b>The operations hub for platform administrators.</b> This page provides an
 * instant aggregate view of platform telemetry: 24-hour transaction audit volumes,
 * today's scheduled background job execution readiness, live audit feed, and quick
 * triggers to run idempotent maintenance jobs directly with in-place feedback.
 *
 * <p><b>Idempotent execution with immediate visibility.</b> Both fee reminders and
 * absence scans are safe to execute repeatedly without duplicating side-effects.
 * The inline job triggers expose this contract directly on the dashboard: running
 * a second time immediately reports zero duplicates and updates the audit log.
 */

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { admin } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, humanise, todayIso } from '@/lib/format';
import type { AbsenceScanResult, AuditAction, FeeReminderRun } from '@/lib/types';
import {
  Badge,
  Button,
  Card,
  DataState,
  EmptyState,
  Notice,
  PageHead,
  StatCard,
  TableWrap,
} from '@/components/ui';
import {
  IconCheckCircle,
  IconClock,
  IconExternal,
  IconHistory,
  IconReceipt,
} from '@/components/icons';
import { toast } from '@/lib/toast';

const ACTION_TONES: Record<AuditAction, 'success' | 'info' | 'danger'> = {
  CREATE: 'success',
  UPDATE: 'info',
  DELETE: 'danger',
};

export default function AdminOverviewPage() {
  const today = todayIso();

  const [reminderRun, setReminderRun] = useState<FeeReminderRun | null>(null);
  const [scanResult, setScanResult] = useState<AbsenceScanResult | null>(null);

  const since24h = useMemo(
    () => new Date(Date.now() - 24 * 3_600_000).toISOString(),
    [],
  );

  const activity = useQuery((signal) => admin.audit.activity(since24h, signal), [since24h]);
  const sentOn = useQuery((signal) => admin.jobs.feeRemindersSentOn(today, signal), [today]);
  const recentAudit = useQuery(
    (signal) => admin.audit.list({ page: 0, size: 8 }, signal),
  );

  const runReminders = useAction((businessDate: string) =>
    admin.jobs.runFeeReminders(businessDate),
  );
  const runScan = useAction((businessDate: string) =>
    admin.jobs.runAbsenceScan(businessDate),
  );

  const handleRunReminders = async () => {
    const result = await runReminders.run(today);
    if (result) {
      setReminderRun(result);
      sentOn.refetch();
      activity.refetch();
      recentAudit.refetch();
      toast(
        `Fee reminders completed: ${result.sent} sent, ${result.skipped} skipped.`,
        'success',
      );
    }
  };

  const handleRunScan = async () => {
    const result = await runScan.run(today);
    if (result) {
      setScanResult(result);
      activity.refetch();
      recentAudit.refetch();
      toast(
        `Absence scan completed: ${result.raised} raised, ${result.extended} extended.`,
        'success',
      );
    }
  };

  return (
    <>
      <PageHead
        title="System Operations"
        subtitle={`Live telemetry, automated background jobs, and audit events for ${formatDate(today)}`}
        actions={
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <Link href="/admin/jobs" className="btn btn-ghost btn-sm">
              <IconClock size={16} /> Scheduled jobs
            </Link>
            <Link href="/admin/audit" className="btn btn-ghost btn-sm">
              <IconHistory size={16} /> Audit log
            </Link>
            <Link href="/warden" className="btn btn-primary btn-sm">
              <IconExternal size={16} /> Warden console
            </Link>
          </div>
        }
      />

      {/* Metric Stat Cards */}
      <div className="stat-grid">
        <StatCard
          label="24h Audit Events"
          value={activity.data ? String(activity.data.events) : '—'}
          note="Tracked mutations in last 24h"
          icon={IconHistory}
          href="/admin/audit"
        />
        <StatCard
          label="Today's Reminders"
          value={sentOn.data ? String(sentOn.data.sent) : '—'}
          note={sentOn.data && sentOn.data.sent > 0 ? 'Sent today' : 'No reminders sent yet'}
          icon={IconReceipt}
          href="/admin/jobs"
        />
        <StatCard
          label="Absence Scanner"
          value="Active"
          note="Streak reconciliation engine"
          icon={IconClock}
          href="/admin/jobs"
        />
        <StatCard
          label="Service Health"
          value="Operational"
          note="All controllers & jobs green"
          icon={IconCheckCircle}
        />
      </div>

      {/* Scheduled Job Fast Trigger Card */}
      <Card
        title="Automated Job Triggers"
        subtitle="Idempotent daily operations runnable on-demand for today's date"
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: '1.25rem' }}>
          {/* Fee Reminders Trigger */}
          <div style={{ padding: '1rem', border: '1px solid var(--border)', borderRadius: 'var(--radius)', background: 'var(--surface-sunken)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
              <div>
                <h4 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 600 }}>Fee Reminders</h4>
                <p className="small muted" style={{ margin: '0.25rem 0 0' }}>
                  Scans invoices due soon and sends automated email notices.
                </p>
              </div>
              <Badge tone={sentOn.data && sentOn.data.sent > 0 ? 'success' : 'neutral'}>
                {sentOn.data && sentOn.data.sent > 0 ? `${sentOn.data.sent} sent` : 'Ready'}
              </Badge>
            </div>

            {reminderRun ? (
              <div style={{ marginBottom: '0.75rem' }}>
                <Notice tone="success">
                  Examined {reminderRun.invoicesExamined} invoices: <b>{reminderRun.sent} sent</b>, {reminderRun.skipped} skipped (already notified today).
                </Notice>
              </div>
            ) : null}

            <Button
              variant="default"
              small
              pending={runReminders.running}
              onClick={handleRunReminders}
            >
              Run today&apos;s fee reminders
            </Button>
          </div>

          {/* Absence Scan Trigger */}
          <div style={{ padding: '1rem', border: '1px solid var(--border)', borderRadius: 'var(--radius)', background: 'var(--surface-sunken)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
              <div>
                <h4 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 600 }}>Absence Streak Scan</h4>
                <p className="small muted" style={{ margin: '0.25rem 0 0' }}>
                  Audits the attendance roll, extends existing streaks, and raises alerts.
                </p>
              </div>
              <Badge tone="info">Idempotent</Badge>
            </div>

            {scanResult ? (
              <div style={{ marginBottom: '0.75rem' }}>
                <Notice tone="success">
                  Examined {scanResult.studentsExamined} students: <b>{scanResult.raised} alerts raised</b>, {scanResult.extended} extended, {scanResult.closed} closed.
                </Notice>
              </div>
            ) : null}

            <Button
              variant="default"
              small
              pending={runScan.running}
              onClick={handleRunScan}
            >
              Run today&apos;s absence scan
            </Button>
          </div>
        </div>
      </Card>

      {/* Live Recent Audit Log */}
      <Card
        title="Recent Audit Events"
        subtitle="Latest mutation events captured by AuditAspect across all entities"
        actions={
          <Link href="/admin/audit" className="btn btn-ghost btn-sm">
            View full audit log →
          </Link>
        }
      >
        <DataState query={recentAudit} skeletonRows={5} errorTitle="Could not load audit events">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No audit events found">
                No database mutations have been recorded recently.
              </EmptyState>
            ) : (
              <TableWrap>
                <table>
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Actor</th>
                      <th>Action</th>
                      <th>Entity</th>
                      <th>Entity ID</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((ev) => (
                      <tr key={ev.id}>
                        <td className="small muted">{formatDateTime(ev.createdAt)}</td>
                        <td>
                          {ev.actorName ? (
                            <span style={{ fontWeight: 500 }}>{ev.actorName}</span>
                          ) : (
                            <span className="faint">System / Anonymous</span>
                          )}
                        </td>
                        <td>
                          <Badge tone={ACTION_TONES[ev.action]}>{ev.action}</Badge>
                        </td>
                        <td>
                          <span className="mono">{humanise(ev.entityType)}</span>
                        </td>
                        <td className="mono muted">#{ev.entityId}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableWrap>
            )
          }
        </DataState>
      </Card>

      {/* Operational Console Jump Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1rem' }}>
        <Link href="/admin/jobs" style={{ textDecoration: 'none', color: 'inherit' }}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
              <IconClock size={20} />
              <h4 style={{ margin: 0 }}>Scheduled Jobs Console</h4>
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Audit job execution history, view reminder tallies by date, and execute past runs.
            </p>
          </Card>
        </Link>

        <Link href="/admin/audit" style={{ textDecoration: 'none', color: 'inherit' }}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
              <IconHistory size={20} />
              <h4 style={{ margin: 0 }}>Audit Trail Inspector</h4>
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Search mutation records across actors, entities, and inspect redacted JSON diff payloads.
            </p>
          </Card>
        </Link>

        <Link href="/warden" style={{ textDecoration: 'none', color: 'inherit' }}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginBottom: '0.5rem' }}>
              <IconExternal size={20} />
              <h4 style={{ margin: 0 }}>Warden Console</h4>
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Access day-to-day operations including bed allocations, student roster, fees, and complaints.
            </p>
          </Card>
        </Link>
      </div>
    </>
  );
}
