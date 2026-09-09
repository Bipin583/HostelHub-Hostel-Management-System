'use client';

/**
 * React's view of the session, and the route guards built on it.
 *
 * <p>There is no `SessionProvider` here, and that is a decision rather than an
 * omission. The session lives in module state in session.ts, and
 * `useSyncExternalStore` subscribes to it directly -- so a provider would be a
 * wrapper that supplies a value React can already reach, while adding a rule that
 * every future page must be mounted inside it or silently see nothing. Module state
 * is the right home for this particular thing because there is exactly one signed-in
 * user per tab, ever; a context would be modelling a variability that does not
 * exist.
 *
 * <p>`useSyncExternalStore` rather than `useState` plus an effect because it is the
 * hook that exists for this: it handles the server snapshot (see
 * `getServerSnapshot`) and it will not tear during a concurrent render, which a
 * hand-rolled subscribe-and-setState will.
 *
 * <h2>What the guards are and are not</h2>
 *
 * <p>These guards decide what to *render*. They are not security. Every endpoint is
 * gated server-side by `SecurityConfig` and, for wardens, narrowed again by hostel
 * scope inside the services -- a student who edits their JWT's role claim gets a
 * 401, and a warden who guesses another hostel's student id gets `OUT_OF_SCOPE`.
 * What the guards buy is that nobody is shown a page whose every request will fail,
 * and that a signed-out user lands on login instead of on a wall of errors.
 */

import { useEffect, useSyncExternalStore } from 'react';
import { useRouter } from 'next/navigation';
import {
  getServerSnapshot,
  getSnapshot,
  login as sessionLogin,
  logout as sessionLogout,
  restore,
  subscribe,
  type SessionSnapshot,
} from './session';
import type { Role } from './types';

/**
 * The current session, subscribed.
 *
 * <p>The `restore()` call lives in an effect here rather than at module load because
 * module load happens during Next's server render too, where there is no cookie jar
 * to consult and no point in a request. It is idempotent, so every component calling
 * this hook is fine -- the first one to mount pays for the single refresh.
 */
export function useSession(): SessionSnapshot {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  useEffect(() => {
    void restore();
  }, []);

  return snapshot;
}

/** The signed-in user, or null. Sugar over `useSession()` for the common read. */
export function useCurrentUser() {
  return useSession().user;
}

export { sessionLogin as login, sessionLogout as logout };

/**
 * Where a role belongs after signing in.
 *
 * <p>Mirrors the three prefixes `SecurityConfig` gates on, which is why there is no
 * shared landing page: an admin's home is the jobs console because that is the only
 * area `/api/v1/admin/**` serves, and a warden's is the dashboard. Sending everyone
 * to one page and hiding two thirds of it would mean a nav that lies about what is
 * reachable.
 */
export function homePathFor(role: Role | null | undefined): string {
  switch (role) {
    case 'ADMIN':
      return '/admin';
    case 'WARDEN':
      return '/warden';
    case 'STUDENT':
      return '/student';
    default:
      return '/login';
  }
}

/**
 * A guard's answer: what to render right now.
 *
 * <p>`checking` is deliberately distinct from `denied`. Until `restore()` resolves,
 * the session is `unknown` and the honest answer is "we do not know yet" -- treating
 * that as denied would redirect every returning user to login for the duration of
 * one request, and an App Router redirect is a real navigation the user sees.
 */
export interface GuardState {
  checking: boolean;
  allowed: boolean;
  user: SessionSnapshot['user'];
}

/**
 * Require one of `roles`, redirecting when that fails.
 *
 * <p>Anonymous goes to `/login` carrying a `next` parameter, so signing in returns
 * the user to the page they asked for rather than to a dashboard -- which matters
 * because the most common way to arrive here signed out is a bookmarked deep link
 * after a reload.
 *
 * <p>A *wrong* role is sent to its own home instead, and is not offered a login
 * screen: they are signed in, and the answer to "may a student open the warden
 * console" is no, not "try different credentials". `replace` rather than `push`
 * keeps the forbidden URL out of the back stack, so Back does not bounce between
 * the two.
 *
 * <p>The redirect runs in an effect because navigating during render is not allowed;
 * the caller renders nothing while `checking` or `!allowed`, so the offending page
 * never paints.
 */
export function useRequireRole(...roles: Role[]): GuardState {
  const { status, user } = useSession();
  const router = useRouter();

  const checking = status === 'unknown';
  const allowed = status === 'authenticated' && user !== null && roles.includes(user.role);

  useEffect(() => {
    if (checking || allowed) return;

    if (status === 'anonymous') {
      const here = `${window.location.pathname}${window.location.search}`;
      router.replace(`/login?next=${encodeURIComponent(here)}`);
      return;
    }
    // Signed in, wrong area.
    router.replace(homePathFor(user?.role));
  }, [checking, allowed, status, user?.role, router]);

  return { checking, allowed, user };
}
