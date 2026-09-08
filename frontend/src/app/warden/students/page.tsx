'use client';

/**
 * The student roster: search, two filters, and a page of rows.
 *
 * <p>Filtering happens on the server -- `query`, `yearOfStudy` and `allocationStatus`
 * are all query parameters -- rather than by fetching everything and filtering here.
 * A hostel is a few hundred students today, so client-side filtering would work; it
 * would also mean the page's correctness depended on the dataset staying small, and
 * the endpoint already does the work.
 *
 * <p>Two details that pages like this usually get wrong:
 *
 * <ul>
 *   <li><b>The search is debounced.</b> Bound straight to `onChange`, a nine-character
 *       roll number is nine requests, of which eight are already stale when they
 *       land.</li>
 *   <li><b>Changing a filter resets to page 0.</b> Without that, narrowing a filter
 *       while on page 4 asks for a page that no longer exists and the table goes
 *       blank -- which reads as "no students match" rather than "wrong page".</li>
 * </ul>
 */

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { warden } from '@/lib/endpoints';
import { useQuery } from '@/lib/use-query';
import { genderLabel } from '@/lib/format';
import { ALLOCATION_STATUSES } from '@/lib/types';
import {
  Avatar,
  Card,
  DataState,
  EmptyState,
  enumOptions,
  PageHead,
  Pager,
  SearchInput,
  SelectField,
  TableWrap,
} from '@/components/ui';
import { IconSearch } from '@/components/icons';
import { AllocationStatusBadge } from '@/components/status-badges';

const YEAR_OPTIONS = [1, 2, 3, 4, 5].map((year) => ({ value: String(year), label: `Year ${year}` }));

export default function WardenStudentsPage() {
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [year, setYear] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);

  // 300ms: long enough that ordinary typing produces one request, short enough that
  // it does not feel like the table is lagging behind the box.
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  const query = useQuery(
    (signal) =>
      warden.students.list(
        {
          page,
          size: 20,
          query: debouncedSearch || undefined,
          yearOfStudy: year ? Number(year) : undefined,
          allocationStatus: status || undefined,
        },
        signal,
      ),
    [page, debouncedSearch, year, status],
  );

  return (
    <>
      <PageHead title="Students" subtitle="Everyone in your hostel scope" />

      <Card>
        <div className="filters">
          {/* The search box carries a glyph and `type="search"`, so a phone keyboard
              offers the right return key and the browser its own clear button. The
              debounce above is what makes it safe to bind straight to state. */}
          <SearchInput
            label="Search students"
            placeholder="Name or roll number"
            value={search}
            onChange={setSearch}
          />
          <SelectField
            label="Year"
            placeholder="Any year"
            options={YEAR_OPTIONS}
            value={year}
            onChange={(event) => {
              setYear(event.target.value);
              setPage(0);
            }}
          />
          <SelectField
            label="Allocation"
            placeholder="Any status"
            options={enumOptions(ALLOCATION_STATUSES)}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          />
        </div>
      </Card>

      <Card flush>
        <DataState query={query} skeletonRows={8} errorTitle="Could not load students">
          {(data) =>
            data.content.length === 0 ? (
              <EmptyState title="No students match" icon={IconSearch}>
                Try clearing the filters, or search a different roll number.
              </EmptyState>
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th>Roll number</th>
                      <th>Name</th>
                      <th>Year</th>
                      <th>Branch</th>
                      <th>Gender</th>
                      <th className="shrink">Allocation</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((student) => (
                      <tr key={student.id}>
                        <td className="mono">{student.rollNumber}</td>
                        <td>
                          <span className="row-tight">
                            <Avatar name={student.fullName} size="sm" />
                            <Link href={`/warden/students/${student.id}`} className="cell-strong">
                              {student.fullName}
                            </Link>
                          </span>
                        </td>
                        <td className="nums">{student.yearOfStudy}</td>
                        <td>{student.branch ?? '--'}</td>
                        <td>{genderLabel(student.gender)}</td>
                        <td className="shrink">
                          <AllocationStatusBadge status={student.allocationStatus} />
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
                    busy={query.loading}
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
