'use client';

/**
 * The root route, which is a router and nothing else.
 *
 * <p>There is no shared home page in this app: an admin's landing place is the jobs
 * console, a warden's is their dashboard, a student's is theirs, and a signed-out
 * visitor's is the login form. So `/` waits for `restore()` to answer and then sends
 * the user where they belong -- see `homePathFor`.
 *
 * <p>`replace` rather than `push`, so `/` does not sit in the back stack. Otherwise
 * pressing Back from the dashboard lands here and immediately forwards again, which
 * makes Back appear broken.
 *
 * <p>The redirect is in an effect because navigation during render is not permitted.
 * What renders in the meantime is the mark and one line of text rather than a skeleton:
 * this is a sub-second stop, and a skeleton of a page that will never appear is a lie
 * about what is loading.
 */

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { homePathFor, useSession } from '@/lib/auth';
import { IconLogo } from '@/components/icons';

export default function RootPage() {
  const { status, user } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (status === 'unknown') return;
    router.replace(status === 'authenticated' ? homePathFor(user?.role) : '/login');
  }, [status, user?.role, router]);

  return (
    <main className="splash">
      <div className="splash-inner">
        {/* The mark, on the brand gradient, so the half-second this is up looks
            deliberate rather than like a page that failed to load. */}
        <span className="splash-mark" aria-hidden="true">
          <IconLogo size={26} />
        </span>
        <p>Signing you in&hellip;</p>
      </div>
    </main>
  );
}
