package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.domain.Allocation;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Notice;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.notice.NoticeCreateRequest;
import com.hostelops.dto.notice.NoticeResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.NoticeMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.NoticeRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The notice board.
 *
 * <h2>A warden cannot post outside their hostels, and the check is not on the hostel field</h2>
 *
 * <p>{@link Notice} stores its audience as three nullable columns where null means
 * everyone, so the default request body -- all three omitted -- addresses the whole campus.
 * That is right for an admin and wrong for a warden, and the fix is not simply to reject a
 * null {@code audienceHostelType}: an MH warden governs two hostel types, {@code BH} and
 * {@code MH}, and a single-valued column cannot say "both of mine and no others".
 *
 * <p>What it can say exactly is the gender. Every hostel houses one sex and the mapping is
 * a bijection -- women in LH, men in BH and MH, per {@link com.hostelops.domain.HostelScope}
 * -- so "all students of my scope's gender" and "all students in my hostels" are the same
 * set of people. A warden's notice therefore always has {@code audienceGender} overwritten
 * with their own scope's gender, whatever the request said, and {@code audienceHostelType}
 * is honoured only if it names a hostel they cover. Omitting the hostel narrows to their
 * students rather than widening to the campus.
 *
 * <p>An admin's request is taken as sent, because an admin's scope is the campus.
 *
 * <h2>Deleting is bounded by authorship, not by scope</h2>
 *
 * <p>The manage list deliberately shows a warden the campus-wide notices, since they need to
 * know what their students have been told. Being able to see one is not being able to
 * remove it, so deletion requires being its author or being an admin -- otherwise a warden
 * could take down the exam timetable.
 */
@Service
public class NoticeService {

    private final NoticeRepository notices;
    private final StudentRepository students;
    private final AllocationRepository allocations;
    private final UserAccountRepository users;
    private final NoticeMapper noticeMapper;
    private final CurrentUserProvider currentUser;

    public NoticeService(
            NoticeRepository notices,
            StudentRepository students,
            AllocationRepository allocations,
            UserAccountRepository users,
            NoticeMapper noticeMapper,
            CurrentUserProvider currentUser) {
        this.notices = notices;
        this.students = students;
        this.allocations = allocations;
        this.users = users;
        this.noticeMapper = noticeMapper;
        this.currentUser = currentUser;
    }

    /**
     * Posts a notice.
     *
     * <p>The author and the publication time come from the caller and the server clock; see
     * {@link NoticeCreateRequest} for why neither is a request field. The audience is
     * narrowed for a warden as described on this class.
     */
    @Audited(entity = AuditEntity.NOTICE, action = AuditAction.CREATE)
    @Transactional
    public NoticeResponse post(NoticeCreateRequest request) {
        AccessScope scope = currentUser.scope();
        UserAccount author = actor();

        Notice notice = new Notice();
        notice.setAuthor(author);
        notice.setTitle(request.title());
        notice.setBody(request.body());
        notice.setAudienceYear(request.audienceYear());
        notice.setExpiresAt(request.expiresAt());

        if (request.audienceHostelType() != null) {
            // 403 rather than a silent narrowing: a warden who names another hostel has
            // misunderstood something, and quietly rewriting their audience would leave them
            // believing the notice went where they aimed it.
            scope.requireVisible(request.audienceHostelType());
            notice.setAudienceHostelType(request.audienceHostelType());
        }

        notice.setAudienceGender(scope.isWarden() && scope.hostelScope() != null
                ? scope.hostelScope().gender()
                : request.audienceGender());

        return noticeMapper.toResponse(notices.save(notice), Instant.now());
    }

    /**
     * The calling student's feed: live notices addressed to them.
     *
     * <p>Filtered in SQL by {@code NoticeRepository.findFeedFor} rather than by loading
     * notices and testing {@link Notice#targets} in Java -- the predicate is the
     * authorization rule for the feed, and applying it after fetching would page over rows
     * the student may not read and return short pages as a result.
     *
     * <p>The hostel type comes from the student's active allocation and is null when they
     * have none, which is the case the query is written to handle: an applicant sees the
     * general notices and none of the building-specific ones.
     */
    @Transactional(readOnly = true)
    public Page<NoticeResponse> feed(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        Long studentId = scope.requireStudentId();

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        HostelType hostelType = allocations.findByStudentIdAndActiveTrue(studentId)
                .map(allocation -> allocation.getRoom().getHostelType())
                .orElse(null);

        // One instant for the whole page, so a feed rendered across a second boundary does
        // not judge its first and last notice against two different nows.
        Instant now = Instant.now();
        return notices
                .findFeedFor(student.getGender(), student.getYearOfStudy(), hostelType, now, pageable)
                .map(notice -> noticeMapper.toResponse(notice, now));
    }

    /**
     * The management list: everything the caller may administer, expired included.
     *
     * <p>Wider than the feed on purpose -- it is the answer to "what did we post last term"
     * -- and wider than what the caller may delete, which authorship decides.
     */
    @Transactional(readOnly = true)
    public Page<NoticeResponse> manageable(Pageable pageable) {
        Instant now = Instant.now();
        return notices.findManageableIn(currentUser.scope().visibleHostelTypes(), pageable)
                .map(notice -> noticeMapper.toResponse(notice, now));
    }

    /**
     * Takes a notice down.
     *
     * <p>A hard delete, not an {@code expiresAt = now} soft one. Expiring a notice is
     * already a first-class thing this table can express, and a notice posted in error --
     * the wrong hostel, the wrong date, a name that should not have been public -- is
     * exactly the case where leaving the row readable defeats the point of removing it. The
     * audit event records that it was deleted and by whom, so the act is not lost with the
     * row.
     *
     * <p>Deletion is by author or by admin. Scope is not the test: a warden can see the
     * campus-wide notices in {@link #manageable}, and being able to read the exam timetable
     * is not being able to take it down.
     */
    @Audited(entity = AuditEntity.NOTICE, action = AuditAction.DELETE, idParam = "noticeId")
    @Transactional
    public void delete(Long noticeId) {
        AccessScope scope = currentUser.scope();
        Notice notice = notices.findById(noticeId)
                .orElseThrow(() -> ApiException.notFound("notice", noticeId));

        boolean isAuthor = Objects.equals(notice.getAuthor().getId(), scope.userId());
        if (!scope.isAdmin() && !isAuthor) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "A notice can only be taken down by its author or an administrator",
                    Map.of("noticeId", noticeId, "author", notice.getAuthor().getFullName()));
        }

        notices.delete(notice);
    }

    /** How many notices the caller has posted. */
    @Transactional(readOnly = true)
    public long countMine() {
        return notices.countByAuthorId(currentUser.require().getUserId());
    }

    /**
     * The caller's account.
     *
     * <p>Loaded rather than referenced, because {@code notices.author_id} is
     * {@code ON DELETE RESTRICT} and the response renders the author's name -- see
     * {@link Notice}, where the reason an unattributed notice is unacceptable is argued.
     */
    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }
}
