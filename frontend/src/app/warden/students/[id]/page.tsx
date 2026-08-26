'use client';

/**
 * One student, everything about them, and the two room actions.
 *
 * <p>This is the page a warden lands on from every table in the app, so it is also
 * where allocation happens: allocate, auto-allocate, vacate. Putting those buttons
 * here rather than only on the allocations list matters because the decision needs
 * the context on this page -- year, gender, current room, attendance -- and a warden
 * who has to hold a roll number in their head while switching screens will
 * eventually allocate the wrong student.
 *
 * <p>Four requests, deliberately not one. `GET /students/{id}` is the record;
 * allocation history, the attendance summary and the register slice are each their
 * own endpoint. A composite "student page" DTO would be faster by one round trip and
 * would couple the backend to this screen's current layout -- the first time a
 * different page wants three of the four fields, the DTO grows a nullable section.
 *
 * <p>The room picker is fetched only when the dialog opens (`enabled`), because a
 * list of every room in the hostel is a page-load cost paid by every visitor for a
 * feature used by a few.
 */

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { useParams } from 'next/navigation';
import { warden } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import {
  formatDate,
  formatDateTime,
  formatPercent,
  genderLabel,
  hostelLabel,
  plural,
  shiftIsoDate,
  todayIso,
  yearLabel,
} from '@/lib/format';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  SelectField,
  TableWrap,
} from '@/components/ui';
import { ConfirmDialog, Dialog } from '@/components/dialog';
import { AllocationStatusBadge, AttendanceStatusBadge } from '@/components/status-badges';
import type { Room } from '@/lib/types';

