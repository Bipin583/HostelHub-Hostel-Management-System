'use client';

/**
 * The only page reachable while signed out.
 *
 * <p>Three things here are worth more than the form itself.
 *
 * <h2>The rate limit is a first-class state</h2>
 *
 * <p>`RateLimitingFilter` allows five attempts per fifteen minutes per (username,
 * IP) and returns 429 with a `Retry-After`. A login form that renders that as a red
 * "request failed" throws away the only useful part of the response, and users
 * respond by retrying -- which on a fixed window means they never get in. So the
 * countdown is live, the submit button stays disabled while it runs, and the header
 * is read from `Retry-After`, which the backend explicitly puts in
 * `exposedHeaders` so that this code can see it at all.
 *
 * <h2>`next` is validated before it is used</h2>
 *
 * <p>The guards send users here with `?next=/warden/fees`, and blindly navigating to
 * a caller-supplied URL is an open redirect -- `?next=https://evil.example` on a page
 * that just took a password is a credible phishing hop. `safeNext` accepts only a
 * same-site absolute path. That is a two-line check, and it is the kind of two lines
 * whose absence is a finding in a review.
 *
 * <h2>The dev hint is compiled out of production</h2>
 *
 * <p>`DevDataSeeder` creates known usernames under the `dev` profile, and having them
 * listed on screen makes this app demonstrable in one click. The block is behind
 * `NODE_ENV !== 'production'`, which the bundler evaluates statically, so it is
 * absent from a production build rather than merely hidden. No password is printed:
 * the seeder takes it from `DEV_SEED_PASSWORD` with no fallback, so only whoever
 * started the backend knows it.
 *
 * <h2>The right-hand panel is hidden, not stacked, on a phone</h2>
 *
 * <p>`BrandPanel` is the half that has to make this look like a product rather than a
 * form on a white page. It disappears entirely below 900px -- stacking it would push
 * the password field below the fold, and nobody scrolls past marketing copy to sign in.
 * Its three claims are limited to ones the code can be shown to honour.
 */

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ApiError } from '@/lib/api-error';
import { homePathFor, login, useSession } from '@/lib/auth';
import { Button, ErrorNotice, Notice, TextField } from '@/components/ui';
import { IconCheck, IconLogo } from '@/components/icons';

/**
 * `useSearchParams` opts a route into dynamic rendering unless it sits under a
 * Suspense boundary. The boundary is here rather than around the whole page so the
 * card and its heading are still prerendered as static HTML.
 */
export default function LoginPage() {
  return (
    <main className="auth-page">
      <div className="auth-form-side">
        <Suspense fallback={<div className="auth-card" aria-busy="true" />}>
          <LoginCard />
        </Suspense>
      </div>
      <BrandPanel />
    </main>
  );
}

