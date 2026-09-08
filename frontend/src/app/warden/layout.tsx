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
import {
  IconAlert,
  IconBed,
  IconClipboard,
  IconDashboard,
  IconInbox,
  IconMegaphone,
  IconMessage,
  IconChart,
  IconReceipt,
  IconUsers,
  IconWallet,
} from '@/components/icons';
import { useRequireRole } from '@/lib/auth';

const SECTIONS: NavSection[] = [
  { items: [{ href: '/warden', label: 'Dashboard', exact: true, icon: IconDashboard }] },
  {
    label: 'Residents',
    items: [
      { href: '/warden/students', label: 'Students', icon: IconUsers },
      { href: '/warden/applications', label: 'Applications', icon: IconInbox },
      { href: '/warden/allocations', label: 'Allocations', icon: IconBed },
      { href: '/warden/rooms', label: 'Rooms', icon: IconBed },
      { href: '/warden/occupancy', label: 'Occupancy', icon: IconChart },
    ],
  },
  {
    label: 'Daily',
    items: [
      { href: '/warden/attendance', label: 'Attendance', icon: IconClipboard },
      { href: '/warden/absence-alerts', label: 'Absence alerts', icon: IconAlert },
    ],
  },
  {
    label: 'Money',
    items: [
      { href: '/warden/fees', label: 'Fees', icon: IconReceipt },
      { href: '/warden/payments', label: 'Payments', icon: IconWallet },
    ],
  },
  {
    label: 'Community',
    items: [
      { href: '/warden/complaints', label: 'Complaints', icon: IconMessage },
      { href: '/warden/notices', label: 'Notices', icon: IconMegaphone },
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
