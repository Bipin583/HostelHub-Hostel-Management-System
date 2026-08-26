'use client';

/**
 * File a complaint, and follow the ones already filed.
 *
 * <p><b>There is no detail page and that is deliberate.</b> A warden needs one -- they
 * work a queue of hundreds and need a permalink to a single report. A student has a
 * handful of complaints and wants to see all of them at once, so this list shows the
 * whole description and the whole resolution note inline. Adding `/student/complaints/[id]`
 * would be a second screen showing strictly less than this one.
 *
 * <p><b>Urgency is a request, not a promise.</b> The form offers the four levels the
 * backend accepts, and the page says plainly that a warden decides what gets worked
 * first -- otherwise every complaint arrives CRITICAL and the field stops carrying
 * information.
 *
 * <p>A student cannot change the status of their own complaint. There is no endpoint for
 * it, and there should not be: "resolved" is an assertion by whoever did the work, and a
 * resolution note the student wrote about their own complaint would tell a warden
 * nothing.
 */

import { useState } from 'react';
import { student } from '@/lib/endpoints';
import { useAction, useQuery } from '@/lib/use-query';
import { formatDateTime, humanise } from '@/lib/format';
import { COMPLAINT_CATEGORIES, COMPLAINT_URGENCIES, type ComplaintCategory, type ComplaintUrgency } from '@/lib/types';
import {
  Button,
  Card,
  DataState,
  EmptyState,
  enumOptions,
  ErrorNotice,
  Notice,
  PageHead,
  Pager,
  SelectField,
  TextAreaField,
  TextField,
} from '@/components/ui';
import { Dialog } from '@/components/dialog';
import { ComplaintStatusBadge, UrgencyMark } from '@/components/status-badges';

export default function StudentComplaintsPage() {
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);

  const complaints = useQuery(
    (signal) => student.complaints.list({ page, size: 10 }, signal),
    [page],
  );

  return (
    <>
      <PageHead
        title="Complaints"
        subtitle="Maintenance and welfare reports you have filed"
        actions={
          <Button variant="primary" onClick={() => setCreateOpen(true)}>
            File a complaint
          </Button>
        }
      />

      <DataState query={complaints} skeletonRows={5} errorTitle="Could not load your complaints">
        {(data) =>
          data.content.length === 0 ? (
            <Card>
              <EmptyState
                title="Nothing filed"
                action={
                  <Button variant="primary" onClick={() => setCreateOpen(true)}>
                    File a complaint
                  </Button>
                }
              >
                Report anything broken, unsafe or unhygienic. Your warden sees it immediately.
              </EmptyState>
            </Card>
          ) : (
            <>
              {data.content.map((complaint) => (
                <Card
                  key={complaint.id}
                  title={complaint.title}
                  subtitle={`${humanise(complaint.category)} · filed ${formatDateTime(complaint.createdAt)}`}
                  actions={<ComplaintStatusBadge status={complaint.status} />}
                  footer={
                    <span className="small muted">
                      <UrgencyMark urgency={complaint.urgency} />
                      {complaint.inProgressAt !== null ? (
                        <> · picked up {formatDateTime(complaint.inProgressAt)}</>
                      ) : null}
                      {complaint.resolvedAt !== null ? (
                        <> · resolved {formatDateTime(complaint.resolvedAt)}</>
                      ) : null}
                    </span>
                  }
                >
                  {/* pre-wrap so the paragraphs a student typed survive the round trip.
                      This is the same report the warden reads, rendered the same way. */}
                  <p style={{ whiteSpace: 'pre-wrap' }}>{complaint.description}</p>

                  {complaint.resolutionNote !== null ? (
                    <Notice
                      tone="success"
                      title={`Resolved by ${complaint.resolvedByName ?? 'the hostel team'}`}
                    >
                      <span style={{ whiteSpace: 'pre-wrap' }}>{complaint.resolutionNote}</span>
                    </Notice>
                  ) : complaint.status === 'IN_PROGRESS' ? (
                    <Notice tone="info" title="Somebody is on it">
                      A warden has picked this up. You will see what was done here when it is
                      closed.
                    </Notice>
                  ) : null}
                </Card>
              ))}

              <Card>
                <Pager
                  page={data.number}
                  totalPages={data.totalPages}
                  totalElements={data.totalElements}
                  shown={data.numberOfElements}
                  onPage={setPage}
                  busy={complaints.loading}
                />
              </Card>
            </>
          )
        }
      </DataState>

      <FileComplaintDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          setPage(0);
          complaints.refetch();
        }}
      />
    </>
  );
}

function FileComplaintDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<string>('');
  const [urgency, setUrgency] = useState<string>('MEDIUM');

  const create = useAction(() =>
    student.complaints.create({
      title: title.trim(),
      description: description.trim(),
      category: category as ComplaintCategory,
      urgency: urgency as ComplaintUrgency,
    }),
  );

  const ready = title.trim() !== '' && description.trim() !== '' && category !== '';

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="File a complaint"
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
              const filed = await create.run();
              if (filed) {
                setTitle('');
                setDescription('');
                setCategory('');
                setUrgency('MEDIUM');
                onCreated();
              }
            }}
          >
            File it
          </Button>
        </>
      }
    >
      <div className="stack-sm">
        <TextField
          label="What is wrong"
          maxLength={200}
          value={title}
          placeholder="Ceiling fan in 214 has stopped"
          onChange={(event) => setTitle(event.target.value)}
        />
        <TextAreaField
          label="Details"
          rows={5}
          value={description}
          hint="Where it is, when it started, and anything already tried. Specifics get it fixed faster."
          onChange={(event) => setDescription(event.target.value)}
        />
        <div className="form-grid">
          <SelectField
            label="Category"
            placeholder="Choose one"
            options={enumOptions(COMPLAINT_CATEGORIES)}
            value={category}
            onChange={(event) => setCategory(event.target.value)}
          />
          <SelectField
            label="Urgency"
            options={enumOptions(COMPLAINT_URGENCIES)}
            value={urgency}
            onChange={(event) => setUrgency(event.target.value)}
            hint="A warden decides the order work is done in."
          />
        </div>

        {create.error ? (
          <ErrorNotice error={create.error} title="The complaint was not filed" />
        ) : null}
      </div>
    </Dialog>
  );
}
