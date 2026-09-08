'use client';

import { useEffect, useState } from 'react';

import { IconCheckCircle, IconInfo, IconX } from '@/components/icons';
import { dismissToast, getToasts, subscribeToasts, type Toast } from '@/lib/toast';

/*
 * The toast region. Mounted once, by AppShell.
 *
 * `role="status"` with `aria-live="polite"` on the container rather than on each toast:
 * a live region has to exist in the DOM before the thing it announces is inserted into
 * it, or the insertion is not announced at all.
 */
export function Toaster() {
  const [toasts, setToasts] = useState<Toast[]>([]);

  // Subscribed in an effect rather than read during render, because the store is
  // module state the server does not have -- reading it while rendering would make the
  // server and the client disagree on the first paint.
  useEffect(() => {
    setToasts(getToasts());
    return subscribeToasts(setToasts);
  }, []);

  return (
    <div className="toast-region" role="status" aria-live="polite" aria-atomic="false">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.tone}`}>
          <span className="toast-icon" aria-hidden="true">
            {toast.tone === 'success' ? <IconCheckCircle size={17} /> : <IconInfo size={17} />}
          </span>
          <div className="toast-body">{toast.message}</div>
          <button
            type="button"
            className="icon-btn icon-btn-sm"
            onClick={() => dismissToast(toast.id)}
            aria-label="Dismiss"
          >
            <IconX size={15} />
          </button>
        </div>
      ))}
    </div>
  );
}
