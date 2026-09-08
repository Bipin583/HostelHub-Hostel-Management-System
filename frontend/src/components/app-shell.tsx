'use client';

/**
 * The frame every signed-in page renders inside: sidebar, nav, top bar, user menu.
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
 *
 * <p><b>The sidebar below 960px.</b> It becomes an off-canvas drawer rather than
 * collapsing into a horizontal strip of the same links. A strip that scrolls sideways
 * hides half the nav and gives no clue that it does; a drawer is a familiar gesture
 * and keeps the group labels, which are most of what makes fifteen links navigable.
 * The drawer closes on route change, on Escape, and on a scrim tap -- all three,
 * because a drawer that stays open over the page it just navigated to feels broken.
 */

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { CommandPalette } from './command-palette';
import { IconChevronDown, IconLogo, IconLogOut, IconMenu, IconSearch, IconUser } from './icons';
import { ThemeToggle } from './theme';
import { Toaster } from './toaster';
import { logout, useSession } from '@/lib/auth';
import { hostelLabel, humanise } from '@/lib/format';

/** The icon component shape. Deliberately narrow: an icon takes a size and nothing else. */
export type NavIcon = (props: { size?: number }) => ReactNode;

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
  /**
   * The glyph beside the label.
   *
   * <p>Optional so a nav entry is never blocked on drawing an icon, but supplied for
   * every entry in all three areas today. It is decoration in the strict sense -- the
   * label is always present and always the accessible name -- and it earns its place
   * by making a fifteen-item list scannable by shape instead of by reading.
   */
  icon?: NavIcon;
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
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  // Any navigation dismisses the transient chrome. `pathname` is the dependency rather
  // than an onClick on every link, so a route change from anywhere -- a table row, the
  // palette, the browser's back button -- has the same effect.
  useEffect(() => {
    setDrawerOpen(false);
    setMenuOpen(false);
  }, [pathname]);

  // One document-level key handler for the two global shortcuts.
  //
  // Cmd-K is intercepted with preventDefault because Chrome and Firefox both bind it to
  // the address bar; without that the palette and the omnibox open together. Escape is
  // handled here for the drawer and the user menu -- the palette handles its own,
  // because its input has focus and a document listener would fire second.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      } else if (event.key === 'Escape') {
        setDrawerOpen(false);
        setMenuOpen(false);
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  async function signOut() {
    setSigningOut(true);
    // `logout()` clears the local session even if the network call fails, so this
    // always ends up at the login page -- see the note on session.logout.
    await logout();
    router.replace('/login');
  }

  const title = activeLabel(pathname, sections) ?? area;

  return (
    <div className="shell">
      {/* First in the DOM, so it is the first thing a keyboard reaches on every page. */}
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      <aside className="sidebar" data-open={drawerOpen ? 'true' : undefined}>
        <Link href={homeFor(area)} className="brand">
          <span className="brand-mark" aria-hidden="true">
            <IconLogo size={19} />
          </span>
          <span className="brand-text">
            <span className="brand-name">HostelOps</span>
            <span className="brand-scope">{area}</span>
          </span>
        </Link>

        <nav className="nav" aria-label={`${area} navigation`}>
          {sections.map((section, index) => (
            <div key={section.label ?? index} className="nav-group">
              {section.label ? <span className="nav-group-label">{section.label}</span> : null}
              {section.items.map((item) => {
                const Icon = item.icon;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className="nav-link"
                    aria-current={isActive(pathname, item) ? 'page' : undefined}
                  >
                    {Icon ? (
                      <span className="nav-link-icon">
                        <Icon size={17} />
                      </span>
                    ) : null}
                    <span className="nav-link-label">{item.label}</span>
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="menu-anchor">
            {menuOpen ? <UserMenu onSignOut={signOut} signingOut={signingOut} area={area} /> : null}
            <button
              type="button"
              className="who"
              aria-expanded={menuOpen}
              aria-haspopup="menu"
              onClick={() => setMenuOpen((open) => !open)}
            >
              <span className="avatar" aria-hidden="true">
                {initials(user?.fullName ?? user?.username ?? '?')}
              </span>
              <span className="who-text">
                <span className="who-name">{user?.fullName ?? '\u2014'}</span>
                <span className="who-role">
                  {user ? humanise(user.role) : ''}
                  {user?.hostelScope ? ` \u00b7 ${hostelLabel(user.hostelScope)}` : ''}
                </span>
              </span>
              <IconChevronDown size={15} />
            </button>
          </div>
        </div>
      </aside>

      {/* The scrim exists only while the drawer is open, and only below the breakpoint
          -- the CSS hides it above 960px, where the sidebar is permanent. */}
      {drawerOpen ? <div className="scrim" onClick={closeDrawer} role="presentation" /> : null}

      <div className="main">
        <header className="topbar">
          <button
            type="button"
            className="icon-btn nav-toggle"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen(true)}
          >
            <IconMenu size={19} />
          </button>

          {/* The name of the page, taken from the nav entry that matched -- so the bar
              cannot disagree with the highlighted link. */}
          <span className="topbar-title">{title}</span>

          <div className="topbar-actions">
            <button type="button" className="cmd-trigger" onClick={() => setPaletteOpen(true)}>
              <IconSearch size={15} />
              <span className="cmd-trigger-label">Jump to&hellip;</span>
              <kbd className="kbd">Ctrl K</kbd>
            </button>
            <ThemeToggle />
          </div>
        </header>

        <main className="content" id="main">
          {children}
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        sections={sections}
        area={area}
      />

      {/* Mounted once per console rather than per page, so a toast survives the
          navigation that follows the write which raised it. */}
      <Toaster />
    </div>
  );
}

/**
 * The menu behind the user card: the profile page where one exists, and sign out.
 *
 * <p>Split out so the shell's own render stays readable, not because it is reused.
 */
function UserMenu({
  onSignOut,
  signingOut,
  area,
}: {
  onSignOut: () => void;
  signingOut: boolean;
  area: string;
}) {
  return (
    <div className="menu menu-up" role="menu">
      {/* Only the student area has a profile page; a link to /student/profile from the
          warden console would be a 403 dressed as a menu item. */}
      {area === 'Student' ? (
        <Link href="/student/profile" className="menu-item" role="menuitem">
          <IconUser size={16} />
          Profile
        </Link>
      ) : null}
      <button
        type="button"
        className="menu-item menu-item-danger"
        role="menuitem"
        onClick={onSignOut}
        disabled={signingOut}
      >
        <IconLogOut size={16} />
        {signingOut ? 'Signing out\u2026' : 'Sign out'}
      </button>
    </div>
  );
}

function isActive(pathname: string, item: NavItem): boolean {
  if (item.exact) return pathname === item.href;
  return pathname === item.href || pathname.startsWith(`${item.href}/`);
}

/**
 * The label of the nav entry the current path matches, for the top bar's title.
 *
 * <p>Longest href wins, so `/warden/fees/12` reports "Fees" rather than "Dashboard" --
 * `/warden` is a prefix of both, and the more specific match is the true one. Returns
 * undefined for a route that is in no nav section (a detail page reached from a table),
 * and the caller falls back to the area name.
 */
function activeLabel(pathname: string, sections: NavSection[]): string | undefined {
  let best: NavItem | undefined;
  for (const section of sections) {
    for (const item of section.items) {
      if (!isActive(pathname, item)) continue;
      if (!best || item.href.length > best.href.length) best = item;
    }
  }
  return best?.label;
}

/**
 * Up to two initials from a display name.
 *
 * <p>First and last word rather than the first two, so "Asha Priya Rao" gives AR --
 * which is how people abbreviate their own names. Falls back to one character for a
 * single-word name, and to a dash for nothing at all.
 */
function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '\u2014';
  if (words.length === 1) return words[0]!.slice(0, 2);
  return `${words[0]![0]}${words[words.length - 1]![0]}`;
}

/** The area's own index route, for the brand link. */
function homeFor(area: string): string {
  return `/${area.toLowerCase()}`;
}
