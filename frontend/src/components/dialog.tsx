'use client';

/**
 * A modal built on the platform's `<dialog>`, in two forms: a general one and a
 * confirmation.
 *
 * <p>`<dialog>`'s `showModal()` gives focus trapping, Escape-to-close, inertness of
 * the page behind it, and the top layer -- four things a div-plus-portal has to
 * reimplement, and three of them are usually got wrong. The only care needed is
 * driving it from React state, which is what the effect below does: React owns
 * `open`, the effect makes the DOM agree.
 *
 * <p>`onClose` is wired to the element's own `close` event rather than only to the
 * buttons, because Escape and the backdrop both close a dialog without going through
 * any button -- and a React state that still says `open` after the browser has closed
 * the dialog leaves it impossible to reopen.
 */

import { useEffect, useRef, type ReactNode } from 'react';
import { Button, ErrorNotice } from './ui';

export function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) return;
    // `open` and `showModal()` are not the same thing: setting the attribute renders
    // a non-modal dialog with no backdrop and no focus trap, which looks correct
    // until the user tabs straight out of it into the page behind.
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog className="dialog" ref={ref} onClose={onClose}>
      <header className="dialog-head">
        <h2>{title}</h2>
      </header>
      <div className="dialog-body">{children}</div>
      {footer ? <div className="dialog-foot">{footer}</div> : null}
    </dialog>
  );
}

/**
 * Confirm an irreversible action.
 *
 * <p>Used for the handful of operations that cannot be undone from the UI -- vacating
 * a room, rejecting an application, deleting a notice. Not used for ordinary saves:
 * a confirmation on everything trains the user to dismiss confirmations.
 *
 * <p>It shows the error in place and stays open on failure. Closing on error would
 * discard the message along with the dialog, leaving the user with an action that
 * appears to have worked.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = 'Confirm',
  destructive,
  pending,
  error,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: ReactNode;
  body: ReactNode;
  confirmLabel?: string;
  destructive?: boolean;
  pending?: boolean;
  error?: unknown;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
          <Button variant={destructive ? 'danger' : 'primary'} onClick={onConfirm} pending={pending}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="stack-sm">
        {typeof body === 'string' ? <p>{body}</p> : body}
        {error ? <ErrorNotice error={error} title="That did not work" /> : null}
      </div>
    </Dialog>
  );
}
