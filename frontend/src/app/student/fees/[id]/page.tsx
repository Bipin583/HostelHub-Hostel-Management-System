'use client';

/**
 * One invoice, and the flow that pays it.
 *
 * <p>This is the only screen in the app that spends money, so the reasoning behind it is
 * worth more than the markup.
 *
 * <h3>The idempotency key is minted once per attempt, not once per click</h3>
 *
 * <p>`POST /student/payments` requires an `Idempotency-Key` header. The whole point of
 * that header is that retrying the same attempt cannot create a second order -- so the
 * key is generated when the student opens the pay panel and stored in state, and every
 * retry of that attempt sends the same one. Minting a fresh UUID inside the click
 * handler would satisfy the type and defeat the constraint: two clicks would be two
 * different attempts, and a student who double-clicked through a slow network would owe
 * two orders. Changing the amount starts a genuinely new attempt, so that -- and only
 * that -- mints a new key.
 *
 * <p>`alreadyInitiated` on the response is surfaced rather than hidden, because it is the
 * proof the mechanism worked: the second request returned the first request's order
 * instead of making one.
 *
 * <h3>Why the browser can sign the mock callback, and why that is dev-only</h3>
 *
 * <p>`POST /student/payments/callback` takes `{providerOrderId, providerPaymentId,
 * signature}` and verifies the signature as
 * `HMAC_SHA256(secret, orderId + "|" + paymentId)`, hex, lower case -- Razorpay's
 * scheme, which the mock gateway implements identically. In production the signature is
 * produced by the gateway and relayed by its checkout widget; the browser never holds
 * the secret and could not forge one.
 *
 * <p>There is no endpoint that hands a signature out. `MockPaymentGateway.signatureFor`
 * exists but is a Java method with no HTTP exposure -- deliberately, since an endpoint
 * that mints valid signatures on request is an endpoint that lets anyone mark any
 * invoice paid. That leaves this page with a choice: stop at initiation and never
 * exercise settlement, or compute the mock HMAC here.
 *
 * <p>It computes it, behind `NEXT_PUBLIC_MOCK_GATEWAY_SECRET`, which is unset by default.
 * With the variable absent the "complete payment" step does not render and the page says
 * why. Set it in `.env.local` against a development backend and the full path -- order,
 * signed callback, verification, ledger credit -- runs end to end. The secret is not in
 * this source file and must never be set in a deployed build: anything in
 * `NEXT_PUBLIC_*` is public, so setting it against a real gateway secret would publish
 * that secret to every visitor.
 *
 * <h3>Partial payments</h3>
 *
 * <p>The amount is editable up to the outstanding balance, because paying a term fee in
 * instalments is normal. Redelivering a partial callback used to double-credit the invoice
 * server-side; that was fixed on 2026-08-27 by locking the attempt row, and the reasoning
 * is in docs/concurrency.md §3. It was never something this page could or should have
 * papered over, and it is not something this page has to know about now either -- the
 * idempotency key below is the only half of the guarantee that lives in the client.
 */

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { student } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDate, formatMoney, paiseToRupeeInput, rupeeInputToPaise } from '@/lib/format';
import type { PaymentInitiation } from '@/lib/types';
import {
  Badge,
  Button,
  Card,
  DataState,
  ErrorNotice,
  Notice,
  PageHead,
  TextField,
} from '@/components/ui';
import { FeeStatusBadge } from '@/components/status-badges';

/**
 * Present only in development. `NEXT_PUBLIC_*` is inlined at build time and readable by
 * anyone, so this is a switch for exercising the mock gateway locally and nothing else.
 */
const MOCK_SECRET = process.env.NEXT_PUBLIC_MOCK_GATEWAY_SECRET ?? '';

