'use client';

/**
 * The room list, with a per-room occupants view.
 *
 * <p>Occupants are fetched on demand rather than embedded in every row. The list
 * endpoint returns bed counts, which is what the table needs; who is in room A-101 is
 * a second request made only when somebody asks. Embedding it would turn one page of
 * twenty rooms into twenty joins on every load, for information that is read a few
 * times a day.
 *
 * <p>The block filter is a plain text box because blocks are free-text on the server
 * (`block` is a nullable varchar, not an enum) -- offering a dropdown would mean
 * inventing a list of blocks the backend does not maintain, and it would go stale the
 * first time a new wing opens.
 */

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { genderLabel, hostelLabel, formatPercent, yearLabel } from '@/lib/format';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  PageHead,
  Pager,
  TableWrap,
  TextField,
} from '@/components/ui';
import { Dialog } from '@/components/dialog';
import type { Room } from '@/lib/types';

export default function WardenRoomsPage() {
  const [block, setBlock] = useState('');
  const [debouncedBlock, setDebouncedBlock] = useState('');
  const [page, setPage] = useState(0);
  const [openRoom, setOpenRoom] = useState<Room | null>(null);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedBlock(block.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [block]);

  const rooms = useQuery(
    (signal) =>
      warden.rooms.list({ page, size: 20, block: debouncedBlock || undefined }, signal),
    [page, debouncedBlock],
  );

  return (
    <>
      <PageHead
        title="Rooms"
        subtitle="Beds, eligibility rules, and who is in them"
        actions={<Link href="/warden/occupancy">Occupancy summary</Link>}
      />

      <Card>
        <div className="filters">
          <TextField
            label="Block"
            placeholder="Any block"
            hint="Matches the block letter or name exactly as recorded"
            value={block}
            onChange={(event) => setBlock(event.target.value)}
          />
        </div>
      </Card>

      <Card flush>
        <DataState query={rooms} skeletonRows={8} errorTitle="Could not load rooms">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No rooms match">
                {debouncedBlock ? `Nothing in block "${debouncedBlock}".` : 'No rooms are recorded.'}
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Room</th>
                      <th>Hostel</th>
                      <th>Block</th>
                      <th className="num">Floor</th>
                      <th>Reserved for</th>
                      <th className="num">Beds</th>
                      <th>Occupancy</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((room) => (
                      <tr key={room.id}>
                        <td className="mono cell-strong">{room.roomName}</td>
                        <td className="nowrap">{hostelLabel(room.hostelType)}</td>
                        <td>{room.block ?? '--'}</td>
                        <td className="num nums">{room.floor ?? '--'}</td>
                        <td className="nowrap small">{eligibilityLabel(room)}</td>
                        <td className="num nums">
                          {room.occupiedBeds}/{room.capacity}
                        </td>
                        <td>
                          <BedMeter room={room} />
                        </td>
                        <td className="shrink">
                          <Button small onClick={() => setOpenRoom(room)}>
                            Occupants
                          </Button>
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
                    busy={rooms.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>

      <OccupantsDialog room={openRoom} onClose={() => setOpenRoom(null)} />
    </>
  );
}

/**
 * Who is in one room.
 *
 * <p>Kept as its own component so the `useQuery` for occupants unmounts when the
 * dialog closes. Holding the hook in the page and gating it with `enabled` would keep
 * the last room's occupants in state, and reopening the dialog for a different room
 * would flash the previous room's residents before the new response lands.
 */
function OccupantsDialog({ room, onClose }: { room: Room | null; onClose: () => void }) {
  const occupants = useQuery(
    (signal) => warden.rooms.occupants(room?.id ?? 0, signal),
    [room?.id],
    { enabled: room !== null },
  );

  return (
    <Dialog
      open={room !== null}
      onClose={onClose}
      title={room === null ? 'Occupants' : `Room ${room.roomName}`}
      footer={<Button onClick={onClose}>Close</Button>}
    >
      <DataState query={occupants} skeletonRows={3} errorTitle="Could not load occupants">
        {(students) =>
          students.length === 0 ? (
            <EmptyState title="Empty room">
              {room === null ? null : `All ${room.capacity} beds are free.`}
            </EmptyState>
          ) : (
            <ul className="stack-sm">
              {students.map((occupant) => (
                <li key={occupant.id} className="spread">
                  <span>
                    <Link href={`/warden/students/${occupant.id}`} className="cell-strong">
                      {occupant.fullName}
                    </Link>
                    <span className="cell-sub mono"> {occupant.rollNumber}</span>
                  </span>
                  <span className="small muted nowrap">{yearLabel(occupant.yearOfStudy)}</span>
                </li>
              ))}
            </ul>
          )
        }
      </DataState>
    </Dialog>
  );
}

function BedMeter({ room }: { room: Room }) {
  const percent = room.capacity === 0 ? 0 : (room.occupiedBeds / room.capacity) * 100;
  return (
    <span className="row-tight nowrap">
      <span className="meter" style={{ width: '5rem' }} aria-hidden="true">
        <span className={percent >= 100 ? 'meter-fill meter-fill-warning' : 'meter-fill'} style={{ width: `${percent}%` }} />
      </span>
      <span className="small faint nums">{formatPercent(percent)}</span>
    </span>
  );
}

/** "Any student" unless the room reserves a year, a gender, or both. */
function eligibilityLabel(room: Room): string {
  const rules = [
    room.eligibleYear === null ? null : yearLabel(room.eligibleYear),
    room.eligibleGender === null ? null : genderLabel(room.eligibleGender),
  ].filter(Boolean);
  return rules.length === 0 ? 'Any student' : rules.join(', ');
}
