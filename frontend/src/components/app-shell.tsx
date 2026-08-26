'use client';

/**
 * The frame every signed-in page renders inside: sidebar, nav, top bar, sign-out.
 *
 * <p>The navigation is passed in per area rather than derived from a global table,
 * because the three areas are genuinely different products sharing a login. A
 * student has nine links and no filters; a warden has fifteen and lives in tables.
 * A single nav definition with `visibleFor: [...]` on each entry would put all three
 * products' concerns in one list and make each one harder to read.
 *
 * <p>Active-link marking uses `aria-current="page"`, which is both the accessible
 * signal and -- see globals.css -- the styling hook. One attribute, not a class plus
 * an attribute that can disagree.
 */

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useState, type ReactNode } from 'react';
import { Button } from './ui';
import { logout, useSession } from '@/lib/auth';
import { hostelLabel, humanise } from '@/lib/format';

export interface NavItem {
  href: string;
  label: string;
  /**
   * Match the path exactly rather than by prefix.
   *
   * Needed for an area's index route: `/warden` is a prefix of every warden page, so
   * without this the dashboard link would stay highlighted on all of them and the
   * nav would never show where the user actually is.
   */
  exact?: boolean;
}

export interface NavSection {
  label?: string;
  items: NavItem[];
}

export function AppShell({
  area,
  sections,
  children,
}: {
  area: string;
  sections: NavSection[];
  children: ReactNode;
}) {
  const { user } = useSession();
  const pathname = usePathname();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);

  async function signOut() {
    setSigningOut(true);
    // `logout()` clears the local session even if the network call fails, so this
    // always ends up at the login page -- see the note on session.logout.
    await logout();
    router.replace('/login');
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-name">HostelOps</span>
          <span className="brand-scope">{area}</span>
        </div>

        <nav className="nav" aria-label={`${area} navigation`}>
          {sections.map((section, index) => (
            <div key={section.label ?? index} className="nav">
              {section.label ? <span className="nav-group-label">{section.label}</span> : null}
              {section.items.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="nav-link"
                  aria-current={isActive(pathname, item) ? 'page' : undefined}
                >
                  {item.label}
                </Link>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="who">
            <span className="who-name">{user?.fullName ?? '--'}</span>
            <span className="who-role">
              {user ? humanise(user.role) : ''}
              {user?.hostelScope ? ` · ${hostelLabel(user.hostelScope)}` : ''}
            </span>
          </div>
          <Button variant="ghost" small onClick={signOut} pending={signingOut}>
            Sign out
          </Button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <span className="brand-scope">{area}</span>
          <span className="small muted nowrap">{user?.username ?? ''}</span>
        </header>
        <main className="content">{children}</main>
      </div>
    </div>
  );
}

function isActive(pathname: string, item: NavItem): boolean {
  if (item.exact) return pathname === item.href;
  return pathname === item.href || pathname.startsWith(`${item.href}/`);
}
