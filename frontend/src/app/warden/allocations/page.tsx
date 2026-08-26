'use client';

/**
 * Every allocation, current and historical.
 *
 * <p>Read-only, and that is a design decision rather than an omission. Allocating and
 * vacating both need a specific student, and this table is ordered by allocation, so
 * the actions live on the student page where the year, gender and current room are
 * visible next to them. A "vacate" button on a row of a long list is the shape of
 * mistake that ends with the wrong bed freed on a Friday evening.
 *
 * <p>The `active` flag is shown as Current/Ended rather than hidden by a filter,
 * because the history is the audit trail: a student who moved rooms twice this year
 * should read as three rows, not one.
 */

import Link from 'next/link';
import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatDateTime, hostelLabel, yearLabel } from '@/lib/format';
import { Badge, Card, DataState, EmptyState, PageHead, Pager, TableWrap } from '@/components/ui';

export default function WardenAllocationsPage() {
  const [page, setPage] = useState(0);
  const allocations = useQuery(
    (signal) => warden.allocations.list({ page, size: 20 }, signal),
    [page],
  );

  return (
    <>
      <PageHead
        title="Allocations"
        subtitle="Newest first, including ended allocations"
        actions={<Link href="/warden/students">Allocate from a student</Link>}
      />

      <Card flush>
        <DataState query={allocations} skeletonRows={8} errorTitle="Could not load allocations">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No allocations yet">
                Approve an application and allocate a room to see it here.
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Student</th>
                      <th>Roll number</th>
                      <th>Room</th>
                      <th>Hostel</th>
                      <th>Allocated</th>
                      <th>Vacated</th>
                      <th className="shrink">State</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((allocation) => (
                      <tr key={allocation.id}>
                        <td>
                          <Link
                            href={`/warden/students/${allocation.student.id}`}
                            className="cell-strong"
                          >
                            {allocation.student.fullName}
                          </Link>
                          <span className="cell-sub">{yearLabel(allocation.student.yearOfStudy)}</span>
                        </td>
                        <td className="mono">{allocation.student.rollNumber}</td>
                        <td className="mono">{allocation.room.roomName}</td>
                        <td className="nowrap">{hostelLabel(allocation.room.hostelType)}</td>
                        <td className="nowrap">{formatDateTime(allocation.allocatedAt)}</td>
                        <td className="nowrap">
                          {allocation.vacatedAt === null ? '--' : formatDateTime(allocation.vacatedAt)}
                        </td>
                        <td className="shrink">
                          {allocation.active ? (
                            <Badge tone="success">Current</Badge>
                          ) : (
                            <Badge tone="neutral">Ended</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
                <div className="card-foot">
                  <Pager
                    page={data.number}
                    totalPages={data.totalPages}
                    totalElements={data.totalElements}
                    shown={data.numberOfElements}
                    onPage={setPage}
                    busy={allocations.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>
    </>
  );
}