export default function StudentFeeDetailPage() {
  const routeParams = useParams();
  const rawId = Array.isArray(routeParams.id) ? routeParams.id[0] : routeParams.id;
  const feeId = Number(rawId);
  const validId = Number.isInteger(feeId) && feeId > 0;

  const fee = useQuery((signal) => student.fees.byId(feeId, signal), [feeId], { enabled: validId });

  // Early return before any DataState: with `enabled: false` the query never resolves,
  // so `data` stays undefined forever and a DataState would spin a skeleton at a user
  // who typed a bad URL. The hooks above still run, so hook order is stable.
  if (!validId) {
    return (
      <>
        <PageHead title="Invoice" crumb={<Link href="/student/fees">Fees</Link>} />
        <Notice tone="error" title="That is not an invoice id">
          The address should end in a number, as in /student/fees/12.
        </Notice>
      </>
    );
  }

  return (
    <>
      <PageHead
        title={fee.data?.title ?? 'Invoice'}
        crumb={<Link href="/student/fees">Fees</Link>}
        subtitle={
          fee.data ? `${fee.data.academicYear} · ${fee.data.semester}` : undefined
        }
      />

      <DataState query={fee} skeletonRows={6} errorTitle="Could not load this invoice">
        {(detail) => (
          <>
            <Card title="The invoice">
              <dl className="facts">
                <dt className="fact-label">Amount</dt>
                <dd className="fact-value nums">{formatMoney(detail.amountPaise)}</dd>
                <dt className="fact-label">Paid</dt>
                <dd className="fact-value nums">{formatMoney(detail.amountPaidPaise)}</dd>
                <dt className="fact-label">Outstanding</dt>
                <dd className="fact-value nums">{formatMoney(detail.outstandingPaise)}</dd>
                <dt className="fact-label">Due</dt>
                <dd className="fact-value">
                  {formatDate(detail.dueDate)}
                  {detail.overdue ? (
                    <span className="cell-sub">
                      <Badge tone="danger">Past due</Badge>
                    </span>
                  ) : null}
                </dd>
                <dt className="fact-label">Status</dt>
                <dd className="fact-value">
                  <FeeStatusBadge status={detail.status} overdue={detail.overdue} />
                </dd>
              </dl>
              {detail.description ? <p className="small muted">{detail.description}</p> : null}
            </Card>

            {detail.status === 'CANCELLED' ? (
              <Notice tone="info" title="This invoice was cancelled">
                Nothing is owed on it. Any payment already credited stays on the record.
              </Notice>
            ) : detail.outstandingPaise === 0 ? (
              <Notice tone="success" title="Settled">
                The full amount has been credited against this invoice.
              </Notice>
            ) : (
              <PayPanel
                feeId={detail.id}
                outstandingPaise={detail.outstandingPaise}
                onSettled={() => fee.refetch()}
              />
            )}
          </>
        )}
      </DataState>
    </>
  );
}

