'use client';

/**
 * The student's own record, and the three fields of it they are allowed to change.
 *
 * <p><b>Most of this page is read-only, and that is the point.</b> Roll number, name,
 * gender, year, hostel and room are shown but not editable -- they are the registry's
 * facts about a student, and letting a resident rewrite their own year of study or move
 * themselves to another hostel would turn every downstream rule (notice audiences,
 * allocation eligibility, hostel scope) into a suggestion. `PUT /student/me` accepts
 * exactly three fields, so this form offers exactly three.
 *
 * <p><b>The phone check mirrors the server's validator instead of inventing one.</b>
 * `@PhoneNumber` normalises away spaces, brackets, dashes and dots plus an optional
 * country code, then requires ten digits starting 6-9. `looksLikePhone` below does the
 * same thing so a student who types "+91 98765 43210" is told it is fine -- which it is
 * -- rather than being blocked by a stricter frontend rule than the API has. The server
 * still validates; this only avoids a pointless round trip.
 *
 * <p>Field-level errors from the server are rendered by `ErrorNotice`, which reads
 * `ApiError.fieldErrors()`. So if the two checks ever drift, the server's message is
 * what the student reads.
 */

import { useEffect, useState } from 'react';
import { student } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { genderLabel, hostelLabel, yearLabel } from '@/lib/format';
import type { StudentDetail } from '@/lib/types';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  TextField,
} from '@/components/ui';
import { AllocationStatusBadge } from '@/components/status-badges';

export default function StudentProfilePage() {
  const me = useQuery((signal) => student.me.get(signal));
  const roommates = useQuery((signal) => student.me.roommates(signal));

  return (
    <>
      <PageHead title="Profile" subtitle="Your record as the hostel office holds it" />

      <DataState query={me} skeletonRows={6} errorTitle="Could not load your record">
        {(detail) => (
          <>
            <Card title="On record" subtitle="Set by the hostel office; ask them to correct a mistake">
              <dl className="facts">
                <dt className="fact-label">Name</dt>
                <dd className="fact-value">{detail.fullName}</dd>
                <dt className="fact-label">Roll number</dt>
                <dd className="fact-value mono">{detail.rollNumber}</dd>
                <dt className="fact-label">Email</dt>
                <dd className="fact-value">{detail.email}</dd>
                <dt className="fact-label">Gender</dt>
                <dd className="fact-value">{genderLabel(detail.gender)}</dd>
                <dt className="fact-label">Year</dt>
                <dd className="fact-value">{yearLabel(detail.yearOfStudy)}</dd>
                <dt className="fact-label">Allocation</dt>
                <dd className="fact-value">
                  <AllocationStatusBadge status={detail.allocationStatus} />
                </dd>
                <dt className="fact-label">Room</dt>
                <dd className="fact-value">
                  {detail.currentRoom === null ? (
                    <span className="faint">Not allocated</span>
                  ) : (
                    <>
                      <span className="mono">{detail.currentRoom.roomName}</span>
                      <span className="cell-sub">{hostelLabel(detail.currentRoom.hostelType)}</span>
                    </>
                  )}
                </dd>
              </dl>
            </Card>

            <ContactForm detail={detail} onSaved={() => me.refetch()} />
          </>
        )}
      </DataState>

      <Card title="Roommates" subtitle="Everyone else currently allocated to your room">
        <DataState query={roommates} skeletonRows={3} errorTitle="Could not load roommates">
          {(others) =>
            others.length === 0 ? (
              <EmptyState title="Nobody else here">
                Either the room is yours alone or you have not been allocated yet.
              </EmptyState>
            ) : (
              <ul className="stack-sm">
                {others.map((mate) => (
                  <li key={mate.id}>
                    <span className="cell-strong">{mate.fullName}</span>
                    {/* Roll number and year only. A resident does not get a directory of
                        other residents' phone numbers from this endpoint, and should
                        not: the roommate list exists so you know who you live with, not
                        so the hostel becomes a contact database. */}
                    <span className="cell-sub mono">
                      {mate.rollNumber} · {yearLabel(mate.yearOfStudy)}
                    </span>
                  </li>
                ))}
              </ul>
            )
          }
        </DataState>
      </Card>
    </>
  );
}

