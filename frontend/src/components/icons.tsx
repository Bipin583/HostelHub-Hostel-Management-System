/**
 * The icon set: hand-drawn inline SVG, no dependency.
 *
 * <p><b>Why not an icon package.</b> `lucide-react` or `react-icons` would add a
 * dependency whose entire value is a few hundred path strings, of which this console
 * uses thirty. Tree-shaking helps the bundle, not the supply chain: it is still a
 * package to audit, pin and renovate for the sake of some `<path d="...">`. These are
 * copied from the feather geometry -- 24x24 box, 1.75 stroke, round caps and joins --
 * so they sit consistently beside one another, which is the only thing an icon set has
 * to get right.
 *
 * <p><b>Why they inherit colour and size.</b> Every icon takes `stroke="currentColor"`
 * and a `size` that defaults to 16. That means an icon inside a `.btn-danger` is red
 * without being told, and an icon in the sidebar dims with the label it sits beside.
 * The alternative -- a `color` prop -- would put theme decisions at every call site and
 * break dark mode one icon at a time.
 *
 * <p><b>Why they are all exported.</b> `noUnusedLocals` is on, so an icon defined here
 * and used nowhere would fail the build. Exporting them keeps the set complete and lets
 * the compiler tell us which ones the console actually renders.
 *
 * <p><b>Accessibility.</b> Every icon is `aria-hidden`, with no title element and no
 * label. An icon in this app is always beside text or inside a control that has an
 * `aria-label` of its own, so announcing the glyph too would just repeat the label.
 * There is no decorative-versus-meaningful judgement to make at the call site because
 * the answer here is always "decorative".
 */
import type { SVGProps } from 'react';

export type IconProps = Omit<SVGProps<SVGSVGElement>, 'width' | 'height'> & {
  /** Edge length in px. 16 suits body text; 18--20 suits nav and buttons. */
  size?: number;
};

/**
 * The shared wrapper. Holds the seven attributes every icon repeats, so a new icon is
 * only ever its paths -- and so a change to the stroke weight is one edit, not thirty.
 */
function Svg({ size = 16, children, ...rest }: IconProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

// ------------------------------------------------------------ navigation

/** Warden and admin dashboards. */
export const IconDashboard = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </Svg>
);

/** Applications -- a form awaiting a decision. */
export const IconInbox = (p: IconProps) => (
  <Svg {...p}>
    <path d="M22 12h-6l-2 3h-4l-2-3H2" />
    <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
  </Svg>
);

/** Rooms and beds. */
export const IconBed = (p: IconProps) => (
  <Svg {...p}>
    <path d="M2 20v-8a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8" />
    <path d="M2 16h20" />
    <path d="M6 10V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v3" />
    <path d="M13 10V7a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v3" />
  </Svg>
);

/** Students -- a roll of people. */
export const IconUsers = (p: IconProps) => (
  <Svg {...p}>
    <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
    <circle cx="9" cy="7" r="4" />
    <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
  </Svg>
);

/** Attendance register. */
export const IconClipboard = (p: IconProps) => (
  <Svg {...p}>
    <rect x="8" y="2" width="8" height="4" rx="1" />
    <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
    <path d="m9 14 2 2 4-4" />
  </Svg>
);

/** Absence alerts. */
export const IconAlert = (p: IconProps) => (
  <Svg {...p}>
    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
    <path d="M12 9v4" />
    <path d="M12 17h.01" />
  </Svg>
);

/** Fees and invoices. */
export const IconReceipt = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 2h16v20l-3-2-2 2-3-2-3 2-2-2-3 2z" />
    <path d="M8 7h8" />
    <path d="M8 11h8" />
    <path d="M8 15h4" />
  </Svg>
);

/** Payments -- money in. */
export const IconWallet = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20 12V8H6a2 2 0 0 1 0-4h12v4" />
    <path d="M4 6v12a2 2 0 0 0 2 2h14v-4" />
    <path d="M18 12a2 2 0 0 0 0 4h4v-4z" />
  </Svg>
);

/** Complaints. */
export const IconMessage = (p: IconProps) => (
  <Svg {...p}>
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8z" />
  </Svg>
);

/** Notices. */
export const IconMegaphone = (p: IconProps) => (
  <Svg {...p}>
    <path d="m3 11 15-6v14L3 13z" />
    <path d="M3 11v2a1 1 0 0 0 1 1h2" />
    <path d="M7 14v5a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-4" />
    <path d="M21 10v4" />
  </Svg>
);

/** Absence requests -- leave. */
export const IconCalendar = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3" y="4" width="18" height="17" rx="2" />
    <path d="M3 10h18" />
    <path d="M8 2v4" />
    <path d="M16 2v4" />
  </Svg>
);

/** Audit trail. */
export const IconHistory = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 3v5h5" />
    <path d="M3.05 13A9 9 0 1 0 6 5.3L3 8" />
    <path d="M12 7v5l4 2" />
  </Svg>
);

/** Scheduled jobs. */
export const IconClock = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3.5 2" />
  </Svg>
);

/** The user's own profile / account. */
export const IconUser = (p: IconProps) => (
  <Svg {...p}>
    <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </Svg>
);