function LoginCard() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { status, user } = useSession();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [retryIn, setRetryIn] = useState(0);

  const destination = safeNext(searchParams.get('next'));

  // Already signed in -- arrived by typing the URL, or by a second tab signing in.
  // Bounce rather than offering a second login, which would spend a rate-limit
  // attempt on a session that already exists.
  useEffect(() => {
    if (status === 'authenticated' && user) {
      router.replace(destination ?? homePathFor(user.role));
    }
  }, [status, user, destination, router]);

  // The countdown. One interval, cleared on unmount and when it reaches zero, so a
  // user who waits out a rate limit sees the button re-enable without reloading.
  useEffect(() => {
    if (retryIn <= 0) return;
    const timer = setInterval(() => setRetryIn((seconds) => Math.max(0, seconds - 1)), 1000);
    return () => clearInterval(timer);
  }, [retryIn]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (retryIn > 0) return;

    setSubmitting(true);
    setError(null);
    try {
      const signedIn = await login(username.trim(), password);
      router.replace(destination ?? homePathFor(signedIn.role));
    } catch (cause) {
      setError(cause);
      if (cause instanceof ApiError && cause.retryAfterSeconds !== null) {
        setRetryIn(cause.retryAfterSeconds);
      }
    } finally {
      setSubmitting(false);
    }
  }

  const rateLimited = retryIn > 0;

  return (
    <div className="auth-card">
      <div className="auth-head">
        <span className="auth-brand">
          <IconLogo size={22} />
          HostelOps
        </span>
        <h1 className="auth-title">Sign in</h1>
        <p className="auth-sub">
          Hostel operations for the whole campus &mdash; applications, beds, attendance and fees
          behind one account.
        </p>
      </div>

      <form className="auth-form" onSubmit={submit}>
        <TextField
          label="Username"
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          autoComplete="username"
          autoFocus
          required
          disabled={submitting}
        />

        <TextField
          label="Password"
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          required
          disabled={submitting}
        />

        {rateLimited ? (
          <Notice tone="warning" title="Too many attempts">
            The server is refusing further sign-ins for this account. Try again in {retryIn} second
            {retryIn === 1 ? '' : 's'}.
          </Notice>
        ) : error ? (
          // Not ErrorNotice's default title: on this page the failure is almost always
          // "wrong password", and "Something went wrong" implies a fault in the system.
          <ErrorNotice error={error} title={titleFor(error)} />
        ) : null}

        <Button type="submit" variant="primary" pending={submitting} disabled={rateLimited}>
          {rateLimited ? `Locked — ${retryIn}s` : 'Sign in'}
        </Button>
      </form>

      {process.env.NODE_ENV !== 'production' ? <DevAccountHint /> : null}

      <p className="auth-foot">Your session ends when you close the browser.</p>
    </div>
  );
}

/*
 * The right-hand half. Static, decorative, and hidden below 900px -- see `.auth-brand-side`.
 *
 * The three claims are the ones this repository can actually defend: the row lock, the
 * scoped queries, and the audit aspect. A pitch panel that promises something the code
 * does not do is worse than no panel, because the first buyer to look finds it out.
 */
function BrandPanel() {
  return (
    <aside className="auth-brand-side">
      <div className="auth-brand-inner">
        <p className="auth-pitch">One record of every bed, every payment, every absence.</p>
        <div className="auth-points">
          {PITCH.map((point) => (
            <div className="auth-point" key={point}>
              <span className="auth-point-mark" aria-hidden="true">
                <IconCheck size={13} />
              </span>
              <span>{point}</span>
            </div>
          ))}
        </div>
      </div>
    </aside>
  );
}

const PITCH = [
  'Allocation that cannot double-book a bed, enforced by the database rather than by a check in application code.',
  'A warden sees one hostel. Scoped at the query, not hidden in the interface.',
  'Every write recorded with actor, entity and a field-level diff, ready for an audit.',
];

/** A friendlier heading for the two failures a user can actually act on. */
function titleFor(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Could not reach the server';
  if (error.code === 'INVALID_CREDENTIALS') return 'Those details did not match';
  if (error.status >= 500) return 'The server had a problem';
  return 'Sign-in failed';
}

/**
 * Accept only a same-site absolute path.
 *
 * <p>`//evil.example` is the case worth naming: it is protocol-relative, so it passes
 * a naive `startsWith('/')` test and then navigates off-site. Anything with a scheme,
 * a host, or a backslash (which some browsers normalise to `/`) is rejected and the
 * user falls back to their role's home.
 */
function safeNext(candidate: string | null): string | null {
  if (!candidate) return null;
  if (!candidate.startsWith('/')) return null;
  if (candidate.startsWith('//') || candidate.startsWith('/\\')) return null;
  if (candidate.includes('\\')) return null;
  return candidate;
}

/**
 * Compiled out of production builds; see the note at the top of this file.
 *
 * <p>Styled with `.auth-hint` -- a dashed border and small faint text -- rather than as
 * an info notice. A notice looks like part of the product, and this is scaffolding.
 */
function DevAccountHint() {
  return (
    <div className="auth-hint">
      <strong>Development accounts.</strong> <code>admin</code>, <code>lh_warden</code>,{' '}
      <code>mh_warden</code>, and students such as <code>asha.rao</code> or{' '}
      <code>arjun.das</code>. All share the password the backend was started with in{' '}
      <code>DEV_SEED_PASSWORD</code>.
    </div>
  );
}
