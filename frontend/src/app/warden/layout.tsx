'use client';

/**
 * The warden area's guard and frame.
 *
 * <p>Admins are admitted alongside wardens, and that is not laxity -- it mirrors the
 * server exactly. `SecurityConfig` gates `/api/v1/warden/**` with
 * `hasAnyRole("ADMIN", "WARDEN")`, and `AccessScope` gives an admin the full set of
 * genders and hostel types rather than a special case, so every one of these pages
 * works unchanged for an admin with a wider `IN (...)` list. Excluding them here
 * would hide functionality the backend deliberately grants.
 *
 * <p>Nothing renders until the guard has an answer. Rendering the shell early would
 * paint a nav a signed-out visitor is about to be redirected away from, and rendering
 * the children early would fire their fetches with no token -- a burst of 401s for a
 * page nobody will see.
 */

import { AppShell, type NavSection } from '@/components/app-shell';
import { useRequireRole } from '@/lib/auth';

const SECTIONS: NavSection[] = [
  { items: [{ href: '/warden', label: 'Dashboard', exact: true }] },
  {
    label: 'Residents',
    items: [
      { href: '/warden/students', label: 'Students' },
      { href: '/warden/applications', label: 'Applications' },
      { href: '/warden/allocations', label: 'Allocations' },
      { href: '/warden/rooms', label: 'Rooms' },
      { href: '/warden/occupancy', label: 'Occupancy' },
    ],
  },
  {
    label: 'Daily',
    items: [
      { href: '/warden/attendance', label: 'Attendance' },
      { href: '/warden/absence-alerts', label: 'Absence alerts' },
    ],
  },
  {
    label: 'Money',
    items: [
      { href: '/warden/fees', label: 'Fees' },
      { href: '/warden/payments', label: 'Payments' },
    ],
  },
  {
    label: 'Community',
    items: [
      { href: '/warden/complaints', label: 'Complaints' },
      { href: '/warden/notices', label: 'Notices' },
    ],
  },
];

export default function WardenLayout({ children }: { children: React.ReactNode }) {
  const { allowed } = useRequireRole('WARDEN', 'ADMIN');
  if (!allowed) return null;
  return (
    <AppShell area="Warden" sections={SECTIONS}>
      {children}
    </AppShell>
  );
}
