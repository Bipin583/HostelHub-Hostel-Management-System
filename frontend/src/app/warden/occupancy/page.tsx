'use client';

/**
 * Occupancy rolled up per hostel and block.
 *
 * <p>This is the one screen a warden shows someone else -- "we are at 87% in LH-A" --
 * so it is deliberately a summary and not a filter of the rooms table. The endpoint
 * returns one row per (hostel, block) already aggregated in SQL, which is where that
 * arithmetic belongs: summing beds over every room in the browser would produce the
 * same numbers today and a slower, wronger page as the dataset grows past one page.
 *
 * <p>The totals row *is* computed here, from the rows the server sent. That is safe --
 * they are integer bed counts, so the sum is exact -- and it avoids asking the backend
 * for a grand total that is trivially derivable from the response it already returned.
 * The occupancy percentage in the totals row is recomputed from the summed beds rather
 * than averaged from the per-block percentages, because an average of percentages
 * weights a four-bed block the same as a hundred-bed one.
 */

import Link from 'next/link';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { formatPercent, hostelLabel, plural } from '@/lib/format';
import { Card, DataState, EmptyState, PageHead, StatCard, TableWrap } from '@/components/ui';
import type { HostelType, Occupancy } from '@/lib/types';

export default function WardenOccupancyPage() {
  const occupancy = useQuery((signal) => warden.rooms.occupancy(signal));

  return (
    <>
      <PageHead
        title="Occupancy"
        subtitle="Beds filled, by hostel and block"
        actions={<Link href="/warden/rooms">Room list</Link>}
      />

      <DataState query={occupancy} skeletonRows={5} errorTitle="Could not load occupancy">
        {(rows) =>
          rows.length === 0 ? (
            <Card>
              <EmptyState title="No rooms in scope">
                Nothing is recorded for the hostels you govern.
              </EmptyState>
            </Card>
          ) : (
            <>
              <Totals rows={rows} />
              {groupByHostel(rows).map(([hostelType, blocks]) => (
                <Card key={hostelType} title={hostelLabel(hostelType)} flush>
                  <TableWrap>
                    <thead>
                      <tr>
                        <th>Block</th>
                        <th className="num">Rooms</th>
                        <th className="num">Beds</th>
                        <th className="num">Occupied</th>
                        <th className="num">Free</th>
                        <th>Filled</th>
                      </tr>
                    </thead>
                    <tbody>
                      {blocks.map((row) => (
                        <tr key={`${row.hostelType}-${row.block ?? 'none'}`}>
                          <td className="cell-strong">{row.block ?? 'Unassigned'}</td>
                          <td className="num nums">{row.roomCount}</td>
                          <td className="num nums">{row.totalBeds}</td>
                          <td className="num nums">{row.occupiedBeds}</td>
                          <td className="num nums">{row.freeBeds}</td>
                          <td>
                            <span className="row-tight nowrap">
                              <span className="meter" style={{ width: '6rem' }} aria-hidden="true">
                                <span
                                  className={fillClass(row.occupancyPercent)}
                                  style={{ width: `${clamp(row.occupancyPercent)}%` }}
                                />
                              </span>
                              <span className="small nums">{formatPercent(row.occupancyPercent)}</span>
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </TableWrap>
                </Card>
              ))}
            </>
          )
        }
      </DataState>
    </>
  );
}

function Totals({ rows }: { rows: Occupancy[] }) {
  const totalBeds = rows.reduce((sum, row) => sum + row.totalBeds, 0);
  const occupiedBeds = rows.reduce((sum, row) => sum + row.occupiedBeds, 0);
  const freeBeds = rows.reduce((sum, row) => sum + row.freeBeds, 0);
  const roomCount = rows.reduce((sum, row) => sum + row.roomCount, 0);
  const percent = totalBeds === 0 ? 0 : (occupiedBeds / totalBeds) * 100;

  return (
    <div className="stat-grid">
      <StatCard label="Beds" value={totalBeds} note={plural(roomCount, 'room')} />
      <StatCard label="Occupied" value={occupiedBeds} note={formatPercent(percent, 1)} />
      <StatCard label="Free" value={freeBeds} note="Available to allocate" />
      <StatCard label="Blocks" value={rows.length} note="Counted across your scope" />
    </div>
  );
}

/**
 * Group the flat rows by hostel, preserving the order the server sent.
 *
 * <p>A `Map` rather than an object literal, because `Map` guarantees insertion order
 * for any key type -- an object would too for these three string keys, but only by
 * accident of them not being integer-like, which is not a property worth depending on.
 */
function groupByHostel(rows: Occupancy[]): [HostelType, Occupancy[]][] {
  const groups = new Map<HostelType, Occupancy[]>();
  for (const row of rows) {
    const existing = groups.get(row.hostelType);
    if (existing) existing.push(row);
    else groups.set(row.hostelType, [row]);
  }
  return [...groups.entries()];
}

function clamp(value: number): number {
  return Math.max(0, Math.min(100, value));
}

/**
 * Amber when nearly full, red when full.
 *
 * <p>Inverted from the fee meter on purpose: there, a high number is the goal, so high
 * is neutral-accent and low is red. Here a full hostel is the problem -- it means the
 * next approved application has nowhere to go.
 */
function fillClass(percent: number): string {
  if (percent >= 100) return 'meter-fill meter-fill-danger';
  if (percent >= 90) return 'meter-fill meter-fill-warning';
  return 'meter-fill';
}
