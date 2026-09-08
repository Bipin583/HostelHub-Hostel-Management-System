import type { Metadata, Viewport } from 'next';
import { Inter, JetBrains_Mono } from 'next/font/google';
import './globals.css';
import { THEME_INIT_SCRIPT } from '@/lib/theme-storage';

/**
 * The root layout: the document, the fonts, the theme decision, and nothing else.
 *
 * <p>Deliberately a server component with no state, no provider, and no session
 * awareness. Two consequences follow, and both are wanted.
 *
 * <p>First, no provider wraps the tree, so the session cannot be "not available
 * here" -- it lives in module state that any client component can subscribe to. See
 * src/lib/auth.tsx for why that is the right shape for a value with exactly one
 * instance per tab.
 *
 * <p>Second, nothing here reads cookies or headers, so this layout is fully static.
 * The three area layouts are the client components that guard routes; keeping the
 * root free of that means the shell HTML is served without waiting on anything.
 */

/*
 * Fonts, downloaded at BUILD time and served from this origin.
 *
 * <p>`next/font/google` fetches the files during `next build` and emits them as static
 * assets, so the browser makes no request to Google at runtime -- no third-party
 * connection, nothing to block, and no privacy footnote to write. It also generates a
 * `@font-face` with `size-adjust` and `ascent-override` measured from the real font, so
 * the local fallback occupies the same space and the page does not reflow when Inter
 * arrives. A hand-written `<link rel="stylesheet">` to fonts.googleapis.com would give
 * up all three.
 *
 * <p>The cost, stated plainly: `next build` now needs network access to
 * fonts.googleapis.com. An offline build fails here rather than silently falling back.
 * That is the right trade for an app whose build already pulls an npm registry.
 *
 * <p>`variable` rather than `className`: the value arrives as a custom property that
 * globals.css consumes inside `--font-sans`, so the font is part of the token system
 * instead of a class the body has to remember to carry.
 */
const inter = Inter({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-inter',
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-mono-jetbrains',
});

export const metadata: Metadata = {
  title: {
    default: 'HostelOps',
    // Every page sets its own short title; this supplies the suffix once instead of at
    // twenty-eight call sites.
    template: '%s \u00b7 HostelOps',
  },
  description: 'Hostel allocation, attendance, fees and complaints console',
  applicationName: 'HostelOps',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  // Both, in this order: the browser paints the correct chrome colour before any
  // JavaScript runs, so a dark-mode user does not get a white flash on first load.
  // These two values are --canvas in each theme; changing one without the other leaves
  // a visible seam between the address bar and the page.
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f7f8fa' },
    { media: '(prefers-color-scheme: dark)', color: '#0b0d11' },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    // suppressHydrationWarning on <html> only, and only because of the script below:
    // it mutates this element's attributes before React sees the document, so the
    // server markup and the client DOM legitimately differ here. Scoped to one element
    // rather than the tree, so a real mismatch anywhere else is still reported.
    <html lang="en" className={`${inter.variable} ${jetbrainsMono.variable}`} suppressHydrationWarning>
      <head>
        {/*
         * The no-flash script. It must be synchronous, inline, and in <head>: a
         * deferred or external script runs after the first paint, and the first paint
         * is precisely what it exists to get right. A dark-mode user would otherwise
         * see a white page for one frame on every navigation that reloads the document.
         *
         * dangerouslySetInnerHTML is the only way to emit a <script> body from JSX. The
         * content is a module constant with no user input in it -- see
         * src/lib/theme-storage.ts.
         */}
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