function ContactForm({ detail, onSaved }: { detail: StudentDetail; onSaved: () => void }) {
  const [mobileNo, setMobileNo] = useState(detail.mobileNo ?? '');
  const [parentMobileNo, setParentMobileNo] = useState(detail.parentMobileNo ?? '');
  const [branch, setBranch] = useState(detail.branch ?? '');
  const [saved, setSaved] = useState(false);

  // Refilled when the record is refetched -- after a save the server may have
  // normalised what was typed ("+91 98765 43210" is stored as ten digits), and the
  // inputs should show what is actually on record rather than the draft that produced
  // it. Keyed on the values themselves, so a refetch that changed nothing is a no-op.
  useEffect(() => {
    setMobileNo(detail.mobileNo ?? '');
    setParentMobileNo(detail.parentMobileNo ?? '');
    setBranch(detail.branch ?? '');
  }, [detail.mobileNo, detail.parentMobileNo, detail.branch]);

  const save = useAction(() =>
    student.me.update({
      mobileNo: mobileNo.trim(),
      // Blank is sent as null, not "": the column is nullable and an empty string is
      // not a phone number. The server's validator passes null and rejects "".
      parentMobileNo: parentMobileNo.trim() === '' ? null : parentMobileNo.trim(),
      branch: branch.trim() === '' ? null : branch.trim(),
    }),
  );

  const mobileError =
    mobileNo.trim() === ''
      ? 'A contact number is required.'
      : looksLikePhone(mobileNo)
        ? undefined
        : 'Ten digits starting 6-9. Spaces, dashes and +91 are fine.';
  const parentError =
    parentMobileNo.trim() === '' || looksLikePhone(parentMobileNo)
      ? undefined
      : 'Ten digits starting 6-9, or leave it blank.';
  const branchError = branch.trim().length > 100 ? 'Branch name is too long.' : undefined;

  const dirty =
    mobileNo.trim() !== (detail.mobileNo ?? '') ||
    parentMobileNo.trim() !== (detail.parentMobileNo ?? '') ||
    branch.trim() !== (detail.branch ?? '');
  const ready = dirty && !mobileError && !parentError && !branchError;

  return (
    <Card
      title="Contact details"
      subtitle="The only fields you can change yourself"
      footer={
        <Button
          variant="primary"
          disabled={!ready}
          pending={save.running}
          onClick={async () => {
            const updated = await save.run();
            if (updated) {
              setSaved(true);
              onSaved();
            }
          }}
        >
          Save changes
        </Button>
      }
    >
      <div className="stack-sm">
        <TextField
          label="Your mobile"
          inputMode="tel"
          autoComplete="tel"
          value={mobileNo}
          error={mobileNo === '' ? undefined : mobileError}
          hint="Used when the warden needs to reach you directly."
          onChange={(event) => {
            setMobileNo(event.target.value);
            setSaved(false);
          }}
        />
        <TextField
          label="Parent or guardian mobile (optional)"
          inputMode="tel"
          value={parentMobileNo}
          error={parentError}
          hint="Contacted for absence alerts and emergencies."
          onChange={(event) => {
            setParentMobileNo(event.target.value);
            setSaved(false);
          }}
        />
        <TextField
          label="Branch (optional)"
          maxLength={100}
          value={branch}
          error={branchError}
          placeholder="Computer Science and Engineering"
          onChange={(event) => {
            setBranch(event.target.value);
            setSaved(false);
          }}
        />

        {save.error ? <ErrorNotice error={save.error} title="Your details were not saved" /> : null}
        {saved && !dirty && !save.error ? (
          <Notice tone="success" title="Saved">
            Your contact details are up to date.
          </Notice>
        ) : null}
      </div>
    </Card>
  );
}

/**
 * The client-side half of `@PhoneNumber`.
 *
 * <p>Deliberately identical to `PhoneNumberValidator.normalise` followed by
 * `^[6-9][0-9]{9}$`: strip presentation characters, drop an optional country code, then
 * require ten digits in the Indian mobile range. Keeping the two in step matters more
 * than tightening this one -- a frontend rule stricter than the API rejects input the
 * system would have accepted, and the user has no way to tell which layer said no.
 */
function looksLikePhone(value: string): boolean {
  const stripped = value.replace(/[\s()\-.]/g, '');
  const national = stripped.replace(/^(?:\+?91|0)/, '');
  return /^[6-9][0-9]{9}$/.test(national);
}
