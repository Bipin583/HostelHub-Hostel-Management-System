'use client';

/**
 * The admin area's guard and frame.
 *
 * <p>Two pages live here, and both are operational rather than day-to-day: the
 * scheduled jobs, which can be run by hand for a chosen date, and the audit log. They
 * are separate from the warden console because `/api/v1/admin/**` is gated on
 * `hasRole("ADMIN")` alone -- a warden cannot reach either endpoint, so putting the
 * links in their nav would be an invitation to a 403.
 *
 * <p>The last section links across to the warden console, because an admin genuinely
 * can use it: see the note in the warden layout. It is a nav entry rather than a
 * duplicated set of pages.
 */

import { AppShell, type NavSection } from '@/components/app-shell';
import { useRequireRole } from '@/lib/auth';

const SECTIONS: NavSection[] = [
  {
    label: 'Operations',
    items: [
      { href: '/admin/jobs', label: 'Scheduled jobs' },
      { href: '/admin/audit', label: 'Audit log' },
    ],
  },
  { label: 'Elsewhere', items: [{ href: '/warden', label: 'Warden console' }] },
];

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { allowed } = useRequireRole('ADMIN');
  if (!allowed) return null;
  return (
    <AppShell area="Admin" sections={SECTIONS}>
      {children}
    </AppShell>
  );
}
