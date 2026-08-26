'use client';

/**
 * The notice board as a student sees it.
 *
 * <p><b>Everything here already matches the reader.</b> The server filters by audience --
 * hostel, gender, year -- and drops expired notices, so this page does no filtering of
 * its own. That is why there is no "for you" toggle: there is nothing else to show. A
 * client-side filter would either duplicate the server's matching rules or contradict
 * them.
 *
 * <p>The full body is rendered, not a preview. A warden's list truncates because they are
 * scanning what they posted; a student is reading. `white-space: pre-wrap` keeps the line
 * breaks the warden typed, which is the difference between a legible list of five rules
 * and one long paragraph.
 *
 * <p>`audienceLabel` is shown as the server composed it. Rebuilding "Second year, Ladies'
 * Hostel" from the three nullable fields here would be a second implementation of the
 * same rule, free to drift.
 */

import { useState } from 'react';
import { student } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatDateTime } from '@/lib/format';
import { Badge, Card, DataState, EmptyState, PageHead, Pager } from '@/components/ui';

export default function StudentNoticesPage() {
  const [page, setPage] = useState(0);

  const notices = useQuery((signal) => student.notices.list({ page, size: 10 }, signal), [page]);

  return (
    <>
      <PageHead title="Notices" subtitle="Posted to your hostel, your year, or everyone" />

      <DataState query={notices} skeletonRows={5} errorTitle="Could not load notices">
        {(data) =>
          data.content.length === 0 ? (
            <Card>
              <EmptyState title="Nothing posted">
                Notices from your warden appear here. Expired ones drop off on their own.
              </EmptyState>
            </Card>
          ) : (
            <>
              {data.content.map((notice) => (
                <Card
                  key={notice.id}
                  title={notice.title}
                  subtitle={`${notice.authorName ?? 'Hostel office'} · ${formatDateTime(notice.publishedAt)}`}
                  actions={<Badge tone="accent">{notice.audienceLabel}</Badge>}
                  footer={
                    notice.expiresAt === null ? undefined : (
                      <span className="small faint">
                        Comes down {formatDateTime(notice.expiresAt)}
                      </span>
                    )
                  }
                >
                  <p style={{ whiteSpace: 'pre-wrap' }}>{notice.body}</p>
                </Card>
              ))}

              <Card>
                <Pager
                  page={data.number}
                  totalPages={data.totalPages}
                  totalElements={data.totalElements}
                  shown={data.numberOfElements}
                  onPage={setPage}
                  busy={notices.loading}
                />
              </Card>
            </>
          )
        }
      </DataState>
    </>
  );
}
