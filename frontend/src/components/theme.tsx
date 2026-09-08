'use client';

/**
 * Light, dark, or follow the system.
 *
 * <p><b>Why three choices and not two.</b> A two-state toggle cannot express "match my
 * OS", which is what most people actually want and what this app did exclusively before
 * -- a bare `prefers-color-scheme` media query with no way to override it. The stored
 * value is therefore one of `light`, `dark`, `system`, and only the first two are
 * preferences; `system` is a subscription to someone else's.
 *
 * <p><b>Why `data-theme` on `<html>` and not a media query.</b> With a query, every
 * dark value would have to be written twice -- once under `prefers-color-scheme` and
 * once under whatever the manual override selector was -- and the two copies would
 * drift. Resolving the choice to a concrete `light`/`dark` attribute means the
 * stylesheet has exactly one dark block. See globals.css.
 *
 * <p><b>Why the first paint is handled elsewhere.</b> This component runs after React
 * hydrates, which is at least one paint too late: the page would flash the light theme
 * before the stored choice was read. The fix is the tiny synchronous script in
 * layout.tsx that sets the attribute before the browser paints anything. This component
 * then takes over, and deliberately does not re-apply on mount for a value the script
 * already set.
 *
 * <p><b>Why no context provider.</b> One control reads this state and one control
 * writes it. A provider would add a client boundary around the whole tree to serve a
 * single button in the topbar.
 */
import { useCallback, useEffect, useState } from 'react';
import { isThemeChoice, THEME_STORAGE_KEY, type ThemeChoice } from '@/lib/theme-storage';
import { IconMonitor, IconMoon, IconSun } from './icons';

const ORDER: ThemeChoice[] = ['light', 'dark', 'system'];

/** What the browser would pick on its own. */
function systemIsDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/**
 * Writes the resolved value to the DOM.
 *
 * <p>`system` is never written as-is: it is resolved here, so CSS only ever sees a
 * concrete theme and no rule has to know that a third state exists.
 */
function apply(choice: ThemeChoice): void {
  const resolved = choice === 'system' ? (systemIsDark() ? 'dark' : 'light') : choice;
  document.documentElement.dataset.theme = resolved;
  // Keeps the browser chrome -- the address bar on mobile, the scrollbar gutters --
  // in step with the page, which is otherwise the one surface that stays white.
  document.documentElement.style.colorScheme = resolved;
}

/**
 * The toggle. Cycles light -> dark -> system, showing the icon of the *current* choice.
 *
 * <p>A cycling button rather than a three-option menu because the whole set is three
 * items and one of them is "stop deciding". A menu for that is a click and a read where
 * a click would do.
 */
export function ThemeToggle() {
  // `system` until the effect below reads storage. Starting from the stored value would
  // mean reading localStorage during render, which differs between server and client
  // and is exactly what a hydration mismatch is.
  const [choice, setChoice] = useState<ThemeChoice>('system');

  useEffect(() => {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (isThemeChoice(stored)) setChoice(stored);
  }, []);

  // While following the system, track it live: a user who switches their OS to dark at
  // sunset expects this tab to follow without a reload. Only registered for `system`,
  // so an explicit choice is never overridden behind the user's back.
  useEffect(() => {
    if (choice !== 'system') return;
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => apply('system');
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, [choice]);

  const cycle = useCallback(() => {
    setChoice((current) => {
      const next = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
      window.localStorage.setItem(THEME_STORAGE_KEY, next);
      apply(next);
      return next;
    });
  }, []);

  const Icon = choice === 'light' ? IconSun : choice === 'dark' ? IconMoon : IconMonitor;
  const label =
    choice === 'light' ? 'Light theme' : choice === 'dark' ? 'Dark theme' : 'Theme follows system';

  return (
    <button
      type="button"
      className="icon-btn"
      onClick={cycle}
      // The label states the state, not the action: a screen reader user needs to know
      // what it is now, and the title says what pressing it will do.
      aria-label={label}
      title={`${label} \u2014 click to change`}
    >
      <Icon size={17} />
    </button>
  );
}