function PayPanel({
  feeId,
  outstandingPaise,
  onSettled,
}: {
  feeId: number;
  outstandingPaise: number;
  onSettled: () => void;
}) {
  const [amountInput, setAmountInput] = useState(() => paiseToRupeeInput(outstandingPaise));
  const [attempt, setAttempt] = useState<{ key: string; amountPaise: number } | null>(null);
  const [initiation, setInitiation] = useState<PaymentInitiation | null>(null);
  const [settled, setSettled] = useState(false);

  const initiate = useAction((key: string, amountPaise: number) =>
    student.payments.initiate(feeId, amountPaise, key),
  );
  const confirm = useAction((order: PaymentInitiation) => completeMockPayment(order));

  const amountPaise = rupeeInputToPaise(amountInput);
  const amountError =
    amountPaise === null
      ? 'Enter an amount in rupees.'
      : amountPaise <= 0
        ? 'The amount has to be more than zero.'
        : amountPaise > outstandingPaise
          ? `That is more than the ${formatMoney(outstandingPaise)} outstanding.`
          : undefined;

  // A new amount is a new attempt, so the key it was minted for no longer applies and
  // the order it produced is stale. Dropping both here is what keeps "one key per
  // attempt" true rather than "one key per page load".
  const onAmountChange = (next: string) => {
    setAmountInput(next);
    setAttempt(null);
    setInitiation(null);
    setSettled(false);
    initiate.reset();
    confirm.reset();
  };

  const onInitiate = async () => {
    if (amountPaise === null || amountError !== undefined) return;
    // Reuse the existing key if this attempt has already been started -- that is the
    // retry path, and it is the whole reason the key lives in state.
    const active = attempt ?? { key: crypto.randomUUID(), amountPaise };
    setAttempt(active);
    const order = await initiate.run(active.key, active.amountPaise);
    if (order) setInitiation(order);
  };

  return (
    <Card
      title="Pay this invoice"
      subtitle={`Up to ${formatMoney(outstandingPaise)} outstanding`}
    >
      <div className="stack-sm">
        <TextField
          label="Amount to pay"
          inputMode="decimal"
          value={amountInput}
          error={amountInput === '' ? undefined : amountError}
          hint="In rupees. Part payments are allowed."
          onChange={(event) => onAmountChange(event.target.value)}
        />

        {initiation === null ? (
          <Button
            variant="primary"
            disabled={amountError !== undefined}
            pending={initiate.running}
            onClick={onInitiate}
          >
            Start payment
          </Button>
        ) : null}

        {initiate.error ? (
          <>
            <ErrorNotice error={initiate.error} title="The payment was not started" />
            <Button variant="default" pending={initiate.running} onClick={onInitiate}>
              Try again
            </Button>
            <p className="small faint">
              Retrying sends the same idempotency key, so it cannot create a second order.
            </p>
          </>
        ) : null}

        {initiation !== null ? (
          <>
            <Notice
              tone={initiation.alreadyInitiated ? 'info' : 'success'}
              title={initiation.alreadyInitiated ? 'This attempt was already open' : 'Order created'}
            >
              {initiation.alreadyInitiated
                ? 'The idempotency key matched an order that already existed, so the server returned that one instead of creating another.'
                : 'The gateway has an order for this amount.'}
            </Notice>

            <dl className="facts">
              <dt className="fact-label">Order</dt>
              <dd className="fact-value mono">{initiation.providerOrderId}</dd>
              <dt className="fact-label">Amount</dt>
              <dd className="fact-value nums">
                {formatMoney(initiation.amountPaise)} {initiation.currency}
              </dd>
              <dt className="fact-label">Provider</dt>
              <dd className="fact-value">{initiation.provider}</dd>
              <dt className="fact-label">Payment</dt>
              <dd className="fact-value nums">#{initiation.paymentId}</dd>
            </dl>

            {settled ? (
              <Notice tone="success" title="Payment credited">
                The callback was verified and the invoice has been updated.
              </Notice>
            ) : initiation.provider !== 'mock' ? (
              <Notice tone="info" title="Finish in the gateway">
                A real provider takes over from here and calls the portal back with a signed
                result. This page does not hold the signing secret and cannot complete the
                payment itself.
              </Notice>
            ) : MOCK_SECRET === '' ? (
              <Notice tone="info" title="Settlement needs the gateway">
                The order exists, but completing it needs a callback signed with the provider&rsquo;s
                secret -- which this page does not have, by design. To exercise the full flow
                locally, set <span className="mono">NEXT_PUBLIC_MOCK_GATEWAY_SECRET</span> to the
                development backend&rsquo;s mock secret and reload.
              </Notice>
            ) : (
              <>
                <Notice tone="warning" title="Standing in for the gateway">
                  This signs a mock callback in the browser, which is only possible because the
                  mock secret has been put in the environment for development. Never do this
                  against a real provider.
                </Notice>
                <Button
                  variant="primary"
                  pending={confirm.running}
                  onClick={async () => {
                    const payment = await confirm.run(initiation);
                    if (payment) {
                      setSettled(true);
                      onSettled();
                    }
                  }}
                >
                  Complete payment
                </Button>
              </>
            )}

            {confirm.error ? (
              <ErrorNotice error={confirm.error} title="The callback was not accepted" />
            ) : null}
          </>
        ) : null}
      </div>
    </Card>
  );
}

/**
 * Signs a mock callback and posts it, exactly as the gateway would.
 *
 * <p>The payload is `orderId + "|" + paymentId` and the digest is HMAC-SHA256 rendered as
 * lower-case hex, matching `Signatures.sign` on the server byte for byte. The payment id
 * is invented here because that is what a gateway does -- it is the provider's reference,
 * not ours, and the server only requires it to be present and consistent with the
 * signature.
 */
async function completeMockPayment(order: PaymentInitiation) {
  const providerPaymentId = `mock_pay_${randomHex(16)}`;
  const signature = await hmacSha256Hex(MOCK_SECRET, `${order.providerOrderId}|${providerPaymentId}`);
  return student.payments.callback({
    providerOrderId: order.providerOrderId,
    providerPaymentId,
    signature,
  });
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const digest = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

/** `crypto.getRandomValues` rather than `Math.random`, since this stands in for a provider reference. */
function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return Array.from(buffer)
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}
