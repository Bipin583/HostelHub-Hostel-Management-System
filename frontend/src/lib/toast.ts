/*
 * Transient confirmation for a write whose effect is off-screen.
 *
 * A module-level store rather than a React context, for one reason: a context needs a
 * provider above every caller, and the callers here are leaf forms in three separate
 * console layouts. With a store, `<Toaster />` is mounted once in the shell and any
 * client component can call `toast(...)` with a plain import and no wiring.
 *
 * Deliberately not for errors. An error has to stay on the page beside the control that
 * failed -- see `ErrorNotice`, which also carries the trace id. Something that vanishes
 * after four seconds is the wrong home for an identifier somebody has to write down.
 */

export type ToastTone = 'success' | 'info';

export interface Toast {
  id: number;
  tone: ToastTone;
  message: string;
}

type Listener = (toasts: Toast[]) => void;

const listeners = new Set<Listener>();
let toasts: Toast[] = [];
let nextId = 1;

/** How long a toast stays up. Long enough to read twelve words, short enough to ignore. */
const LIFETIME_MS = 4000;

function emit() {
  // A fresh array each time: React's `useSyncExternalStore` compares by identity, and
  // mutating the existing one in place would render nothing.
  const snapshot = toasts;
  listeners.forEach((listener) => listener(snapshot));
}

export function subscribeToasts(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getToasts(): Toast[] {
  return toasts;
}

export function dismissToast(id: number) {
  toasts = toasts.filter((toast) => toast.id !== id);
  emit();
}

export function toast(message: string, tone: ToastTone = 'success') {
  const id = nextId++;
  // Newest last: the region stacks upward from the bottom-right corner, so appending
  // puts the newest nearest the corner the eye is already on.
  toasts = [...toasts, { id, tone, message }];
  emit();
  // No cleanup handle is returned. A caller that unmounts mid-timeout still wants its
  // "Saved" to be seen -- that is the whole point of a toast over an inline message.
  setTimeout(() => dismissToast(id), LIFETIME_MS);
}
