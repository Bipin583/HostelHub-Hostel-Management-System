'use client';

/**
 * Taking the register.
 *
 * <p>This screen is the reason the backend has a bulk endpoint, and it is built the
 * way that endpoint asks to be used: the whole roster is loaded in one request, marked
 * locally, and submitted as one transaction. Two consequences worth stating, because
 * both were deliberate.
 *
 * <p><b>The roster and the marks are two different requests.</b>
 * `GET /attendance/register` is a page over the attendance table -- it returns the
 * marks that exist, not the students who should be marked. So the roster comes from
 * `GET /students` and this page overlays the day's marks onto it. That is not a
 * missing endpoint: a student with no row for a date is the third state the whole
 * absence detector depends on ("nobody took the roll"), and a register endpoint that
 * invented ABSENT rows to fill the gaps would destroy it.
 *
 * <p><b>Nothing is sent until Save.</b> A per-row request would mean a browser closed
 * halfway through leaves a day where some students are marked and the rest are
 * indistinguishable from "not taken" -- the exact ambiguity `AttendanceBulkMarkRequest`
 * documents as its reason for existing. Local state, one submit.
 *
 * <p>Unmarked students are counted and shown before saving, because a partial register
 * is a legitimate thing to submit (a warden may only have taken one wing) but never an
 * accident worth hiding.
 */

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, plural, todayIso, yearLabel } from '@/lib/format';
import type { AttendanceBulkMarkResult, AttendanceRecord, AttendanceStatus } from '@/lib/types';
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  Skeleton,
  TableWrap,
  TextField,
} from '@/components/ui';

/**
 * One request for the whole hostel.
 *
 * <p>Matches `@Size(max = 1000)` on the bulk request: asking for more students than
 * can be submitted in one call would produce a roster this page cannot save. If a
 * deployment ever outgrows it, the honest fix is a paged register with a
 * per-page-is-partial warning, not a silently truncated roll call -- so the overflow
 * is reported rather than absorbed.
 */
const ROSTER_LIMIT = 1000;

