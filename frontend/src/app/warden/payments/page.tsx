'use client';

/**
 * Every payment attempt in this warden's scope, newest first.
 *
 * <p>Read-only, and that is the whole design. A warden cannot create, retry, refund or
 * reconcile a payment from here, because none of those exist on the server: a payment
 * is created by the student initiating one and is only ever settled by the gateway
 * callback, whose signature is verified before a rupee is credited. A button here that
 * marked something paid would be a hole straight through that check.
 *
 * <p><b>There is deliberately no status filter.</b> `GET /warden/payments` takes only
 * page and size, so any filter on this screen could filter nothing but the twenty rows
 * currently loaded -- while looking, to the person using it, like a filter over every
 * payment. A control that quietly means something narrower than it says is worse than
 * no control; the honest version of this feature is a query parameter on the endpoint.
 *
 * <p>Pending rows are the ones worth reading. A payment that was initiated and never
 * completed is either a student who abandoned the form or a callback that never
 * arrived, and the second of those is an operational problem the fee page cannot show.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatDateTime, formatMoney, plural } from '@/lib/format';
import { Card, DataState, EmptyState, Notice, PageHead, Pager, TableWrap } from '@/components/ui';
import { PaymentStatusBadge } from '@/components/status-badges';

export default function WardenPaymentsPage() {
  const [page, setPage] = useState(0);

  const payments = useQuery(
    (signal) => warden.payments.list({ page, size: 20 }, signal),
    [page],
  );

  // Counted over the loaded page only, and said so in the notice. The alternative --
  // implying it is a total -- would be a number a warden could act on wrongly.
  const pendingOnPage = (payments.data?.content ?? []).filter(
    (payment) => payment.status === 'PENDING',
  ).length;

  return (
    <>
      <PageHead
        title="Payments"
        subtitle="Gateway attempts against hostel fees"
        actions={<Link href="/warden/fees">Fees</Link>}
      />

      {pendingOnPage > 0 ? (
        <Notice tone="warning" title="Attempts still open">
          {plural(pendingOnPage, 'payment')} on this page {pendingOnPage === 1 ? 'is' : 'are'}{' '}
          pending. A pending attempt has credited nothing -- it is either an abandoned form or a
          callback that never arrived.
        </Notice>
      ) : null}

      <Card flush>
        <DataState query={payments} skeletonRows={8} errorTitle="Could not load payments">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No payments yet">
                Nothing has been initiated against a fee in your hostels.
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>Completed</th>
                      <th>Student</th>
                      <th>Invoice</th>
                      <th className="num">Amount</th>
                      <th>Provider</th>
                      <th>Order</th>
                      <th>Outcome</th>
                      <th className="shrink">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((payment) => (
                      <tr key={payment.id}>
                        <td className="nowrap small">{formatDateTime(payment.createdAt)}</td>
                        <td className="nowrap small">
                          {payment.completedAt === null ? (
                            <span className="faint">--</span>
                          ) : (
                            formatDateTime(payment.completedAt)
                          )}
                        </td>
                        <td>
                          {/* The payment DTO carries ids, not a student summary -- so
                              this links out rather than pretending to a name it was
                              not given. Fetching one student per row to print a name
                              would be twenty requests for a column. */}
                          <Link href={`/warden/students/${payment.studentId}`}>
                            Student #{payment.studentId}
                          </Link>
                        </td>
                        <td>
                          <Link href={`/warden/fees/${payment.feeId}`}>Fee #{payment.feeId}</Link>
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
                          {payment.failureReason ?? <span className="faint">--</span>}
                        </td>
                        <td className="shrink">
                          <PaymentStatusBadge status={payment.status} />
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
