'use client';

/**
 * One invoice, and every payment attempt against it.
 *
 * <p>This page exists because a fee's status is a summary of something, and a warden
 * fielding "I paid that" needs the something. `amountPaidPaise` says a number arrived;
 * the payment list says when, through which provider order, and -- when it did not
 * arrive -- why the gateway refused it.
 *
 * <p><b>It reconciles rather than trusting.</b> The sum of the SUCCEEDED payments
 * should equal `amountPaidPaise` exactly; both come from the same table on the server,
 * one aggregated and one enumerated. If they ever disagree, the invoice's balance is
 * wrong and no amount of UI polish makes that safe to hide, so the mismatch is shown
 * with both figures. This is a cheap consistency check on the one part of the system
 * where being off by a rupee matters, and it costs nothing when everything is fine.
 *
 * <p>Failed and pending attempts are listed alongside the successful ones on purpose.
 * A student who tried three times and failed twice looks, on the fees table, exactly
 * like a student who never tried -- and those two people need different conversations.
 */

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, formatMoney, formatPercent } from '@/lib/format';
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
import { ConfirmDialog } from '@/components/dialog';
import { FeeStatusBadge, PaymentStatusBadge } from '@/components/status-badges';

export default function WardenFeeDetailPage() {
  const routeParams = useParams();
  const rawId = Array.isArray(routeParams.id) ? routeParams.id[0] : routeParams.id;
  const feeId = Number(rawId);
  const validId = Number.isInteger(feeId) && feeId > 0;

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [cancelled, setCancelled] = useState(false);

  // `enabled` rather than an early return: hooks cannot be called conditionally, and a
  // route like /warden/fees/abc reaches this component with a NaN id. Gating the query
  // keeps the request from being sent at all while leaving the hook order intact.
  const fee = useQuery((signal) => warden.fees.byId(feeId, signal), [feeId], { enabled: validId });
  const payments = useQuery((signal) => warden.payments.forFee(feeId, signal), [feeId], {
    enabled: validId,
  });

  const cancel = useAction(() => warden.fees.cancel(feeId));

  if (!validId) {
    return (
      <>
        <PageHead title="Fee" crumb={<Link href="/warden/fees">Fees</Link>} />
        <Notice tone="error" title="That is not an invoice id">
          The address should end in a number, as in /warden/fees/12.
        </Notice>
      </>
    );
  }

  const succeededPaise = (payments.data ?? [])
    .filter((payment) => payment.status === 'SUCCEEDED')
    .reduce((total, payment) => total + payment.amountPaise, 0);
  // Both figures have to be real before they can be compared -- an empty payment list
  // against a loaded balance would report a mismatch on every first render.
  const recordedPaise = fee.data?.amountPaidPaise;
  const mismatch =
    recordedPaise !== undefined && payments.data !== undefined && succeededPaise !== recordedPaise;

  return (
    <>
      <PageHead
        title={fee.data?.title ?? 'Invoice'}
        crumb={<Link href="/warden/fees">Fees</Link>}
        subtitle={
          fee.data
            ? `${fee.data.student.fullName} · ${fee.data.academicYear} ${fee.data.semester}`
            : undefined
        }
        actions={
          fee.data && fee.data.status === 'UNPAID' && fee.data.amountPaidPaise === 0 ? (
            <Button variant="danger" onClick={() => setConfirmOpen(true)}>
              Cancel invoice
            </Button>
          ) : null
        }
      />

      {cancelled ? (
        <Notice tone="success" title="Invoice cancelled">
          It stays on the ledger as a cancelled row rather than disappearing, so the audit
          trail still shows it was raised.
        </Notice>
      ) : null}

      {mismatch ? (
        <Notice tone="error" title="The balance does not match the payments">
          This invoice records {formatMoney(recordedPaise)} paid, but the successful payments
          below add up to {formatMoney(succeededPaise)}. One of the two is wrong; do not settle
          anything against this invoice until it is reconciled.
        </Notice>
      ) : null}

      <DataState query={fee} skeletonRows={5} errorTitle="Could not load this invoice">
        {(data) => (
          <Card title="Invoice">
            <dl className="facts">
              <dt className="fact-label">Student</dt>
              <dd className="fact-value">
                <Link href={`/warden/students/${data.student.id}`}>{data.student.fullName}</Link>
                <span className="cell-sub mono">{data.student.rollNumber}</span>
              </dd>
              <dt className="fact-label">Term</dt>
              <dd className="fact-value">
                {data.academicYear}
                <span className="cell-sub">{data.semester}</span>
              </dd>
              <dt className="fact-label">Status</dt>
              <dd className="fact-value">
                <FeeStatusBadge status={data.status} overdue={data.overdue} />
              </dd>
              <dt className="fact-label">Due</dt>
              <dd className="fact-value">
                {formatDate(data.dueDate)}
                {data.overdue ? <span className="cell-sub">Past due</span> : null}
              </dd>
              <dt className="fact-label">Billed</dt>
              <dd className="fact-value nums">{formatMoney(data.amountPaise)}</dd>
              <dt className="fact-label">Received</dt>
              <dd className="fact-value nums">{formatMoney(data.amountPaidPaise)}</dd>
              <dt className="fact-label">Outstanding</dt>
              <dd className="fact-value nums">{formatMoney(data.outstandingPaise)}</dd>
              <dt className="fact-label">Collected</dt>
              <dd className="fact-value">
                {/* The percentage is computed from the two paise integers rather than
                    from rupees, and only for display. Nothing downstream reads it, so
                    a rounded figure here cannot become a balance. */}
                <span className="nums">{formatPercent(collectedPercent(data.amountPaidPaise, data.amountPaise))}</span>
                <span className="meter">
                  <span
                    className={data.overdue ? 'meter-fill meter-fill-danger' : 'meter-fill'}
                    style={{
                      width: `${Math.min(100, collectedPercent(data.amountPaidPaise, data.amountPaise))}%`,
                    }}
                  />
                </span>
              </dd>
            </dl>
            {data.description ? <p className="small muted">{data.description}</p> : null}
          </Card>
        )}
      </DataState>

      <Card flush title="Payment attempts" subtitle="Newest first, including the ones that failed">
        <DataState query={payments} skeletonRows={4} errorTitle="Could not load payments">
          {(rows) =>
            rows.length === 0 ? (
              <EmptyState title="No payment has been attempted">
                Students pay from their own fee page; nothing has been initiated against this
                invoice.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
                    <th>Started</th>
                    <th>Completed</th>
                    <th className="num">Amount</th>
                    <th>Provider</th>
                    <th>Order</th>
                    <th>Outcome</th>
                    <th className="shrink">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((payment) => (
                    <tr key={payment.id}>
                      <td className="nowrap small">{formatDateTime(payment.createdAt)}</td>
                      <td className="nowrap small">
                        {payment.completedAt === null ? (
                          <span className="faint">--</span>
                        ) : (
                          formatDateTime(payment.completedAt)
                        )}
                      </td>
                      <td className="num nums">{formatMoney(payment.amountPaise)}</td>
                      <td className="small">
                        {payment.provider}
                        <span className="cell-sub">{payment.currency}</span>
                      </td>
                      <td className="mono small">
                        {payment.providerOrderId ?? '--'}
                        {payment.providerPaymentId ? (
                          <span className="cell-sub mono">{payment.providerPaymentId}</span>
                        ) : null}
                      </td>
                      <td className="small">
                        {/* The gateway's own words. A generic "payment failed" would
                            hide the difference between a declined card and a bad
                            signature, which are a student problem and our problem. */}
                        {payment.failureReason ?? <span className="faint">--</span>}
                      </td>
                      <td className="shrink">
                        <PaymentStatusBadge status={payment.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </TableWrap>
            )
          }
        </DataState>
      </Card>

      {cancel.error ? (
        <ErrorNotice error={cancel.error} title="The invoice was not cancelled" />
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title="Cancel this invoice?"
        destructive
        confirmLabel="Cancel invoice"
        pending={cancel.running}
        error={cancel.error}
        body={
          <p>
            {fee.data?.title}, worth {formatMoney(fee.data?.amountPaise)}, will be marked
            cancelled. The server refuses this once any money has arrived, so a payment that
            lands between now and the click will stop it.
          </p>
        }
        onConfirm={async () => {
          const done = await cancel.run();
          if (done) {
            setConfirmOpen(false);
            setCancelled(true);
            fee.refetch();
            payments.refetch();
          }
        }}
        onCancel={() => setConfirmOpen(false)}
      />
    </>
  );
}

/**
 * How much of an invoice has arrived, as a percentage.
 *
 * <p>Guards the zero-amount case even though `@Positive` on `amountPaise` should make
 * it impossible: a `0 / 0` here would render `NaN%` and a `width: NaN%`, and a display
 * helper that trusts a validation annotation two services away is a poor bet.
 */
function collectedPercent(paidPaise: number, amountPaise: number): number {
  if (amountPaise <= 0) return 0;
  return (paidPaise / amountPaise) * 100;
}
