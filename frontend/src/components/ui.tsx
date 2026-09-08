'use client';

/**
 * The shared vocabulary of the interface: buttons, cards, fields, badges, tables,
 * and the two wrappers that stop every page from re-implementing "loading, failed,
 * or empty".
 *
 * <p>These are thin on purpose. Each one is a class name from globals.css plus the
 * accessibility wiring that is easy to forget and invisible when missing -- the
 * `aria-describedby` linking a field to its error, `aria-current` on the active nav
 * link, `aria-busy` on a pending button. That wiring is the actual reason these
 * exist as components rather than as bare JSX with a className.
 */

import Link from 'next/link';
import {
  useId,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { ApiError, messageOf } from '@/lib/api-error';
import { humanise } from '@/lib/format';
import type { QueryResult } from '@/lib/use-query';
import {
  IconAlertCircle,
  IconCheckCircle,
  IconChevronLeft,
  IconChevronRight,
  IconInboxEmpty,
  IconInfo,
  IconSearch,
  IconTrendDown,
  IconTrendUp,
  type IconProps,
} from './icons';

/**
 * The shape a caller passes when it wants a glyph on a component.
 *
 * <p>A component reference, not an element: the receiving component decides the size,
 * so a card icon and a nav icon stay at their own scales without every call site
 * repeating a number.
 */
export type IconComponent = (props: IconProps) => ReactNode;

// ----------------------------------------------------------------- buttons

type ButtonVariant = 'primary' | 'default' | 'danger' | 'ghost';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  small?: boolean;
  /** Shows a spinner and blocks the click, without changing the label. */
  pending?: boolean;
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'btn btn-primary',
  default: 'btn',
  danger: 'btn btn-danger',
  ghost: 'btn btn-ghost',
};

/**
 * A button that cannot be double-submitted while pending.
 *
 * <p>`disabled` while pending is not cosmetic: several of this app's writes are not
 * idempotent from the user's side -- approving an application twice is a 409, and a
 * second payment initiation without the same key would open a second attempt. The
 * server defends itself in every case, but the first line of defence is not firing
 * the second request at all.
 */
export function Button({ variant = 'default', small, pending, children, ...rest }: ButtonProps) {
  const className = [VARIANT_CLASS[variant], small ? 'btn-sm' : '', rest.className ?? '']
    .filter(Boolean)
    .join(' ');
  return (
    <button {...rest} className={className} disabled={rest.disabled || pending} aria-busy={pending}>
      {pending ? <span className="spinner" aria-hidden="true" /> : null}
      {children}
    </button>
  );
}

// -------------------------------------------------------------------- cards

