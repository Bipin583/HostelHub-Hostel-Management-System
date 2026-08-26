import type { Metadata, Viewport } from 'next';
import './globals.css';

/**
 * The root layout: the document, the stylesheet, and nothing else.
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

export const metadata: Metadata = {
  title: 'HostelOps',
  description: 'Hostel allocation, attendance, fees and complaints console',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  // Both, in this order: the browser paints the correct chrome colour before any
  // JavaScript runs, so a dark-mode user does not get a white flash on first load.
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f6f7f9' },
    { media: '(prefers-color-scheme: dark)', color: '#101215' },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