export default function WardenStudentDetailPage() {
  // `useParams` rather than the `params` prop: in the App Router that prop is a
  // promise in a client component, and unwrapping it with `use()` buys nothing here.
  const routeParams = useParams();
  const rawId = Array.isArray(routeParams.id) ? routeParams.id[0] : routeParams.id;
  const studentId = Number(rawId);
  const validId = Number.isInteger(studentId) && studentId > 0;

  const [roomDialogOpen, setRoomDialogOpen] = useState(false);
  const [vacateOpen, setVacateOpen] = useState(false);
  const [chosenRoom, setChosenRoom] = useState('');

  // A month of attendance: enough to see a pattern, short enough that the summary
  // percentage still describes the student's current behaviour.
  const range = useMemo(() => {
    const to = todayIso();
    return { from: shiftIsoDate(to, -29), to };
  }, []);

  const student = useQuery((signal) => warden.students.byId(studentId, signal), [studentId], {
    enabled: validId,
  });
  const history = useQuery((signal) => warden.allocations.history(studentId, signal), [studentId], {
    enabled: validId,
  });
  const summary = useQuery(
    (signal) => warden.attendance.summaryForStudent(studentId, range, signal),
    [studentId, range],
    { enabled: validId },
  );
  const attendance = useQuery(
    (signal) => warden.attendance.forStudent(studentId, range, signal),
    [studentId, range],
    { enabled: validId },
  );
  const rooms = useQuery((signal) => warden.rooms.list({ size: 200 }, signal), [], {
    enabled: roomDialogOpen,
  });

  const refreshAfterRoomChange = () => {
    student.refetch();
    history.refetch();
  };

  const allocate = useAction((roomId: number) => warden.allocations.allocate(studentId, roomId));
  const autoAllocate = useAction(() => warden.allocations.autoAllocate(studentId));
  const vacate = useAction(() => warden.allocations.vacate(studentId));

  if (!validId) {
    return (
      <>
        <PageHead title="Student" />
        <Card>
          <EmptyState title="Not a valid student">
            The address does not contain a student id.
          </EmptyState>
        </Card>
      </>
    );
  }

  const freeRooms = (rooms.data?.content ?? []).filter((room) => room.freeBeds > 0);

  return (
    <>
      <DataState query={student} skeletonRows={3} errorTitle="Could not load this student">
        {(detail) => (
          <>
            <PageHead
              crumb={<Link href="/warden/students">Students</Link>}
              title={detail.fullName}
              subtitle={
                <>
                  <span className="mono">{detail.rollNumber}</span> · {yearLabel(detail.yearOfStudy)} ·{' '}
                  {detail.branch ?? 'No branch on record'}
                </>
              }
              actions={
                detail.currentRoom ? (
                  <Button variant="danger" onClick={() => setVacateOpen(true)}>
                    Vacate room
                  </Button>
                ) : (
                  <>
                    <Button onClick={() => setRoomDialogOpen(true)}>Choose a room</Button>
                    <Button
                      variant="primary"
                      pending={autoAllocate.running}
                      onClick={async () => {
                        const done = await autoAllocate.run();
                        if (done) refreshAfterRoomChange();
                      }}
                    >
                      Auto-allocate
                    </Button>
                  </>
                )
              }
            />

            {autoAllocate.error ? (
              <ErrorNotice error={autoAllocate.error} title="Could not allocate a room" />
            ) : null}

            <div className="grid-2">
              <Card title="Record">
                <dl className="facts">
                  <dt className="fact-label">Status</dt>
                  <dd className="fact-value">
                    <AllocationStatusBadge status={detail.allocationStatus} />
                  </dd>
                  <dt className="fact-label">Email</dt>
                  <dd className="fact-value">{detail.email}</dd>
                  <dt className="fact-label">Gender</dt>
                  <dd className="fact-value">{genderLabel(detail.gender)}</dd>
                  <dt className="fact-label">Mobile</dt>
                  <dd className="fact-value">{detail.mobileNo ?? '--'}</dd>
                  <dt className="fact-label">Parent's mobile</dt>
                  <dd className="fact-value">{detail.parentMobileNo ?? '--'}</dd>
                </dl>
              </Card>

              <Card title="Room">
                {detail.currentRoom ? (
                  <dl className="facts">
                    <dt className="fact-label">Room</dt>
                    <dd className="fact-value mono">{detail.currentRoom.roomName}</dd>
                    <dt className="fact-label">Hostel</dt>
                    <dd className="fact-value">{hostelLabel(detail.currentRoom.hostelType)}</dd>
                    <dt className="fact-label">Block</dt>
                    <dd className="fact-value">{detail.currentRoom.block ?? '--'}</dd>
                    <dt className="fact-label">Floor</dt>
                    <dd className="fact-value nums">{detail.currentRoom.floor ?? '--'}</dd>
                    <dt className="fact-label">Capacity</dt>
                    <dd className="fact-value nums">{detail.currentRoom.capacity}</dd>
                  </dl>
                ) : (
                  <EmptyState title="No room allocated">
                    {detail.allocationStatus === 'PENDING'
                      ? 'This student has applied and is waiting for a room.'
                      : 'This student has not applied for accommodation.'}
                  </EmptyState>
                )}
              </Card>
            </div>

            <Card
              title="Attendance, last 30 days"
              subtitle={`${formatDate(range.from)} to ${formatDate(range.to)}`}
            >
              <DataState query={summary} skeletonRows={2}>
                {(data) => (
                  <div className="stat-grid">
                    <div className="stat">
                      <span className="stat-label">Marked days</span>
                      <span className="stat-value">{data.markedDays}</span>
                    </div>
                    <div className="stat">
                      <span className="stat-label">Present</span>
                      <span className="stat-value">{data.presentDays}</span>
                    </div>
                    <div className="stat">
                      <span className="stat-label">Absent</span>
                      <span className="stat-value">{data.absentDays}</span>
                    </div>
                    <div className="stat">
                      <span className="stat-label">Attendance</span>
                      <span className="stat-value">{formatPercent(data.presentPercentage, 1)}</span>
                      <span className="stat-note">Of days actually marked</span>
                    </div>
                  </div>
                )}
              </DataState>
            </Card>

            <div className="grid-2">
              <Card title="Room history" flush>
                <DataState query={history} skeletonRows={3}>
                  {(rows) =>
                    rows.length === 0 ? (
                      <EmptyState title="No allocations yet" />
                    ) : (
                      <TableWrap>
                        <thead>
                          <tr>
                            <th>Room</th>
                            <th>From</th>
                            <th>To</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rows.map((allocation) => (
                            <tr key={allocation.id}>
                              <td className="mono">{allocation.room.roomName}</td>
                              <td className="nowrap">{formatDateTime(allocation.allocatedAt)}</td>
                              <td className="nowrap">
                                {allocation.active ? (
                                  <span className="muted">Current</span>
                                ) : (
                                  formatDateTime(allocation.vacatedAt)
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </TableWrap>
                    )
                  }
                </DataState>
              </Card>

              <Card title="Recent register entries" flush>
                <DataState query={attendance} skeletonRows={3}>
                  {(rows) =>
                    rows.length === 0 ? (
                      <EmptyState title="Nothing marked in this window" />
                    ) : (
                      <TableWrap>
                        <thead>
                          <tr>
                            <th>Date</th>
                            <th className="shrink">Status</th>
                            <th>Marked by</th>
                          </tr>
                        </thead>
                        <tbody>
                          {/* Newest first: the endpoint returns the window ascending, and
                              the question being asked of this table is "what happened
                              lately", not "what happened first". */}
                          {[...rows].reverse().map((record) => (
                            <tr key={record.id}>
                              <td className="nowrap">{formatDate(record.attendanceDate)}</td>
                              <td className="shrink">
                                <AttendanceStatusBadge status={record.status} />
                              </td>
                              <td>{record.markedByName ?? '--'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </TableWrap>
                    )
                  }
                </DataState>
              </Card>
            </div>

            <Dialog
              open={roomDialogOpen}
              onClose={() => setRoomDialogOpen(false)}
              title={`Allocate a room to ${detail.fullName}`}
              footer={
                <>
                  <Button variant="ghost" onClick={() => setRoomDialogOpen(false)}>
                    Cancel
                  </Button>
                  <Button
                    variant="primary"
                    disabled={chosenRoom === ''}
                    pending={allocate.running}
                    onClick={async () => {
                      const done = await allocate.run(Number(chosenRoom));
                      if (done) {
                        setRoomDialogOpen(false);
                        setChosenRoom('');
                        refreshAfterRoomChange();
                      }
                    }}
                  >
                    Allocate
                  </Button>
                </>
              }
            >
              <div className="stack-sm">
                {/* Eligibility is the server's decision, not this list's. Rooms with no
                    free bed are filtered out because that is a fact already in the
                    response; year and gender rules are left to the endpoint, which
                    answers ROOM_NOT_ELIGIBLE and is the only thing that can be
                    authoritative about them. */}
                <Notice tone="info">
                  Only rooms with a free bed are listed. The server still checks the year and
                  gender rules for the room you pick.
                </Notice>
                <DataState query={rooms} skeletonRows={3} errorTitle="Could not load rooms">
                  {() =>
                    freeRooms.length === 0 ? (
                      <EmptyState title="No room has a free bed" />
                    ) : (
                      <SelectField
                        label="Room"
                        placeholder="Select a room"
                        value={chosenRoom}
                        onChange={(event) => setChosenRoom(event.target.value)}
                        options={freeRooms.map((room) => ({
                          value: String(room.id),
                          label: roomOptionLabel(room),
                        }))}
                      />
                    )
                  }
                </DataState>
                {allocate.error ? <ErrorNotice error={allocate.error} title="Could not allocate" /> : null}
              </div>
            </Dialog>

            <ConfirmDialog
              open={vacateOpen}
              title="Vacate this room?"
              destructive
              confirmLabel="Vacate"
              pending={vacate.running}
              error={vacate.error}
              body={
                <p>
                  {detail.fullName} will be released from{' '}
                  <span className="mono">{detail.currentRoom?.roomName}</span> and the bed becomes
                  free immediately. The allocation stays in the history.
                </p>
              }
              onConfirm={async () => {
                const done = await vacate.run();
                if (done !== undefined) {
                  setVacateOpen(false);
                  refreshAfterRoomChange();
                }
              }}
              onCancel={() => setVacateOpen(false)}
            />
          </>
        )}
      </DataState>
    </>
  );
}

/** `LH-A-101 · 2nd year · 1 of 3 free` -- everything needed to choose without leaving. */
function roomOptionLabel(room: Room): string {
  const rules = [
    room.eligibleYear === null ? null : yearLabel(room.eligibleYear),
    room.eligibleGender === null ? null : genderLabel(room.eligibleGender),
  ].filter(Boolean);
  const suffix = rules.length > 0 ? ` · ${rules.join(', ')}` : '';
  return `${room.roomName}${suffix} · ${plural(room.freeBeds, 'free bed')} of ${room.capacity}`;
}