export function Card({
  title,
  subtitle,
  actions,
  flush,
  icon,
  children,
  footer,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  /** For a card whose body is a table -- the table draws its own edges. */
  flush?: boolean;
  /**
   * A tinted glyph tile beside the title.
   *
   * <p>Optional and purely decorative -- the heading is always the accessible name.
   * It earns its place on a dashboard of four stacked cards, where it is the fastest
   * way to tell them apart without reading, and is worth skipping on a page with one.
   */
  icon?: IconComponent;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const Icon = icon;
  return (
    <section className={flush ? 'card card-flush' : 'card'}>
      {title !== undefined ? (
        <header className="card-head">
          {Icon ? (
            <span className="card-icon" aria-hidden="true">
              <Icon size={16} />
            </span>
          ) : null}
          <div className="card-head-text">
            <h2>{title}</h2>
            {subtitle ? <p className="page-sub">{subtitle}</p> : null}
          </div>
          {actions ? <div className="row-tight card-actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className="card-body">{children}</div>
      {footer ? <div className="card-foot">{footer}</div> : null}
    </section>
  );
}

export function PageHead({
  title,
  subtitle,
  actions,
  crumb,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  crumb?: ReactNode;
}) {
  return (
    <div className="page-head">
      <div>
        {crumb ? <div className="crumb">{crumb}</div> : null}
        <h1>{title}</h1>
        {subtitle ? <p className="page-sub">{subtitle}</p> : null}
      </div>
      {actions ? <div className="row-tight">{actions}</div> : null}
    </div>
  );
}

/**
 * A change against a previous period, shown beside a figure.
 *
 * <p>`direction` is separate from the sign of the number on purpose: a 12% rise in
 * unpaid fees is bad news, and a component that colours every increase green would say
 * the opposite of what the number means. The caller knows which way is up for its own
 * metric; this only draws it.
 */
export interface StatTrend {
  label: string;
  direction: 'up' | 'down' | 'flat';
  /** Whether this direction is good news, for the colour. Defaults to neutral grey. */
  tone?: 'good' | 'bad';
}

export function StatCard({
  label,
  value,
  note,
  href,
  icon,
  trend,
}: {
  label: ReactNode;
  value: ReactNode;
  note?: ReactNode;
  href?: string;
  icon?: IconComponent;
  trend?: StatTrend;
}) {
  const Icon = icon;
  const inner = (
    <>
      <span className="stat-head">
        <span className="stat-label">{label}</span>
        {Icon ? (
          <span className="stat-icon" aria-hidden="true">
            <Icon size={15} />
          </span>
        ) : null}
      </span>
      <span className="stat-value">{value}</span>
      <span className="row-tight">
        {trend ? (
          <span className={`trend ${trendClass(trend)}`}>
            {trend.direction === 'up' ? (
              <IconTrendUp size={13} />
            ) : trend.direction === 'down' ? (
              <IconTrendDown size={13} />
            ) : null}
            {trend.label}
          </span>
        ) : null}
        {note ? <span className="stat-note">{note}</span> : null}
      </span>
    </>
  );
  // An anchor when it leads somewhere, a div when it does not: a clickable-looking
  // tile that does nothing is worse than a plain one.
  //
  // `Link`, not a bare `<a>`. A full document load would discard the access token,
  // which lives in a module variable by design, and every tile click would pay for a
  // silent refresh round trip before the next screen could ask for anything.
  return href ? (
    <Link className="stat" href={href}>
      {inner}
    </Link>
  ) : (
    <div className="stat">{inner}</div>
  );
}

function trendClass(trend: StatTrend): string {
  if (trend.tone === 'good') return 'trend-up';
  if (trend.tone === 'bad') return 'trend-down';
  return 'trend-flat';
}

// ------------------------------------------------------------------- badges

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'accent';

export function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

// -------------------------------------------------------------------- forms

/**
 * Label, hint, and error around an arbitrary control.
 *
 * <p>The caller owns the control and its `id`, because the alternative -- cloning
 * the child to inject props -- breaks the moment the child is a fragment or a custom
 * component, and fails silently when it does. `describedBy` is returned as a render
 * argument so the control can point at whichever of hint/error is present.
 */
export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: ReactNode;
  htmlFor: string;
  hint?: ReactNode;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label className="field-label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {hint && !error ? (
        <span className="field-hint" id={`${htmlFor}-hint`}>
          {hint}
        </span>
      ) : null}
      {error ? (
        <span className="field-error" id={`${htmlFor}-error`} role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}

interface FieldShell {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
}

/** A labelled text input. `error` turns on `aria-invalid` as well as the message. */
export function TextField({
  label,
  hint,
  error,
  ...rest
}: FieldShell & InputHTMLAttributes<HTMLInputElement>) {
  // `useId` is called unconditionally and only *used* as a fallback -- putting it on
  // the right of `??` would make it a conditional hook, which is a rules-of-hooks
  // violation that happens to work until a caller starts passing an explicit id.
  const generatedId = useId();
  const id = rest.id ?? generatedId;
  return (
    <Field label={label} htmlFor={id} hint={hint} error={error}>
      <input
        {...rest}
        id={id}
        className={`input ${rest.className ?? ''}`.trim()}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
      />
    </Field>
  );
}

export function TextAreaField({
  label,
  hint,
  error,
  ...rest
}: FieldShell & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const generatedId = useId();
  const id = rest.id ?? generatedId;
  return (
    <Field label={label} htmlFor={id} hint={hint} error={error}>
      <textarea
        {...rest}
        id={id}
        className={`textarea ${rest.className ?? ''}`.trim()}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
      />
    </Field>
  );
}

export interface Option {
  value: string;
  label: string;
}

/**
 * Turn an enum's values into select options, labelled by `humanise`.
 *
 * <p>Driven by the arrays exported from types.ts, which are transcribed from the
 * server's enums -- so a filter dropdown cannot drift from the values the endpoint
 * accepts, and adding a status server-side is a one-line change here rather than a
 * hunt through pages for hard-coded `<option>` lists.
 */
export function enumOptions(values: readonly string[]): Option[] {
  return values.map((value) => ({ value, label: humanise(value) }));
}

/**
 * A labelled select.
 *
 * <p>`placeholder` becomes an option with an empty value, which is how every
 * optional filter in this app expresses "no filter". The empty string is then
 * dropped by `toSearchParams` rather than sent -- Spring would reject an empty
 * `status` as a 400, not read it as "all".
 */
export function SelectField({
  label,
  hint,
  error,
  options,
  placeholder,
  ...rest
}: FieldShell & { options: readonly Option[]; placeholder?: string } & SelectHTMLAttributes<HTMLSelectElement>) {
  const generatedId = useId();
  const id = rest.id ?? generatedId;
  return (
    <Field label={label} htmlFor={id} hint={hint} error={error}>
      <select
        {...rest}
        id={id}
        className={`select ${rest.className ?? ''}`.trim()}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
      >
        {placeholder !== undefined ? <option value="">{placeholder}</option> : null}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  );
}

// ------------------------------------------------------------------ notices

/**
 * Render whatever went wrong, using everything the server said about it.
 *
 * <p>The message comes from the backend, because `GlobalExceptionHandler` writes
 * better prose about its own state than this component could invent ("Room LH-A-101
 * is full" beats "Could not allocate"). Three extras are added on top:
 *
 * <ul>
 *   <li>the `Retry-After` countdown on a 429, which is the only actionable thing
 *       about a rate limit;</li>
 *   <li>the `traceId`, small and monospaced, so a report can be tied to a log line
 *       -- the backend generates one for exactly this purpose;</li>
 *   <li>a Retry button when the caller supplies `onRetry`, because a transient 500
 *       or a dropped connection is usually fixed by asking again.</li>
 * </ul>
 */
export function ErrorNotice({
  error,
  title = 'Something went wrong',
  onRetry,
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
}) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const retryAfter = apiError?.retryAfterSeconds ?? null;

  return (
    <div className="notice notice-error" role="alert">
      <span className="notice-icon" aria-hidden="true">
        <IconAlertCircle size={17} />
      </span>
      <div className="notice-body">
        <span className="notice-title">{title}</span>
        <span>{messageOf(error)}</span>
        {retryAfter !== null ? <span> Try again in {retryAfter} seconds.</span> : null}
        {onRetry ? (
          <span>
            {' '}
            <button type="button" className="btn-link" onClick={onRetry}>
              Retry
            </button>
          </span>
        ) : null}
        {apiError?.traceId ? <span className="notice-trace">trace {apiError.traceId}</span> : null}
      </div>
    </div>
  );
}

/** One glyph per tone, so the message is legible without colour. */
const NOTICE_ICON: Record<'info' | 'success' | 'warning' | 'error', IconComponent> = {
  info: IconInfo,
  success: IconCheckCircle,
  warning: IconAlertCircle,
  error: IconAlertCircle,
};

export function Notice({
  tone = 'info',
  title,
  children,
}: {
  tone?: 'info' | 'success' | 'warning' | 'error';
  title?: ReactNode;
  children?: ReactNode;
}) {
  const Icon = NOTICE_ICON[tone];
  return (
    <div className={`notice notice-${tone}`}>
      <span className="notice-icon" aria-hidden="true">
        <Icon size={17} />
      </span>
      <div className="notice-body">
        {title ? <span className="notice-title">{title}</span> : null}
        {children ? <span>{children}</span> : null}
      </div>
    </div>
  );
}

// ----------------------------------------------------- empty and loading

export function EmptyState({
  title,
  children,
  action,
  icon,
}: {
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  /** Defaults to an empty tray. Override where a more specific glyph says more. */
  icon?: IconComponent;
}) {
  const Icon = icon ?? IconInboxEmpty;
  return (
    <div className="empty">
      <span className="empty-icon" aria-hidden="true">
        <Icon size={22} />
      </span>
      <span className="empty-title">{title}</span>
      {children ? <p className="small">{children}</p> : null}
      {action}
    </div>
  );
}

/** Placeholder rows of roughly the right shape. See the note in globals.css. */
export function Skeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="skeleton-stack" aria-hidden="true">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="skeleton" style={{ width: `${100 - (index % 3) * 12}%` }} />
      ))}
    </div>
  );
}

