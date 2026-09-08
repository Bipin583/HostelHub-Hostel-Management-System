'use client';

/**
 * The fee ledger: what has been billed, what has arrived, and what is owed.
 *
 * <p>Three things on this page are worth defending.
 *
 * <p><b>Every amount stays in paise until it is rendered.</b> The table sums nothing;
 * the per-term breakdown comes from `GET /fees/collections`, which aggregates in SQL.
 * The one place a rupee figure is produced from a typed one is `rupeeInputToPaise` in
 * the create form, and it rounds rather than truncates -- `parseFloat('1234.56') * 100`
 * is `123455.99999999999`, and `Math.trunc` of that under-bills by a paisa.
 *
 * <p><b>Overdue is shown instead of the status, not beside it.</b> An unpaid invoice
 * past its due date is the row a warden is scanning for, and "Unpaid" alone does not
 * say that. See `FeeStatusBadge`.
 *
 * <p><b>Cancel is offered only on an untouched invoice.</b> The server refuses once
 * anything has been paid (`FEE_ALREADY_SETTLED`), because a cancelled invoice with a
 * payment against it is an unreconcilable ledger. The button follows that rule rather
 * than discovering it -- but the server is still the authority, and its refusal is
 * displayed if the state changed under us.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import {
  formatDate,
  formatFraction,
  formatMoney,
  rupeeInputToPaise,
  todayIso,
} from '@/lib/format';
import { FEE_STATUSES, type Fee, type FeeStatus } from '@/lib/types';
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
  TextField,
} from '@/components/ui';
import { ConfirmDialog, Dialog } from '@/components/dialog';
import { FeeStatusBadge } from '@/components/status-badges';

export default function WardenFeesPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<Fee | null>(null);

  const fees = useQuery(
    (signal) =>
      warden.fees.list(
        { page, size: 20, status: status ? (status as FeeStatus) : undefined },
        signal,
      ),
    [page, status],
  );
  const collections = useQuery((signal) => warden.fees.collections(signal));

  const cancel = useAction((feeId: number) => warden.fees.cancel(feeId));

  const afterWrite = () => {
    fees.refetch();
    collections.refetch();
  };

  return (
    <>
      <PageHead
        title="Fees"
        subtitle="Invoices raised against students in your scope"
        actions={
          <>
            <Link href="/warden/payments">Payments</Link>
            <Button variant="primary" onClick={() => setCreateOpen(true)}>
              Raise an invoice
            </Button>
          </>
        }
      />

      <DataState query={collections} skeletonRows={2} errorTitle="Could not load collections">
        {(data) => (
          <Card title="Collections by term" subtitle="Aggregated on the server, newest term first" flush>
            {data.terms.length === 0 ? (
              <EmptyState title="Nothing billed yet" />
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Term</th>
                    <th className="num">Invoices</th>
                    <th className="num">Paid</th>
                    <th className="num">Overdue</th>
                    <th className="num">Billed</th>
                    <th className="num">Collected</th>
                    <th className="num">Outstanding</th>
                    <th className="num">Rate</th>
                  </tr>
                </thead>
                <tbody>
                  {data.terms.map((term) => (
                    <tr key={`${term.academicYear}-${term.semester}`}>
                      <td className="cell-strong nowrap">
                        {term.academicYear}
                        <span className="cell-sub">{term.semester}</span>
                      </td>
                      <td className="num nums">{term.invoiceCount}</td>
                      <td className="num nums">{term.paidInvoiceCount}</td>
                      <td className="num nums">{term.overdueCount}</td>
                      <td className="num nums">{formatMoney(term.billedPaise)}</td>
                      <td className="num nums">{formatMoney(term.collectedPaise)}</td>
                      <td className="num nums">{formatMoney(term.outstandingPaise)}</td>
                      <td className="num nums">{formatFraction(term.collectionRate, 1)}</td>
                    </tr>
                  ))}
                  <tr>
                    <td className="cell-strong">All terms</td>
                    <td className="num nums">{data.totals.invoiceCount}</td>
                    <td className="num nums">{data.totals.paidInvoiceCount}</td>
                    <td className="num nums">{data.totals.overdueCount}</td>
                    <td className="num nums cell-strong">{formatMoney(data.totals.billedPaise)}</td>
                    <td className="num nums cell-strong">{formatMoney(data.totals.collectedPaise)}</td>
                    <td className="num nums cell-strong">
                      {formatMoney(data.totals.outstandingPaise)}
                    </td>
                    <td className="num nums cell-strong">
                      {formatFraction(data.totals.collectionRate, 1)}
                    </td>
                  </tr>
                </tbody>
              </TableWrap>
            )}
          </Card>
        )}
      </DataState>

      <Card>
        <div className="filters">
          <SelectField
            label="Status"
            placeholder="Any status"
            options={enumOptions(FEE_STATUSES)}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          />
        </div>
      </Card>

      <Card flush>
        <DataState query={fees} skeletonRows={8} errorTitle="Could not load fees">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No invoices match">
                {status ? 'Try a different status.' : 'Raise an invoice to get started.'}
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Student</th>
                      <th>Invoice</th>
                      <th>Term</th>
                      <th className="num">Amount</th>
                      <th className="num">Paid</th>
                      <th className="num">Outstanding</th>
                      <th>Due</th>
                      <th className="shrink">Status</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((fee) => (
                      <tr key={fee.id}>
                        <td>
                          <Link href={`/warden/students/${fee.student.id}`} className="cell-strong">
                            {fee.student.fullName}
                          </Link>
                          <span className="cell-sub mono">{fee.student.rollNumber}</span>
                        </td>
                        <td>
                          <Link href={`/warden/fees/${fee.id}`}>{fee.title}</Link>
                        </td>
                        <td className="nowrap small">
                          {fee.academicYear}
                          <span className="cell-sub">{fee.semester}</span>
                        </td>
                        <td className="num nums">{formatMoney(fee.amountPaise)}</td>
                        <td className="num nums">{formatMoney(fee.amountPaidPaise)}</td>
                        <td className="num nums">{formatMoney(fee.outstandingPaise)}</td>
                        <td className="nowrap">{formatDate(fee.dueDate)}</td>
                        <td className="shrink">
                          <FeeStatusBadge status={fee.status} overdue={fee.overdue} />
                        </td>
                        <td className="shrink">
                          {fee.status === 'UNPAID' && fee.amountPaidPaise === 0 ? (
                            <Button small variant="danger" onClick={() => setCancelTarget(fee)}>
                              Cancel
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
                    busy={fees.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>

      <CreateFeeDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          afterWrite();
        }}
      />

      <ConfirmDialog
        open={cancelTarget !== null}
        title="Cancel this invoice?"
        destructive
        confirmLabel="Cancel invoice"
        pending={cancel.running}
        error={cancel.error}
        body={
          <p>
            {cancelTarget?.title} for {cancelTarget?.student.fullName}, worth{' '}
            {formatMoney(cancelTarget?.amountPaise)}, will be marked cancelled. Nothing has been
            paid against it, so no payment is affected.
          </p>
        }
        onConfirm={async () => {
          if (cancelTarget === null) return;
          const done = await cancel.run(cancelTarget.id);
          if (done) {
            setCancelTarget(null);
            afterWrite();
          }
        }}
        onCancel={() => setCancelTarget(null)}
      />
    </>
  );
}

/**
 * Raising an invoice.
 *
 * <p>The student is chosen from a select rather than typed as an id, because an id is
 * not something a warden knows and a typo in one bills the wrong person. The roster is
 * loaded only when this dialog opens.
 *
 * <p>The term fields are free text on the server (`varchar(20)`, no enum) and are free
 * text here to match. Inventing a dropdown of semesters would put a constraint in the
 * UI that the database does not have, and it would be wrong the first time a hostel
 * bills something that is not per-semester.
 */
function CreateFeeDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [studentId, setStudentId] = useState('');
  const [title, setTitle] = useState('');
  const [academicYear, setAcademicYear] = useState(defaultAcademicYear());
  const [semester, setSemester] = useState('');
  const [amount, setAmount] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [description, setDescription] = useState('');

  const roster = useQuery((signal) => warden.students.list({ size: 1000 }, signal), [], {
    enabled: open,
  });

  const amountPaise = rupeeInputToPaise(amount);
  const create = useAction(() =>
    warden.fees.create({
      studentId: Number(studentId),
      title: title.trim(),
      academicYear: academicYear.trim(),
      semester: semester.trim(),
      amountPaise: amountPaise ?? 0,
      dueDate,
      description: description.trim() || null,
    }),
  );

  const amountInvalid = amount !== '' && (amountPaise === null || amountPaise <= 0);
  const ready =
    studentId !== '' &&
    title.trim() !== '' &&
    academicYear.trim() !== '' &&
    semester.trim() !== '' &&
    dueDate !== '' &&
    amountPaise !== null &&
    amountPaise > 0;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Raise an invoice"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.running}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={!ready}
            pending={create.running}
            onClick={async () => {
              const created = await create.run();
              if (created) {
                setTitle('');
                setSemester('');
                setAmount('');
                setDueDate('');
                setDescription('');
                setStudentId('');
                onCreated();
              }
            }}
          >
            Raise invoice
          </Button>
        </>
      }
    >
      <div className="stack-sm">
        <Notice tone="info">
          One invoice per student, term and title -- the database enforces it, so raising the
          same title twice for a term is rejected rather than duplicated.
        </Notice>

        <DataState query={roster} skeletonRows={2} errorTitle="Could not load the roster">
          {(page) => (
            <SelectField
              label="Student"
              placeholder="Select a student"
              value={studentId}
              onChange={(event) => setStudentId(event.target.value)}
              options={page.content.map((candidate) => ({
                value: String(candidate.id),
                label: `${candidate.fullName} · ${candidate.rollNumber}`,
              }))}
            />
          )}
        </DataState>

        <TextField
          label="Title"
          placeholder="Hostel fee"
          maxLength={200}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />

        <div className="form-grid">
          <TextField
            label="Academic year"
            placeholder="2026-27"
            maxLength={20}
            value={academicYear}
            onChange={(event) => setAcademicYear(event.target.value)}
          />
          <TextField
            label="Semester"
            placeholder="ODD"
            maxLength={20}
            value={semester}
            onChange={(event) => setSemester(event.target.value)}
          />
        </div>

        <div className="form-grid">
          <TextField
            label="Amount"
            inputMode="decimal"
            placeholder="45000"
            hint="Rupees. Stored as paise."
            error={amountInvalid ? 'Enter an amount like 45000 or 45000.50.' : undefined}
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
          />
          <TextField
            label="Due date"
            type="date"
            min={todayIso()}
            value={dueDate}
            onChange={(event) => setDueDate(event.target.value)}
          />
        </div>

        <TextAreaField
          label="Description (optional)"
          rows={2}
          maxLength={5000}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />

        {amountPaise !== null && amountPaise > 0 ? (
          <p className="small muted">
            Billing {formatMoney(amountPaise)} ({amountPaise} paise).
          </p>
        ) : null}

        {create.error ? <ErrorNotice error={create.error} title="The invoice was not raised" /> : null}
      </div>
    </Dialog>
  );
}

/**
 * The academic year that is currently running, as `2026-27`.
 *
 * <p>Rolls over in July rather than in January, because an Indian academic year does.
 * A default of the calendar year would be wrong for five months of every twelve, and
 * wrong in the direction that bills against a term that has ended.
 */
function defaultAcademicYear(): string {
  const now = new Date();
  const startYear = now.getMonth() >= 6 ? now.getFullYear() : now.getFullYear() - 1;
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, '0')}`;
}