/** Settings and configuration. */
export const IconSettings = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.82 1.17V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3 15a1.65 1.65 0 0 0-1.51-1H1a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 3 8.6a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 8 4.6V4a2 2 0 1 1 4 0v.09A1.65 1.65 0 0 0 15 3a1.65 1.65 0 0 0 1.82.33h.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 21 8.6V9a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </Svg>
);

/** Hostels. */
export const IconBuilding = (p: IconProps) => (
  <Svg {...p}>
    <rect x="4" y="2" width="16" height="20" rx="2" />
    <path d="M9 22v-4h6v4" />
    <path d="M8 6h.01M12 6h.01M16 6h.01M8 10h.01M12 10h.01M16 10h.01M8 14h.01M16 14h.01" />
  </Svg>
);

// ------------------------------------------------------------------ actions

export const IconSearch = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Svg>
);

export const IconPlus = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);

export const IconCheck = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20 6 9 17l-5-5" />
  </Svg>
);

export const IconX = (p: IconProps) => (
  <Svg {...p}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Svg>
);

export const IconChevronRight = (p: IconProps) => (
  <Svg {...p}>
    <path d="m9 18 6-6-6-6" />
  </Svg>
);

export const IconChevronLeft = (p: IconProps) => (
  <Svg {...p}>
    <path d="m15 18-6-6 6-6" />
  </Svg>
);

export const IconChevronDown = (p: IconProps) => (
  <Svg {...p}>
    <path d="m6 9 6 6 6-6" />
  </Svg>
);

export const IconMenu = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </Svg>
);

export const IconLogOut = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <path d="m16 17 5-5-5-5" />
    <path d="M21 12H9" />
  </Svg>
);

export const IconRefresh = (p: IconProps) => (
  <Svg {...p}>
    <path d="M21 12a9 9 0 0 1-9 9 9 9 0 0 1-6.7-3L3 16" />
    <path d="M3 12a9 9 0 0 1 9-9 9 9 0 0 1 6.7 3L21 8" />
    <path d="M21 3v5h-5M3 21v-5h5" />
  </Svg>
);

export const IconDownload = (p: IconProps) => (
  <Svg {...p}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <path d="m7 10 5 5 5-5" />
    <path d="M12 15V3" />
  </Svg>
);

export const IconFilter = (p: IconProps) => (
  <Svg {...p}>
    <path d="M22 3H2l8 9.46V19l4 2v-8.54z" />
  </Svg>
);

export const IconExternal = (p: IconProps) => (
  <Svg {...p}>
    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
    <path d="M15 3h6v6" />
    <path d="M10 14 21 3" />
  </Svg>
);

// ------------------------------------------------------------------- states

export const IconInfo = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 16v-5" />
    <path d="M12 8h.01" />
  </Svg>
);

export const IconCheckCircle = (p: IconProps) => (
  <Svg {...p}>
    <path d="M21.5 11.1V12a9 9 0 1 1-5.34-8.23" />
    <path d="m9 11 3 3 8.5-8.5" />
  </Svg>
);

export const IconAlertCircle = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 8v4" />
    <path d="M12 16h.01" />
  </Svg>
);

export const IconLock = (p: IconProps) => (
  <Svg {...p}>
    <rect x="4" y="10" width="16" height="11" rx="2" />
    <path d="M8 10V7a4 4 0 0 1 8 0v3" />
  </Svg>
);

export const IconInboxEmpty = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 4h16v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" />
    <path d="M4 9h4l2 3h4l2-3h4" />
  </Svg>
);

export const IconShield = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    <path d="m9 12 2 2 4-4" />
  </Svg>
);

export const IconTrendUp = (p: IconProps) => (
  <Svg {...p}>
    <path d="m3 17 6-6 4 4 8-8" />
    <path d="M15 7h6v6" />
  </Svg>
);

export const IconTrendDown = (p: IconProps) => (
  <Svg {...p}>
    <path d="m3 7 6 6 4-4 8 8" />
    <path d="M15 17h6v-6" />
  </Svg>
);

export const IconChart = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 3v18h18" />
    <rect x="7" y="12" width="3" height="6" rx="0.5" />
    <rect x="12" y="8" width="3" height="10" rx="0.5" />
    <rect x="17" y="4" width="3" height="14" rx="0.5" />
  </Svg>
);

// -------------------------------------------------------------------- theme

export const IconSun = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
);

export const IconMoon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
  </Svg>
);

export const IconMonitor = (p: IconProps) => (
  <Svg {...p}>
    <rect x="2" y="3" width="20" height="14" rx="2" />
    <path d="M8 21h8M12 17v4" />
  </Svg>
);

// --------------------------------------------------------------- brand mark

/**
 * The logo mark: a building silhouette with a lit window, filled rather than stroked so
 * it reads at 20px where a 1.75 stroke would close up. It is the one icon here that
 * carries the brand, so it is also the one that sits on the gradient tile.
 */
export const IconLogo = ({ size = 18, ...rest }: IconProps) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
    focusable="false"
    {...rest}
  >
    <path d="M4 21V7.2a1 1 0 0 1 .6-.92l7-3.1a1 1 0 0 1 .8 0l7 3.1a1 1 0 0 1 .6.92V21a1 1 0 0 1-1 1h-5.2v-5.4h-3.6V22H5a1 1 0 0 1-1-1zm4.4-9.6h2.6V8.8H8.4zm4.6 0h2.6V8.8H13z" />
  </svg>
);