/**
 * The three-state wrapper: loading, failed, or here is your data.
 *
 * <p>Every page in this app has the same first fifteen lines without it -- check
 * `initialLoading`, check `error`, then narrow `data` from `T | undefined`. Putting
 * it in one component means the loading state looks the same everywhere and the
 * error state always offers `refetch`, and it means the children callback receives a
 * non-optional `T`, so no page needs a `data!` to typecheck.
 *
 * <p>Note that it renders children while a *refetch* is in flight, showing the
 * previous data. Blanking the table on every filter change would be a worse
 * experience than a brief stale read, and the alternative -- a skeleton on each
 * keystroke of a search box -- flickers.
 */
export function DataState<T>({
  query,
  children,
  skeletonRows,
  errorTitle,
}: {
  query: QueryResult<T>;
  children: (data: T) => ReactNode;
  skeletonRows?: number;
  errorTitle?: string;
}) {
  if (query.error) {
    return <ErrorNotice error={query.error} title={errorTitle} onRetry={query.refetch} />;
  }
  if (query.data === undefined) {
    return <Skeleton rows={skeletonRows} />;
  }
  return <>{children(query.data)}</>;
}

// ---------------------------------------------------------------- tables

/** A table inside its own horizontal scroll container. See `.table-wrap`. */
export function TableWrap({ children }: { children: ReactNode }) {
  return (
    <div className="table-wrap">
      <table className="table">{children}</table>
    </div>
  );
}

