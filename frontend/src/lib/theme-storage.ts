/**
 * Where the theme choice is stored, and how it is read.
 *
 * <p><b>Why this is its own module.</b> Two very different consumers need the same key:
 * the `ThemeToggle` client component, and the synchronous script the root layout inlines
 * into `<head>` to set the theme before first paint. The layout is a server component,
 * and importing a constant out of a `'use client'` module from the server gives back a
 * client *reference* rather than the string -- the interpolation would silently produce
 * nonsense. A plain module with no directive is importable by both, so the key exists
 * exactly once.
 */

/** The localStorage key. Namespaced, because localStorage is shared per origin. */
export const THEME_STORAGE_KEY = 'hostelops.theme';

export type ThemeChoice = 'light' | 'dark' | 'system';

export function isThemeChoice(value: string | null): value is ThemeChoice {
  return value === 'light' || value === 'dark' || value === 'system';
}

/**
 * The script that runs before the browser paints.
 *
 * <p>It is a string rather than a function because it has to be inlined into the
 * document by `dangerouslySetInnerHTML`; nothing about it is dynamic except the key,
 * which is injected as a JSON literal so it cannot break out of the string.
 *
 * <p>Everything is inside a try/catch: `localStorage` throws outright in a Safari
 * private window and under some enterprise policies, and a theme preference is not
 * worth a blank page. The fallback is the system preference, which is what the app did
 * before it had a toggle at all.
 *
 * <p>It writes both `data-theme` (what the stylesheet selects on) and `style.colorScheme`
 * (what the browser uses for scrollbars, form controls and the address bar), because
 * getting only the first produces a dark page with a white scrollbar.
 */
export const THEME_INIT_SCRIPT = `(function(){try{var k=${JSON.stringify(
  THEME_STORAGE_KEY,
)};var c=localStorage.getItem(k);var d=c==='dark'||((c===null||c==='system')&&window.matchMedia('(prefers-color-scheme: dark)').matches);var t=d?'dark':'light';var e=document.documentElement;e.dataset.theme=t;e.style.colorScheme=t;}catch(_){}})();`;
