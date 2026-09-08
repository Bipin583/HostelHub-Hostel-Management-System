/**
 * Presentation helpers: money, dates, durations, and enum labels.
 *
 * <p>Everything user-visible that isn't a straight string passes through here, so
 * that a rupee looks the same on the fees page and the payments page, and so the
 * two rules below hold everywhere rather than in most places.
 *
 * <h2>Money is never a float</h2>
 *
 * <p>The backend stores and sends paise as integers, and this app keeps them that
 * way. `formatMoney` is the only place a paise count becomes rupees, and it does
 * the division inside `Intl.NumberFormat` rather than in JavaScript arithmetic --
 * `amountPaise / 100` on a large invoice is exactly the kind of expression that
 * yields `1234.5600000000001`. Nothing in this app adds, subtracts, or compares
 * rupees; sums are computed on paise and formatted once at the end.
 *
 * <h2>A `LocalDate` is not an instant</h2>
 *
 * <p>`attendanceDate`, `dueDate`, `streakStartDate` and friends arrive as
 * `"2026-05-15"`. `new Date("2026-05-15")` parses that as midnight *UTC*, so in any
 * timezone behind UTC it renders as the 14th -- a bug that only appears for some
 * users, and never for a developer in IST. `formatDate` therefore splits the string
 * itself and never constructs a `Date` from it at all, which also makes it
 * timezone-independent and safe to render identically on the server and the client.
 */

// -------------------------------------------------------------------- currency

const RUPEES_WHOLE = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});

const RUPEES_EXACT = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * Paise to rupees, in the Indian digit grouping (₹1,23,456).
 *
 * <p>Whole-rupee amounts drop the decimals, because every fee in this system is
 * billed in whole rupees and `₹45,000.00` is noise in a table. A partial payment
 * that genuinely has paise keeps them -- silently rounding somebody's balance to
 * the nearest rupee would make the arithmetic on screen stop adding up.
 */
export function formatMoney(paise: number | null | undefined): string {
  if (paise === null || paise === undefined) return '--';
  const formatter = paise % 100 === 0 ? RUPEES_WHOLE : RUPEES_EXACT;
  return formatter.format(paise / 100);
}

/** Rupees only, no symbol -- for inputs, where a `₹` in the value breaks parsing. */
export function paiseToRupeeInput(paise: number | null | undefined): string {
  if (paise === null || paise === undefined) return '';
  return paise % 100 === 0 ? String(paise / 100) : (paise / 100).toFixed(2);
}

/**
 * A rupee string typed by a human, as integer paise.
 *
 * <p>`Math.round` is doing real work: `parseFloat('1234.56') * 100` is
 * `123455.99999999999`, and `Math.trunc` of that undercharges by a paisa. Returns
 * null for anything that isn't a number, so callers can show a validation message
 * instead of sending `NaN` to a `@Positive Long`.
 */
export function rupeeInputToPaise(input: string): number | null {
  const trimmed = input.trim().replace(/,/g, '');
  if (trimmed === '' || !/^\d+(\.\d{1,2})?$/.test(trimmed)) return null;
  return Math.round(Number(trimmed) * 100);
}

