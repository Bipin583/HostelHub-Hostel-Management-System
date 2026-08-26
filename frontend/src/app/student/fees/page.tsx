'use client';

/**
 * The student's ledger: what has been billed, and what has been paid against it.
 *
 * <p><b>Two tables because they are two different records.</b> Fees are what the hostel
 * says you owe; payments are what you have attempted. They are not one list -- a failed
 * payment is a real row in the payment history and no part of the fee ledger, and a
 * partly-paid invoice is one fee with several payments. Collapsing them would hide
 * exactly the case a student comes here to check: "I paid, why does it still say due?"
 *
 * <p><b>Fees come back whole; payments come back paged.</b> That asymmetry is the API's
 * and it is the right one -- a student has a handful of invoices and can have many
 * payment attempts against them -- so the fee table has no pager and the payment table
 * does.
 *
 * <p>Every amount is an integer count of paise from the server, formatted for display
 * only. Nothing on this page does arithmetic on rupees.
 */

import Link from 'next/link';
import { useState } from 'react';
import { student } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatDate, formatDateTime, formatMoney, plural } from '@/lib/format';
import {
  Badge,
  Button,
  Card,
  DataState,
  EmptyState,
  PageHead,
  Pager,
  StatCard,
  TableWrap,
} from '@/components/ui';
import { FeeStatusBadge, PaymentStatusBadge } from '@/components/status-badges';

export default function StudentFeesPage() {
  const [page, setPage] = useState(0);

  const fees = useQuery((signal) => student.fees.list(signal));
  const payments = useQuery((signal) => student.payments.list({ page, size: 10 }, signal), [page]);

  const all = fees.data ?? [];
  const owed = all.filter((fee) => fee.status !== 'CANCELLED' && fee.outstandingPaise > 0);
  const outstandingPaise = owed.reduce((total, fee) => total + fee.outstandingPaise, 0);
  const paidPaise = all.reduce((total, fee) => total + fee.amountPaidPaise, 0);
  const overdue = owed.filter((fee) => fee.overdue);

  return (
    <>
      <PageHead title="Fees" subtitle="Invoices raised against you, and your payments" />

      <div className="stat-grid">
        <StatCard
          label="Outstanding"
          value={fees.data === undefined ? '--' : formatMoney(outstandingPaise)}
          note={
            overdue.length > 0
              ? `${plural(overdue.length, 'invoice')} past due`
              : outstandingPaise === 0
                ? 'Nothing owed'
                : `Across ${plural(owed.length, 'invoice')}`
          }
        />
        <StatCard
          label="Paid to date"
          value={fees.data === undefined ? '--' : formatMoney(paidPaise)}
          note="Credited against your invoices"
        />
        <StatCard
          label="Invoices"
          value={fees.data === undefined ? '--' : all.length}
          note="Including settled and cancelled"
        />
      </div>

      <Card flush title="Invoices" subtitle="Soonest due first">
        <DataState query={fees} skeletonRows={5} errorTitle="Could not load your fees">
          {(rows) =>
            rows.length === 0 ? (
              <EmptyState title="No invoices">
                Nothing has been billed to you yet.
              </EmptyState>
            ) : (
              <TableWrap>
                <thead>
                  <tr>
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
                  {[...rows]
                    .sort((left, right) => left.dueDate.localeCompare(right.dueDate))
                    .map((fee) => (
                      <tr key={fee.id}>
                        <td>
                          <Link href={`/student/fees/${fee.id}`} className="cell-strong">
                            {fee.title}
                          </Link>
                          {fee.description ? (
                            <span className="cell-sub">{fee.description}</span>
                          ) : null}
                        </td>
                        <td className="small nowrap">
                          {fee.academicYear}
                          <span className="cell-sub">{fee.semester}</span>
                        </td>
                        <td className="num nums">{formatMoney(fee.amountPaise)}</td>
                        <td className="num nums">{formatMoney(fee.amountPaidPaise)}</td>
                        <td className="num nums">{formatMoney(fee.outstandingPaise)}</td>
                        <td className="nowrap">
                          {formatDate(fee.dueDate)}
                          {fee.overdue ? (
                            <span className="cell-sub">
                              <Badge tone="danger">Past due</Badge>
                            </span>
                          ) : null}
                        </td>
                        <td className="shrink">
                          <FeeStatusBadge status={fee.status} overdue={fee.overdue} />
                        </td>
                        <td className="shrink">
                          {/* Offered only where it can succeed. A cancelled invoice is
                              not payable and a settled one has nothing left to pay --
                              the server refuses both, so the button does not appear. */}
                          {fee.status !== 'CANCELLED' && fee.outstandingPaise > 0 ? (
                            <Link href={`/student/fees/${fee.id}`}>
                              <Button small variant="primary">
                                Pay
                              </Button>
                            </Link>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </TableWrap>
            )
          }
        </DataState>
      </Card>

      <Card flush title="Payment history" subtitle="Every attempt, including the ones that failed">
        <DataState query={payments} skeletonRows={5} errorTitle="Could not load your payments">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No payments yet">
                Payments you make through the portal appear here.
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>Invoice</th>
                      <th className="num">Amount</th>
                      <th>Reference</th>
                      <th>Completed</th>
                      <th className="shrink">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((payment) => (
                      <tr key={payment.id}>
                        <td className="nowrap small">{formatDateTime(payment.createdAt)}</td>
                        <td>
                          <Link href={`/student/fees/${payment.feeId}`}>#{payment.feeId}</Link>
                        </td>
                        <td className="num nums">{formatMoney(payment.amountPaise)}</td>
                        {/* The gateway's own payment id, which is the reference a student
                            quotes when something has to be chased with the provider.
                            Shown in full rather than truncated for exactly that reason.
                            Null until the gateway has issued one. */}
                        <td className="mono small">
                          {payment.providerPaymentId ?? (
                            <span className="faint">--</span>
                          )}
                        </td>
                        <td className="nowrap small">
                          {payment.completedAt === null ? (
                            <span className="faint">--</span>
                          ) : (
                            formatDateTime(payment.completedAt)
                          )}
                        </td>
                        <td className="shrink">
                          <PaymentStatusBadge status={payment.status} />
                          {payment.failureReason ? (
                            <span className="cell-sub">{payment.failureReason}</span>
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
                    busy={payments.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>
    </>
  );
}
