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
import { useRequireRole } from '@/lib/auth';

const SECTIONS: NavSection[] = [
  { items: [{ href: '/student', label: 'Overview', exact: true }] },
  {
    label: 'My room',
    items: [
      { href: '/student/application', label: 'Hostel application' },
      { href: '/student/profile', label: 'Profile' },
    ],
  },
  {
    label: 'My record',
    items: [
      { href: '/student/attendance', label: 'Attendance' },
      { href: '/student/alerts', label: 'Absence alerts' },
      { href: '/student/fees', label: 'Fees' },
    ],
  },
  {
    label: 'Hostel',
    items: [
      { href: '/student/complaints', label: 'Complaints' },
      { href: '/student/notices', label: 'Notices' },
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