// ----------------------------------------------------------------------- dates

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `"2026-05-15"` to `"15 May 2026"`, without ever building a `Date`. See the note above. */
export function formatDate(isoDate: string | null | undefined): string {
  const parts = splitIsoDate(isoDate);
  if (parts === null) return '--';
  const [year, month, day] = parts;
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** `"2026-05-15"` to `"15 May"` -- for axis labels and dense tables. */
export function formatDayMonth(isoDate: string | null | undefined): string {
  const parts = splitIsoDate(isoDate);
  if (parts === null) return '--';
  const [, month, day] = parts;
  return `${day} ${MONTHS[month - 1]}`;
}

function splitIsoDate(isoDate: string | null | undefined): [number, number, number] | null {
  if (!isoDate) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate);
  if (match === null) return null;
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

/**
 * An `Instant` (`"2026-05-15T09:30:00Z"`) in the viewer's own timezone.
 *
 * <p>This one *is* timezone-dependent, and that is correct -- an audit event
 * happened at a moment in time and should read in local terms. It is safe from
 * hydration mismatches for a structural reason rather than a lucky one: every
 * instant this app displays comes from data fetched after mount, so the server's
 * HTML contains no formatted timestamp for the client to disagree with.
 */
export function formatDateTime(isoInstant: string | null | undefined): string {
  if (!isoInstant) return '--';
  const at = new Date(isoInstant);
  if (Number.isNaN(at.getTime())) return '--';
  const day = at.getDate();
  const month = MONTHS[at.getMonth()];
  const year = at.getFullYear();
  const hours = String(at.getHours()).padStart(2, '0');
  const minutes = String(at.getMinutes()).padStart(2, '0');
  return `${day} ${month} ${year}, ${hours}:${minutes}`;
}

/** Today as `"YYYY-MM-DD"` in local terms -- the format every date endpoint takes. */
export function todayIso(): string {
  return toIsoDate(new Date());
}

/**
 * A `Date` as `"YYYY-MM-DD"` using its *local* fields.
 *
 * <p>Not `toISOString().slice(0, 10)`, which converts to UTC first and so returns
 * yesterday for anyone east of Greenwich after they stop working for the evening --
 * in IST that is every day after 05:30.
 */
export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/** `"YYYY-MM-DD"` shifted by whole days, staying in the string domain. */
export function shiftIsoDate(isoDate: string, days: number): string {
  const parts = splitIsoDate(isoDate);
  if (parts === null) return isoDate;
  const [year, month, day] = parts;
  // Noon, not midnight: constructing a local Date at 00:00 and adding days lands on
  // 23:00 of the previous day across a DST boundary. India has no DST, but this
  // helper should not be the reason a deployment elsewhere shows the wrong week.
  const shifted = new Date(year, month - 1, day, 12);
  shifted.setDate(shifted.getDate() + days);
  return toIsoDate(shifted);
}

/** Inclusive day count between two ISO dates, or null if either is unparseable. */
export function daysBetween(fromIso: string, toIso: string): number | null {
  const from = splitIsoDate(fromIso);
  const to = splitIsoDate(toIso);
  if (from === null || to === null) return null;
  const a = Date.UTC(from[0], from[1] - 1, from[2]);
  const b = Date.UTC(to[0], to[1] - 1, to[2]);
  return Math.round((b - a) / 86_400_000) + 1;
}

// ------------------------------------------------------------------- durations

/**
 * Seconds as the coarsest useful unit.
 *
 * <p>The complaint analytics endpoint returns averages in seconds, and a backlog's
 * oldest item is routinely six figures of them. "2d 4h" is a number a warden can
 * act on; "187,432s" is a number they have to do arithmetic on first.
 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '--';
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return minutes % 60 === 0 ? `${hours}h` : `${hours}h ${minutes % 60}m`;
  const days = Math.floor(hours / 24);
  return hours % 24 === 0 ? `${days}d` : `${days}d ${hours % 24}h`;
}

/** A whole-number percentage, or `--`. Fractions of a percent are never actionable. */
export function formatPercent(value: number | null | undefined, fractionDigits = 0): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '--';
  return `${value.toFixed(fractionDigits)}%`;
}

/**
 * A 0..1 fraction as a percentage.
 *
 * `collectionRate` is the one figure the API sends as a fraction -- `FeeCollectionResponse`
 * fixes that convention on purpose, so the field is never sometimes 0.87 and sometimes 87 --
 * while `presentPercentage`, `occupancyPercent` and the rest arrive already scaled. Two
 * conventions need two functions with two names, because the alternative is a caller
 * guessing which one a field follows and rendering `0.6%` on a term that collected 63%.
 */
export function formatFraction(value: number | null | undefined, fractionDigits = 0): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '--';
  return formatPercent(value * 100, fractionDigits);
}

// ---------------------------------------------------------------------- labels

/**
 * `SCREAMING_SNAKE_CASE` to `Sentence case`.
 *
 * <p>Applied to enum values that need no translation, so `IN_PROGRESS` reads as
 * "In progress" without a lookup table per enum. Values whose expansion a reader
 * would not guess -- `LH`, `BH`, `MH` -- get explicit entries below instead, because
 * "Lh" is worse than the raw code.
 */
export function humanise(value: string | null | undefined): string {
  if (!value) return '--';
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

const HOSTEL_LABELS: Record<string, string> = {
  LH: "Ladies' hostel",
  BH: "Boys' hostel",
  MH: "Men's hostel",
};

export function hostelLabel(hostel: string | null | undefined): string {
  if (!hostel) return 'All hostels';
  return HOSTEL_LABELS[hostel] ?? hostel;
}

const GENDER_LABELS: Record<string, string> = { M: 'Male', F: 'Female' };

export function genderLabel(gender: string | null | undefined): string {
  if (!gender) return '--';
  return GENDER_LABELS[gender] ?? gender;
}

/** `1` to `"1st year"`. Ordinals stop at 5, which is the backend's own bound. */
export function yearLabel(year: number | null | undefined): string {
  if (year === null || year === undefined) return '--';
  const suffix = year === 1 ? 'st' : year === 2 ? 'nd' : year === 3 ? 'rd' : 'th';
  return `${year}${suffix} year`;
}

/** `"3"` items to `"3 items"`, with the singular handled. */
export function plural(count: number, singular: string, pluralForm?: string): string {
  return `${count} ${count === 1 ? singular : (pluralForm ?? `${singular}s`)}`;
}
