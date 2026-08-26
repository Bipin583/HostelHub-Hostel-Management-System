'use client';

/**
 * The two hooks every page uses: one to read, one to write.
 *
 * <p>No React Query, no SWR. This app fetches from a REST API that returns whole
 * resources, and about two hundred lines of hooks cover what it needs -- the
 * loading/error/data triple, cancellation, and a re-run trigger. Pulling in a cache
 * library would add a normalisation model, a stale-time policy, and a devtools
 * panel to a project whose reviewers are being asked to read the code, not to
 * recognise a dependency. Where a real cache would earn its keep -- shared,
 * invalidated, cross-page state -- this app instead refetches, which for a warden's
 * console is both correct and cheap.
 *
 * <p>What is *not* skipped is cancellation. Every request gets an `AbortSignal`, and
 * a superseded or unmounted request is aborted rather than left to land on a dead
 * component. Without that, clicking through three students in a list can leave the
 * slowest response painting last -- a stale-render bug that looks like the server
 * returning the wrong record.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

export interface QueryResult<T> {
  data: T | undefined;
  error: unknown;
  /** True while a request is outstanding, including a refetch that has data already. */
  loading: boolean;
  /** True only for the first load, when there is nothing to show yet. */
  initialLoading: boolean;
  /** Re-run the fetcher. Safe to pass straight to `onClick`. */
  refetch: () => void;
}

export interface QueryOptions {
  /**
   * Skip the fetch entirely while false.
   *
   * <p>This is what keeps a detail page from requesting `/students/undefined` while
   * a route parameter or the session is still resolving. It stays in `initialLoading`
   * rather than reporting an error, because "not asked yet" is not a failure.
   */
  enabled?: boolean;
}

/**
 * Run `fetcher` whenever `deps` change, tracking its state.
 *
 * <p>Two implementation choices are worth naming.
 *
 * <p>First, `deps` is collapsed to a JSON key rather than spread into the effect's
 * dependency array. A spread array whose length differs between renders is a React
 * error, and call sites naturally want to pass a filter object whose fields come and
 * go. Serialising also means an object literal rebuilt every render -- `{ from, to }`
 * -- does not retrigger the fetch, which a raw identity comparison would.
 *
 * <p>Second, the fetcher is read through a ref. It is a new closure on every render,
 * so depending on it would refetch forever; the ref lets the effect always call the
 * current one while being triggered only by `deps`. That is the standard shape for
 * "latest callback, stable effect".
 */
export function useQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: readonly unknown[] = [],
  options: QueryOptions = {},
): QueryResult<T> {
  const enabled = options.enabled ?? true;
  const key = JSON.stringify(deps);

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(enabled);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!enabled) return;

    const controller = new AbortController();
    let live = true;

    setLoading(true);
    setError(null);

    fetcherRef
      .current(controller.signal)
      .then((result) => {
        if (!live) return;
        setData(result);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        // An abort is this hook's own doing -- a dependency changed or the component
        // went away. Reporting it would put "The user aborted a request" on screen
        // for what is, from the user's side, a successful navigation.
        if (!live || isAbort(cause)) return;
        setError(cause);
        setLoading(false);
      });

    return () => {
      live = false;
      controller.abort();
    };
    // `key` stands in for `deps`; see the note above.
  }, [key, enabled, reloadToken]);

  const refetch = useCallback(() => setReloadToken((token) => token + 1), []);

  return { data, error, loading, initialLoading: loading && data === undefined, refetch };
}

/**
 * A write, with its own pending and error state.
 *
 * <p>Kept separate from `useQuery` because a mutation is not a fetch that happens to
 * change something: it runs on a click rather than on render, it must not be
 * retried automatically, and its error belongs next to the button that caused it --
 * often as field-level messages from `ApiError.fieldErrors()`.
 *
 * <p>`run` resolves to the result on success and to `undefined` on failure, having
 * stored the error. That lets a call site write the happy path plainly:
 *
 * <pre>
 *   const created = await run();
 *   if (created) router.push(...);
 * </pre>
 *
 * without a try/catch in every handler, while `error` drives the UI. The rejection
 * is deliberately not re-thrown: an unhandled promise rejection in an event handler
 * is invisible in production, and this hook exists so the failure is visible.
 */
export interface ActionResult<Args extends unknown[], T> {
  run: (...args: Args) => Promise<T | undefined>;
  running: boolean;
  error: unknown;
  /** Clear the error -- e.g. when the user edits the field that caused it. */
  reset: () => void;
}

export function useAction<Args extends unknown[], T>(
  action: (...args: Args) => Promise<T>,
): ActionResult<Args, T> {
  const actionRef = useRef(action);
  actionRef.current = action;

  const [running, setRunning] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = useCallback(async (...args: Args): Promise<T | undefined> => {
    setRunning(true);
    setError(null);
    try {
      const result = await actionRef.current(...args);
      return result;
    } catch (cause) {
      // Guarded on `mounted` because a mutation often navigates away on success and
      // the failure of a *different*, still-running call must not set state on an
      // unmounted component.
      if (mounted.current) setError(cause);
      return undefined;
    } finally {
      if (mounted.current) setRunning(false);
    }
  }, []);

  const reset = useCallback(() => setError(null), []);

  return { run, running, error, reset };
}

function isAbort(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