/**
 * Zero-based paging controls.
 *
 * <p>The count reads "n of m" rather than "page x of y" because a warden looking at
 * a queue cares how many items there are, not how many pages the page size happens
 * to produce. Both buttons stay mounted and go disabled at the ends, so the row
 * never changes width and the Next button does not move under the cursor.
 */
export function Pager({
  page,
  totalPages,
  totalElements,
  shown,
  onPage,
  busy,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  shown: number;
  onPage: (page: number) => void;
  busy?: boolean;
}) {
  const first = totalElements === 0 ? 0 : page * Math.max(shown, 1) + 1;
  const last = totalElements === 0 ? 0 : first + shown - 1;
  return (
    <div className="pager">
      <span className="nums">
        {totalElements === 0 ? 'No results' : `${first}–${last} of ${totalElements}`}
      </span>
      <div className="pager-buttons">
        <Button small onClick={() => onPage(page - 1)} disabled={page <= 0 || busy}>
          <IconChevronLeft size={15} />
          Previous
        </Button>
        <span className="small nums">
          {totalPages === 0 ? 0 : page + 1} / {totalPages}
        </span>
        <Button small onClick={() => onPage(page + 1)} disabled={page + 1 >= totalPages || busy}>
          Next
          <IconChevronRight size={15} />
        </Button>
      </div>
    </div>
  );
}

/*
 * The initials disc. Kept here rather than in app-shell so a table row can show one
 * next to a student's name with the same 32px geometry the sidebar uses.
 */
export function Avatar({
  name,
  size = 'md',
}: {
  name: string;
  size?: 'sm' | 'md' | 'lg';
}) {
  const cls = size === 'md' ? 'avatar' : `avatar avatar-${size}`;
  return (
    <span className={cls} aria-hidden="true">
      {initialsOf(name)}
    </span>
  );
}

/** First and last word, so "Asha Rao" is AR and a one-word name is a single letter. */
function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  const first = words[0]![0] ?? '';
  const last = words.length > 1 ? (words[words.length - 1]![0] ?? '') : '';
  return (first + last).toUpperCase();
}

/*
 * A progress track. `value` is a percentage the caller has already computed, because
 * the two things this shows -- fee collection and occupancy -- divide different
 * quantities and rounding belongs with whoever knows the units.
 *
 * The thresholds are the caller's too: 40% occupancy is unremarkable, 40% of fees
 * collected a month before the deadline is not.
 */
export function Meter({
  value,
  tone,
  label,
}: {
  value: number;
  tone?: 'default' | 'warning' | 'danger';
  /** Announced to a screen reader, which cannot see the width of a div. */
  label: string;
}) {
  const pct = Math.max(0, Math.min(100, Math.round(value)));
  const fill = tone && tone !== 'default' ? `meter-fill meter-fill-${tone}` : 'meter-fill';
  return (
    <div
      className="meter"
      role="progressbar"
      aria-label={label}
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div className={fill} style={{ width: `${pct}%` }} />
    </div>
  );
}

/*
 * A segmented control for switching one table between a few fixed views.
 *
 * `aria-pressed` is both the announced state and the CSS hook, so a segment cannot
 * look selected without saying that it is. Generic over the value type so the caller
 * gets a narrowed union back rather than a string it has to re-validate.
 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
  /** Names the group; without it the buttons are three unexplained words. */
  label: string;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/*
 * A search box with the glyph inside it. `type="search"` rather than `text`, so a
 * mobile keyboard offers the right return key and the browser offers its own clear
 * button; the icon is absolutely positioned by the wrapper so the input keeps its own
 * focus ring.
 */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Search',
  label,
  onSubmit,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** Visually hidden, because the placeholder disappears the moment you type. */
  label: string;
  onSubmit?: () => void;
}) {
  return (
    <div className="search">
      <span className="search-icon">
        <IconSearch size={16} />
      </span>
      <input
        type="search"
        className="input"
        value={value}
        aria-label={label}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && onSubmit) {
            event.preventDefault();
            onSubmit();
          }
        }}
      />
    </div>
  );
}
