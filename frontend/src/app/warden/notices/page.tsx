'use client';

/**
 * Notice board: what has been posted, and the form for posting.
 *
 * <p><b>The audience is three independent filters, not a list of recipients.</b> A
 * notice carries a nullable hostel type, gender and year, and a student sees it when
 * every non-null field matches them. That is why this form has three optional selects
 * rather than a multi-select of students: the server matches on attributes at read
 * time, so a notice aimed at "second year, ladies' hostel" keeps aiming there as
 * students arrive and leave. A snapshot list of ids would not.
 *
 * <p>The hostel options come from `HOSTELS_IN_SCOPE`, which mirrors
 * `HostelScope.hostelTypes()` on the server -- a warden of MH can post to BH and MH and
 * not to LH, and offering LH here would produce a 403 the user could not have
 * predicted. The scope is read from the session rather than fetched.
 *
 * <p><b>Expiry is a wall-clock time converted to an instant.</b> `datetime-local` gives
 * `2026-09-01T18:00` with no zone, and `new Date(...)` parses exactly that form in the
 * browser's own timezone -- which is what the user meant by "six in the evening". The
 * value sent is `toISOString()` of it, because the backend field is an `Instant`. This
 * is the one place in this app where feeding a date string to `new Date` is correct;
 * the rule against it applies to `LocalDate` values, which have no time and no zone.
 */

import { useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useCurrentUser } from '@/lib/auth';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime, genderLabel, hostelLabel, yearLabel } from '@/lib/format';
import { HOSTELS_IN_SCOPE, type Notice as HostelNotice } from '@/lib/types';
import {
  Badge,
  Button,
  Card,
  DataState,
  EmptyState,
  ErrorNotice,
  Notice,
  PageHead,
  Pager,
  SelectField,
  TableWrap,
  TextAreaField,
  TextField,
} from '@/components/ui';
import { ConfirmDialog, Dialog } from '@/components/dialog';

const GENDER_OPTIONS = [
  { value: 'M', label: genderLabel('M') },
  { value: 'F', label: genderLabel('F') },
];

const YEAR_OPTIONS = [1, 2, 3, 4, 5].map((year) => ({
  value: String(year),
  label: yearLabel(year),
}));

export default function WardenNoticesPage() {
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<HostelNotice | null>(null);

  const notices = useQuery((signal) => warden.notices.list({ page, size: 20 }, signal), [page]);
  const remove = useAction((noticeId: number) => warden.notices.remove(noticeId));

  return (
    <>
      <PageHead
        title="Notices"
        subtitle="Posted to the hostels you govern"
        actions={
          <Button variant="primary" onClick={() => setCreateOpen(true)}>
            Post a notice
          </Button>
        }
      />

      <Card flush>
        <DataState query={notices} skeletonRows={6} errorTitle="Could not load notices">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="Nothing posted">
                Notices you post appear on the dashboard of every student they match.
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Notice</th>
                      <th>Audience</th>
                      <th>Author</th>
                      <th>Published</th>
                      <th>Expires</th>
                      <th className="shrink">State</th>
                      <th className="shrink" />
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((notice) => (
                      <tr key={notice.id}>
                        <td>
                          <span className="cell-strong">{notice.title}</span>
                          {/* Two lines of the body in the table: enough to tell two
                              notices apart, which is the only job a list has here.
                              There is no detail page because there is nothing more to
                              show -- a notice is its title and its body. */}
                          <span className="cell-sub">{truncate(notice.body, 140)}</span>
                        </td>
                        {/* The server composes this label from the three audience
                            fields, so it cannot disagree with the matching rules the
                            way a locally-assembled string could. */}
                        <td className="small">{notice.audienceLabel}</td>
                        <td className="small">{notice.authorName ?? '--'}</td>
                        <td className="nowrap small">{formatDateTime(notice.publishedAt)}</td>
                        <td className="nowrap small">
                          {notice.expiresAt === null ? (
                            <span className="faint">Never</span>
                          ) : (
                            formatDateTime(notice.expiresAt)
                          )}
                        </td>
                        <td className="shrink">
                          {notice.live ? (
                            <Badge tone="success">Live</Badge>
                          ) : (
                            <Badge tone="neutral">Expired</Badge>
                          )}
                        </td>
                        <td className="shrink">
                          <Button small variant="danger" onClick={() => setDeleteTarget(notice)}>
                            Delete
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
                    busy={notices.loading}
                  />
                </div>
              </>
            )
          }
        </DataState>
      </Card>

      <CreateNoticeDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          setPage(0);
          notices.refetch();
        }}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete this notice?"
        destructive
        confirmLabel="Delete"
        pending={remove.running}
        error={remove.error}
        body={
          <p>
            &ldquo;{deleteTarget?.title}&rdquo; will stop appearing for every student it matches.
            An expiry date is usually the better tool -- deleting removes it from the record too.
          </p>
        }
        onConfirm={async () => {
          if (deleteTarget === null) return;
          // `del` resolves to void, so success is "not undefined" rather than a truthy
          // payload. `useAction` returns undefined only when the call threw.
          const done = await remove.run(deleteTarget.id);
          if (done !== undefined) {
            setDeleteTarget(null);
            notices.refetch();
          }
        }}
        onCancel={() => setDeleteTarget(null)}
      />
    </>
  );
}

function CreateNoticeDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const user = useCurrentUser();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [hostelType, setHostelType] = useState('');
  const [gender, setGender] = useState('');
  const [year, setYear] = useState('');
  const [expiresLocal, setExpiresLocal] = useState('');

  const hostelOptions = (user?.hostelScope ? HOSTELS_IN_SCOPE[user.hostelScope] : []).map(
    (type) => ({ value: type, label: hostelLabel(type) }),
  );

  const expiresAt = expiresLocal === '' ? null : localToInstant(expiresLocal);
  // The backend rejects an expiry that is already past, and this mirrors it rather than
  // using the input's `min` attribute: `min` would have to be rendered from a "now"
  // computed during the server render, in the server's timezone, and then disagree with
  // the browser's clock on hydration. Validating on the value the user typed avoids
  // needing to know the time before the page is interactive.
  const expiryInPast = expiresAt !== null && Date.parse(expiresAt) <= Date.now();
  const expiryUnparseable = expiresLocal !== '' && expiresAt === null;

  const create = useAction(() =>
    warden.notices.create({
      title: title.trim(),
      body: body.trim(),
      audienceHostelType: hostelType ? (hostelType as HostelNotice['audienceHostelType']) : null,
      audienceGender: gender ? (gender as HostelNotice['audienceGender']) : null,
      audienceYear: year ? Number(year) : null,
      expiresAt,
    }),
  );

  const ready =
    title.trim() !== '' && body.trim() !== '' && !expiryInPast && !expiryUnparseable;

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Post a notice"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={create.running}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={!ready}
            pending={create.running}
            onClick={async () => {
              const posted = await create.run();
              if (posted) {
                setTitle('');
                setBody('');
                setHostelType('');
                setGender('');
                setYear('');
                setExpiresLocal('');
                onCreated();
              }
            }}
          >
            Post
          </Button>
        </>
      }
    >
      <div className="stack-sm">
        <TextField
          label="Title"
          maxLength={200}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
        <TextAreaField
          label="Body"
          rows={5}
          value={body}
          hint="Line breaks are preserved for the reader."
          onChange={(event) => setBody(event.target.value)}
        />

        <Notice tone="info" title="Audience">
          Every field left blank is a filter not applied. All three blank posts to everyone in
          your scope; setting year to 1 and leaving the rest blank posts to every first-year.
        </Notice>

        <div className="form-grid">
          <SelectField
            label="Hostel"
            placeholder="Any in scope"
            options={hostelOptions}
            value={hostelType}
            onChange={(event) => setHostelType(event.target.value)}
            hint={user?.hostelScope ? undefined : 'No hostel scope on this account.'}
          />
          <SelectField
            label="Gender"
            placeholder="Any gender"
            options={GENDER_OPTIONS}
            value={gender}
            onChange={(event) => setGender(event.target.value)}
          />
          <SelectField
            label="Year"
            placeholder="Any year"
            options={YEAR_OPTIONS}
            value={year}
            onChange={(event) => setYear(event.target.value)}
          />
        </div>

        <TextField
          label="Expires (optional)"
          type="datetime-local"
          value={expiresLocal}
          hint="Your local time. Leave blank for a notice that never expires."
          error={
            expiryUnparseable
              ? 'That is not a date and time.'
              : expiryInPast
                ? 'The expiry has to be in the future.'
                : undefined
          }
          onChange={(event) => setExpiresLocal(event.target.value)}
        />

        {create.error ? <ErrorNotice error={create.error} title="The notice was not posted" /> : null}
      </div>
    </Dialog>
  );
}

/**
 * A `datetime-local` value as an ISO instant.
 *
 * <p>`new Date('2026-09-01T18:00')` is parsed in the browser's timezone -- the one case
 * where handing a date string to `Date` is right, because the user typed a wall-clock
 * time and meant their own wall clock. Returns null on an unparseable value so the
 * caller can show a message rather than sending `"Invalid Date"`.
 */
function localToInstant(local: string): string | null {
  const at = new Date(local);
  if (Number.isNaN(at.getTime())) return null;
  return at.toISOString();
}

/** Body preview for the table. Cuts on a word boundary so it does not end mid-word. */
function truncate(text: string, limit: number): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= limit) return flat;
  const cut = flat.slice(0, limit);
  const lastSpace = cut.lastIndexOf(' ');
  return `${lastSpace > limit * 0.6 ? cut.slice(0, lastSpace) : cut}…`;
}
