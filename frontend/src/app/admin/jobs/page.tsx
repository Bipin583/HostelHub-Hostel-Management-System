'use client';

/**
 * The two scheduled jobs, runnable by hand.
 *
 * <p><b>Why an operator can trigger a scheduled job at all.</b> Both of these run on a
 * timer in production. They are exposed here because timers fail: the process was down at
 * 02:00, a deploy landed mid-run, someone needs yesterday's reminders sent today. Without
 * a manual trigger the recovery path is a database script, which is worse in every way
 * than an audited endpoint.
 *
 * <p><b>Both jobs are idempotent, and this page is built to show that rather than assert
 * it.</b> Fee reminders record what they sent per invoice per day, so a second run on the
 * same date reports its work as `skipped` instead of sending a duplicate -- run it twice
 * and the second result proves the guard exists. The absence scan reconciles against the
 * register: it raises new alerts, extends the ones whose streak grew, and closes the ones
 * whose streak ended, so re-running it converges rather than accumulating.
 *
 * <p>That is the whole reason the counters are shown broken out. A job that answered "ok"
 * would leave an operator with no way to tell "nothing needed doing" from "nothing was
 * done".
 *
 * <p><b>The date is a parameter, not "now".</b> Both endpoints take the business date, so
 * a run can be repeated for a day that was missed. Future dates are refused by the input's
 * `max` and by the server.
 */

import { useState } from 'react';
import { admin } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDate, plural, todayIso } from '@/lib/format';
import type { AbsenceScanResult, FeeReminderRun } from '@/lib/types';
import {
  Button,
  Card,
  DataState,
  ErrorNotice,
  Notice,
  PageHead,
  StatCard,
  TextField,
} from '@/components/ui';

export default function AdminJobsPage() {
  const [date, setDate] = useState(todayIso());

  const [reminderRun, setReminderRun] = useState<FeeReminderRun | null>(null);
  const [scanResult, setScanResult] = useState<AbsenceScanResult | null>(null);

  const sentOn = useQuery((signal) => admin.jobs.feeRemindersSentOn(date, signal), [date]);

  const runReminders = useAction((businessDate: string) =>
    admin.jobs.runFeeReminders(businessDate),
  );
  const runScan = useAction((businessDate: string) => admin.jobs.runAbsenceScan(businessDate));

  const futureDate = date > todayIso();

  return (
    <>
      <PageHead
        title="Scheduled jobs"
        subtitle="Run a job by hand for a given business date"
      />

      <Card title="Business date" subtitle="Both jobs act on this date, not on the wall clock">
        <div className="filters">
          <TextField
            label="Date"
            type="date"
            max={todayIso()}
            value={date}
            error={futureDate ? 'The date cannot be in the future.' : undefined}
            onChange={(event) => {
              setDate(event.target.value);
              // The previous run's counters describe the previous date. Keeping them on
              // screen next to a new date is how an operator ends up reading Tuesday's
              // result as Wednesday's.
              setReminderRun(null);
              setScanResult(null);
              runReminders.reset();
              runScan.reset();
            }}
          />
        </div>
      </Card>

      <Card
        title="Fee reminders"
        subtitle="Emails residents with an unpaid invoice falling due"
        actions={
          <Button
            variant="primary"
            disabled={futureDate}
            pending={runReminders.running}
            onClick={async () => {
              const result = await runReminders.run(date);
              if (result) {
                setReminderRun(result);
                sentOn.refetch();
              }
            }}
          >
            Run for {formatDate(date)}
          </Button>
        }
      >
        <div className="stack-sm">
          <DataState query={sentOn} skeletonRows={1} errorTitle="Could not read the day's tally">
            {(tally) => (
              <Notice tone={tally.sent === 0 ? 'info' : 'success'} title="Already sent on this date">
                {tally.sent === 0
                  ? 'No reminder has gone out for this date yet.'
                  : `${plural(tally.sent, 'reminder')} recorded as sent.`}
              </Notice>
            )}
          </DataState>

          {runReminders.error ? (
            <ErrorNotice error={runReminders.error} title="The job did not run" />
          ) : null}

          {reminderRun !== null ? (
            <>
              <div className="stat-grid">
                <StatCard
                  label="Invoices examined"
                  value={reminderRun.invoicesExamined}
                  note={`Due on ${formatDate(reminderRun.reminderDate)}`}
                />
                <StatCard label="Sent" value={reminderRun.sent} note="New reminders dispatched" />
                <StatCard
                  label="Skipped"
                  value={reminderRun.skipped}
                  note="Already reminded on this date"
                />
                <StatCard
                  label="Failed"
                  value={reminderRun.failed}
                  note={reminderRun.failed === 0 ? 'None' : 'Check the mail transport'}
                />
              </div>
              {reminderRun.sent === 0 && reminderRun.skipped > 0 ? (
                <Notice tone="success" title="The guard held">
                  Every candidate had already been reminded for this date, so nothing was sent
                  again. Running this job twice does not email anybody twice.
                </Notice>
              ) : null}
              {reminderRun.failed > 0 ? (
                <Notice tone="warning" title="Some reminders failed">
                  A failure is not recorded as sent, so re-running picks those up and leaves the
                  successful ones alone.
                </Notice>
              ) : null}
            </>
          ) : null}
        </div>
      </Card>

      <Card
        title="Absence scan"
        subtitle="Raises, extends and closes absence alerts from the register"
        actions={
          <Button
            variant="primary"
            disabled={futureDate}
            pending={runScan.running}
            onClick={async () => {
              const result = await runScan.run(date);
              if (result) setScanResult(result);
            }}
          >
            Run for {formatDate(date)}
          </Button>
        }
      >
        <div className="stack-sm">
          {runScan.error ? <ErrorNotice error={runScan.error} title="The scan did not run" /> : null}

          {scanResult === null ? (
            <p className="small muted">
              The scan reads the attendance register up to the chosen date and reconciles alerts
              against it. It is safe to run repeatedly -- a streak that has not changed produces no
              new alert.
            </p>
          ) : (
            <>
              <div className="stat-grid">
                <StatCard
                  label="Students examined"
                  value={scanResult.studentsExamined}
                  note={`As at ${formatDate(scanResult.scanDate)}`}
                />
                <StatCard label="Alerts raised" value={scanResult.raised} note="New streaks" />
                <StatCard
                  label="Alerts extended"
                  value={scanResult.extended}
                  note="Streak grew; existing alert updated"
                />
                <StatCard
                  label="Alerts closed"
                  value={scanResult.closed}
                  note="Student came back"
                />
              </div>
              {scanResult.raised === 0 && scanResult.extended === 0 && scanResult.closed === 0 ? (
                <Notice tone="info" title="Nothing changed">
                  The scan examined {plural(scanResult.studentsExamined, 'student')} and found the
                  alert state already correct -- which is what a second run on the same day should
                  report.
                </Notice>
              ) : null}
            </>
          )}
        </div>
      </Card>
    </>
  );
}