export default function WardenAttendancePage() {
  const [date, setDate] = useState(todayIso());
  const [draft, setDraft] = useState<Record<number, AttendanceStatus>>({});
  const [result, setResult] = useState<AttendanceBulkMarkResult | null>(null);

  const roster = useQuery((signal) => warden.students.list({ size: ROSTER_LIMIT }, signal));
  const marks = useQuery(
    (signal) => warden.attendance.register(date, { size: ROSTER_LIMIT }, signal),
    [date],
  );

  /**
   * Seed the draft from the server's marks.
   *
   * <p>Filtered on `attendanceDate === date` rather than trusting `marks.data` to be
   * current: between changing the date and the new response landing, the hook still
   * holds the previous day's page, and seeding from it would show yesterday's marks as
   * though they were today's -- then save them under today's date.
   */
  useEffect(() => {
    const next: Record<number, AttendanceStatus> = {};
    for (const record of marks.data?.content ?? []) {
      if (record.attendanceDate === date) next[record.student.id] = record.status;
    }
    setDraft(next);
  }, [marks.data, date]);

  const existing = useMemo(() => {
    const byStudent = new Map<number, AttendanceRecord>();
    for (const record of marks.data?.content ?? []) {
      if (record.attendanceDate === date) byStudent.set(record.student.id, record);
    }
    return byStudent;
  }, [marks.data, date]);

  const students = roster.data?.content ?? [];
  const marked = students.filter((student) => draft[student.id] !== undefined).length;
  const unmarked = students.length - marked;
  const presentCount = students.filter((student) => draft[student.id] === 'PRESENT').length;
  const absentCount = marked - presentCount;
  const rosterOverflow = (roster.data?.totalElements ?? 0) > ROSTER_LIMIT;

  const save = useAction(() =>
    warden.attendance.markAll({
      attendanceDate: date,
      marks: students
        .filter((student) => draft[student.id] !== undefined)
        .map((student) => ({ studentId: student.id, status: draft[student.id] })),
    }),
  );

  const setAll = (status: AttendanceStatus) => {
    const next: Record<number, AttendanceStatus> = {};
    for (const student of students) next[student.id] = status;
    setDraft(next);
  };

  const loading = roster.data === undefined || marks.data === undefined;

  return (
    <>
      <PageHead
        title="Register"
        subtitle={`Roll call for ${formatDate(date)}`}
        actions={<Link href="/warden/absence-alerts">Absence alerts</Link>}
      />

      <Card>
        <div className="filters">
          <TextField
            label="Date"
            type="date"
            value={date}
            // The backend refuses a future date outright. `max` stops the picker from
            // offering one, so the rule is visible before it becomes a 400.
            max={todayIso()}
            hint="The register cannot be taken for a future date"
            onChange={(event) => {
              setDate(event.target.value);
              setResult(null);
              save.reset();
            }}
          />
          <div className="row-tight">
            <Button small onClick={() => setAll('PRESENT')} disabled={students.length === 0}>
              All present
            </Button>
            <Button small onClick={() => setAll('ABSENT')} disabled={students.length === 0}>
              All absent
            </Button>
            <Button small variant="ghost" onClick={() => setDraft({})} disabled={marked === 0}>
              Clear
            </Button>
          </div>
        </div>
      </Card>

      {rosterOverflow ? (
        <Notice tone="warning" title="More students than one submission holds">
          {roster.data?.totalElements} students are in scope and only {ROSTER_LIMIT} can be
          submitted at once. The rows below are the first {ROSTER_LIMIT}; saving will mark only
          those.
        </Notice>
      ) : null}

      {result ? (
        <Notice
          tone={result.skippedStudentIds.length > 0 ? 'warning' : 'success'}
          title={`Register saved for ${formatDate(result.attendanceDate)}`}
        >
          {plural(result.created, 'new mark')}, {plural(result.updated, 'correction')}
          {result.skippedStudentIds.length > 0
            ? ` · ${plural(result.skippedStudentIds.length, 'student')} skipped as out of scope`
            : ''}
          {result.created === 0 && result.updated === 0
            ? ' -- nothing had changed, so nothing was written.'
            : ''}
        </Notice>
      ) : null}

      {roster.error ? (
        <ErrorNotice error={roster.error} title="Could not load the roster" onRetry={roster.refetch} />
      ) : null}
      {marks.error ? (
        <ErrorNotice
          error={marks.error}
          title="Could not load the marks for this date"
          onRetry={marks.refetch}
        />
      ) : null}
      {save.error ? <ErrorNotice error={save.error} title="The register was not saved" /> : null}

      <Card
        flush
        title="Roll call"
        subtitle={
          loading
            ? undefined
            : `${marked} of ${students.length} marked · ${presentCount} present, ${absentCount} absent`
        }
        actions={
          <Button
            variant="primary"
            pending={save.running}
            disabled={marked === 0}
            onClick={async () => {
              const saved = await save.run();
              if (saved) {
                setResult(saved);
                marks.refetch();
              }
            }}
          >
            Save register
          </Button>
        }
        footer={
          unmarked > 0 && !loading ? (
            <span className="small muted">
              {plural(unmarked, 'student')} still unmarked. Saving now records only the{' '}
              {marked} you have marked; the rest stay as "not taken", which is not the same as
              absent.
            </span>
          ) : null
        }
      >
        {loading && !roster.error && !marks.error ? (
          <div className="card-body">
            <Skeleton rows={8} />
          </div>
        ) : students.length === 0 ? (
          <EmptyState title="No students in scope">
            There is nobody to mark for the hostels you govern.
          </EmptyState>
        ) : (
          <TableWrap>
            <thead>
              <tr>
                <th>Roll number</th>
                <th>Student</th>
                <th>Year</th>
                <th>Already recorded</th>
                <th className="shrink">Mark</th>
              </tr>
            </thead>
            <tbody>
              {students.map((student) => {
                const chosen = draft[student.id];
                const recorded = existing.get(student.id);
                return (
                  <tr key={student.id}>
                    <td className="mono">{student.rollNumber}</td>
                    <td>
                      <Link href={`/warden/students/${student.id}`} className="cell-strong">
                        {student.fullName}
                      </Link>
                    </td>
                    <td className="nums">{yearLabel(student.yearOfStudy)}</td>
                    <td className="small">
                      {recorded === undefined ? (
                        <span className="faint">Not taken</span>
                      ) : (
                        <>
                          {recorded.status === 'PRESENT' ? 'Present' : 'Absent'}
                          <span className="cell-sub">
                            {recorded.markedByName ?? 'Unknown'} ·{' '}
                            {formatDateTime(recorded.markedAt)}
                          </span>
                        </>
                      )}
                    </td>
                    <td className="shrink">
                      <span className="row-tight nowrap">
                        {/* `aria-pressed` rather than two radios: this is a pair of
                            toggles whose state must be announced, and a radio group per
                            row would need its own name and label to be read correctly. */}
                        <Button
                          small
                          variant={chosen === 'PRESENT' ? 'primary' : 'default'}
                          aria-pressed={chosen === 'PRESENT'}
                          onClick={() => setDraft({ ...draft, [student.id]: 'PRESENT' })}
                        >
                          Present
                        </Button>
                        <Button
                          small
                          variant={chosen === 'ABSENT' ? 'danger' : 'default'}
                          aria-pressed={chosen === 'ABSENT'}
                          onClick={() => setDraft({ ...draft, [student.id]: 'ABSENT' })}
                        >
                          Absent
                        </Button>
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </TableWrap>
        )}
      </Card>
    </>
  );
}
