'use client';

/**
 * Cmd-K / Ctrl-K: jump to any page in the current console by typing part of its name.
 *
 * <p><b>What it is not.</b> It is not a search over data -- it does not hit the API,
 * and it will not find a student by roll number. Every entry is a route the signed-in
 * user can already reach from the sidebar, so the palette is a faster path to the same
 * places and never the only path. That matters: a keyboard shortcut nobody discovers
 * must not be load-bearing.
 *
 * <p><b>Why not a library.</b> `cmdk` is 12 kB and a peer-dependency negotiation to
 * solve a filter, a list and an arrow-key handler. The interesting part -- which
 * routes exist for this role -- is data this app already has, in the same `NavSection`
 * array the sidebar renders.
 *
 * <p><b>Matching.</b> A case-insensitive subsequence, not a substring: "abal" finds
 * "Absence alerts". It is the cheapest fuzzy match that feels like a fuzzy match, and
 * with at most fifteen candidates there is nothing to optimise.
 *
 * <p><b>Accessibility.</b> The input keeps focus the whole time and owns the list via
 * `aria-activedescendant`, which is the combobox pattern -- so arrow keys move the
 * selection without moving focus, and a screen reader announces the highlighted option
 * as it changes. The rows are `role="option"` and never receive focus themselves.
 */

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { NavSection } from './app-shell';
import { IconChevronRight, IconSearch } from './icons';

interface Entry {
  href: string;
  label: string;
  group: string;
  Icon?: (props: { size?: number }) => React.ReactNode;
}

/** Flattens the sidebar's sections into one list, keeping the group for context. */
function flatten(sections: NavSection[], area: string): Entry[] {
  return sections.flatMap((section) =>
    section.items.map((item) => ({
      href: item.href,
      label: item.label,
      group: section.label ?? area,
      Icon: item.icon,
    })),
  );
}

/**
 * True when every character of `query` appears in `text`, in order.
 *
 * <p>Both are lowercased by the caller once per keystroke rather than once per
 * candidate, which is the only part of this worth thinking about.
 */
function subsequence(text: string, query: string): boolean {
  let i = 0;
  for (const ch of text) {
    if (ch === query[i]) i += 1;
    if (i === query.length) return true;
  }
  return query.length === 0;
}

export function CommandPalette({
  open,
  onClose,
  sections,
  area,
}: {
  open: boolean;
  onClose: () => void;
  sections: NavSection[];
  area: string;
}) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState(0);

  const entries = useMemo(() => flatten(sections, area), [sections, area]);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return entries;
    return entries.filter((entry) => subsequence(entry.label.toLowerCase(), needle));
  }, [entries, query]);

  // Reset on every open rather than on close: a palette that reopens showing the last
  // search is a palette that appears broken.
  useEffect(() => {
    if (open) {
      setQuery('');
      setCursor(0);
      inputRef.current?.focus();
    }
  }, [open]);

  // A filter that shortens the list can leave the cursor past the end.
  useEffect(() => {
    setCursor((c) => (c >= matches.length ? 0 : c));
  }, [matches.length]);

  const go = useCallback(
    (href: string) => {
      onClose();
      router.push(href);
    },
    [onClose, router],
  );

  if (!open) return null;

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || (event.key === 'n' && event.ctrlKey)) {
      event.preventDefault();
      setCursor((c) => (matches.length === 0 ? 0 : (c + 1) % matches.length));
    } else if (event.key === 'ArrowUp' || (event.key === 'p' && event.ctrlKey)) {
      event.preventDefault();
      setCursor((c) => (matches.length === 0 ? 0 : (c - 1 + matches.length) % matches.length));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const chosen = matches[cursor];
      if (chosen) go(chosen.href);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
  }

  return (
    // A click on the scrim closes; a click inside must not, hence stopPropagation on
    // the panel. No keyboard handler here -- Escape is handled on the input, which
    // holds focus for the whole life of the palette.
    <div className="palette-scrim" onMouseDown={onClose} role="presentation">
      <div
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="palette-input-row">
          <IconSearch size={17} />
          <input
            ref={inputRef}
            className="palette-input"
            type="text"
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-list"
            aria-activedescendant={matches[cursor] ? `palette-opt-${cursor}` : undefined}
            aria-autocomplete="list"
            placeholder={`Go to a page in ${area}\u2026`}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onKeyDown}
            autoComplete="off"
            spellCheck={false}
          />
          <kbd className="kbd">Esc</kbd>
        </div>

        <div className="palette-list" id="palette-list" role="listbox" aria-label="Pages">
          {matches.length === 0 ? (
            <p className="palette-empty">Nothing matches &ldquo;{query}&rdquo;.</p>
          ) : (
            matches.map((entry, index) => {
              const Icon = entry.Icon;
              return (
                <button
                  key={entry.href}
                  type="button"
                  id={`palette-opt-${index}`}
                  className="palette-item"
                  role="option"
                  aria-selected={index === cursor}
                  // Pointer movement drives the same cursor the keyboard does, so the
                  // highlight is never in two places at once.
                  onMouseMove={() => setCursor(index)}
                  onClick={() => go(entry.href)}
                  // tabIndex -1: focus stays in the input, per the combobox pattern.
                  tabIndex={-1}
                >
                  <span className="palette-item-icon">
                    {Icon ? <Icon size={16} /> : <IconChevronRight size={16} />}
                  </span>
                  <span className="palette-item-label">{entry.label}</span>
                  <span className="palette-item-area">{entry.group}</span>
                </button>
              );
            })
          )}
        </div>

        <div className="palette-foot">
          <span>
            <kbd className="kbd">&uarr;</kbd>
            <kbd className="kbd">&darr;</kbd> to move
          </span>
          <span>
            <kbd className="kbd">Enter</kbd> to open
          </span>
        </div>
      </div>
    </div>
  );
}
