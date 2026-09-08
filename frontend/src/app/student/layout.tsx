'use client';

/**
 * The student portal's guard and frame.
 *
 * <p>Students only -- and unlike the warden area, an admin is deliberately *not*
 * admitted. The server would let one through the URL gate (`/api/v1/student/**` is
 * `hasAnyRole("ADMIN", "STUDENT")`) but every page in here ends up calling something
 * that goes through `AccessScope.requireStudentId()`, which throws FORBIDDEN with
 * "This endpoint is for student accounts" when the principal has no student id. An
 * admin admitted here would see a portal of error notices; sending them to their own
 * area instead is the truthful outcome.
 */

import { AppShell, type NavSection } from '@/components/app-shell';
import {
  IconAlert,
  IconBed,
  IconClipboard,
  IconDashboard,
  IconMegaphone,
  IconMessage,
  IconReceipt,
  IconUser,
} from '@/components/icons';
import { useRequireRole } from '@/lib/auth';

const SECTIONS: NavSection[] = [
  { items: [{ href: '/student', label: 'Overview', exact: true, icon: IconDashboard }] },
  {
    label: 'My room',
    items: [
      { href: '/student/application', label: 'Hostel application', icon: IconBed },
      { href: '/student/profile', label: 'Profile', icon: IconUser },
    ],
  },
  {
    label: 'My record',
    items: [
      { href: '/student/attendance', label: 'Attendance', icon: IconClipboard },
      { href: '/student/alerts', label: 'Absence alerts', icon: IconAlert },
      { href: '/student/fees', label: 'Fees', icon: IconReceipt },
    ],
  },
  {
    label: 'Hostel',
    items: [
      { href: '/student/complaints', label: 'Complaints', icon: IconMessage },
      { href: '/student/notices', label: 'Notices', icon: IconMegaphone },
    ],
  },
];

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  const { allowed } = useRequireRole('STUDENT');
  if (!allowed) return null;
  return (
    <AppShell area="Student" sections={SECTIONS}>
      {children}
    </AppShell>
  );
}
