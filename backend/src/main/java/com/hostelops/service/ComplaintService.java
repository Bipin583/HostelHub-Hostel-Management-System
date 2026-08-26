package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.Complaint;
import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.complaint.ComplaintAnalyticsResponse;
import com.hostelops.dto.complaint.ComplaintCreateRequest;
import com.hostelops.dto.complaint.ComplaintResponse;
import com.hostelops.dto.complaint.ComplaintStatusUpdateRequest;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.ComplaintMapper;
import com.hostelops.repository.ComplaintCategoryTotals;
import com.hostelops.repository.ComplaintRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Complaints: raising them, working them, and measuring how long that took.
 *
 * <h2>The state machine is not here</h2>
 *
 * <p>Which transitions are legal lives on {@link ComplaintStatus}, and stamping the
 * timestamps lives on {@link Complaint}. This class does the two things neither of those
 * can: it decides what an illegal move returns to the caller (409, with both states named,
 * rather than the entity's backstop {@code IllegalStateException} surfacing as a 500), and
 * it enforces the one rule that is about the request rather than the row -- resolving
 * requires saying what was done.
 *
 * <h2>Two ways in, two different scopes</h2>
 *
 * <p>A student reaches their own complaints through {@link #mine} and
 * {@link #ownComplaint}, which filter on the student id from the token and never take one
 * from the request. A warden reaches the queue through {@link #queue} and {@link #get},
 * which filter on {@code visibleGenders()}. Neither path can be reached with the other's
 * argument, which is why there is no single {@code findById} in this class that both share.
 *
 * <h2>The analytics are three answers, not one</h2>
 *
 * <p>Why resolution timings, backlog and the category grid all have to be reported together
 * is argued on {@link ComplaintAnalyticsResponse}. What is decided here is the shape of the
 * grid: the group-by returns only the cells that have rows, and
 * {@link #densify} fills every missing cell with an explicit zero -- the commitment
 * {@code ComplaintCategoryTotals} makes when it says "the service fills the gaps".
 */
@Service
public class ComplaintService {

    /**
     * Longest window the analytics may look back over.
     *
     * <p>Same ceiling as the attendance reports, for the same reason: the resolution
     * percentile over an academic year is a number somebody can act on, and the one over
     * all time is a number that never changes again.
     */
    private static final int MAX_ANALYTICS_DAYS = 366;

    private final ComplaintRepository complaints;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final ComplaintMapper complaintMapper;
    private final CurrentUserProvider currentUser;

    public ComplaintService(
            ComplaintRepository complaints,
            StudentRepository students,
            UserAccountRepository users,
            ComplaintMapper complaintMapper,
            CurrentUserProvider currentUser) {
        this.complaints = complaints;
        this.students = students;
        this.users = users;
        this.complaintMapper = complaintMapper;
        this.currentUser = currentUser;
    }

    /**
     * A student raises a complaint.
     *
     * <p>The author is {@code requireStudentId()}, never a field of the request -- see
     * {@link ComplaintCreateRequest}, which has no {@code studentId} for exactly this
     * reason. The status is not taken from the request either: a complaint is born
     * {@code OPEN} because {@link Complaint} initialises it that way.
     */
    @Audited(entity = AuditEntity.COMPLAINT, action = AuditAction.CREATE)
    @Transactional
    public ComplaintResponse raise(ComplaintCreateRequest request) {
        AccessScope scope = currentUser.scope();
        Long studentId = scope.requireStudentId();

        Student student = students.findByIdAndGenderIn(studentId, scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", studentId));

        Complaint complaint = new Complaint();
        complaint.setStudent(student);
        complaint.setTitle(request.title());
        complaint.setDescription(request.description());
        complaint.setCategory(request.category());
        complaint.setUrgency(request.urgency());

        return complaintMapper.toResponse(complaints.save(complaint));
    }

    /**
     * The warden's queue, optionally narrowed to one status.
     *
     * <p>A null status means every status, and the two cases go to two different repository
     * methods rather than one query with an {@code (:status is null or ...)} predicate --
     * the reasoning is on {@code ComplaintRepository.findInScopeByStatus}. The branch is
     * three lines here instead of an unindexable expression in SQL.
     */
    @Transactional(readOnly = true)
    public Page<ComplaintResponse> queue(ComplaintStatus status, Pageable pageable) {
        Set<Gender> genders = currentUser.scope().visibleGenders();
        Page<Complaint> page = status == null
                ? complaints.findInScope(genders, pageable)
                : complaints.findInScopeByStatus(genders, status, pageable);
        return page.map(complaintMapper::toResponse);
    }

    /** One complaint, as a warden sees it. Out of scope reads as 404 rather than 403. */
    @Transactional(readOnly = true)
    public ComplaintResponse get(Long complaintId) {
        return complaintMapper.toResponse(requireInScope(complaintId));
    }

    /** The caller's own complaints, newest first. */
    @Transactional(readOnly = true)
    public Page<ComplaintResponse> mine(Pageable pageable) {
        Long studentId = currentUser.scope().requireStudentId();
        return complaints.findForStudent(studentId, pageable).map(complaintMapper::toResponse);
    }

    /**
     * One of the caller's own complaints.
     *
     * <p>Filtered by student id in the query rather than fetched and then checked, so a
     * student probing ids gets the same 404 whether the complaint belongs to somebody else
     * or does not exist.
     */
    @Transactional(readOnly = true)
    public ComplaintResponse ownComplaint(Long complaintId) {
        Long studentId = currentUser.scope().requireStudentId();
        Complaint complaint = complaints.findByIdAndStudentId(complaintId, studentId)
                .orElseThrow(() -> ApiException.notFound("complaint", complaintId));
        return complaintMapper.toResponse(complaint);
    }

    /**
     * A warden moves a complaint forward.
     *
     * <p>The legality check is asked of the entity and the refusal is built here, so the
     * 409 can name both states: "IN_PROGRESS cannot become OPEN" is actionable, and the
     * generic conflict a caught {@code IllegalStateException} would produce is not.
     *
     * <p>Resolving without a note is refused. The note is the only field that says what was
     * actually done, and a resolution nobody described is indistinguishable from a
     * complaint somebody closed to clear their queue -- which is precisely the behaviour the
     * timestamps in this table exist to make visible. The rule lives here rather than in
     * bean validation because it is conditional on another field, which is argued on
     * {@link ComplaintStatusUpdateRequest}.
     */
    @Audited(entity = AuditEntity.COMPLAINT, action = AuditAction.UPDATE, idParam = "complaintId")
    @Transactional
    public ComplaintResponse updateStatus(Long complaintId, ComplaintStatusUpdateRequest request) {
        Complaint complaint = requireInScope(complaintId);
        ComplaintStatus next = request.status();

        if (!complaint.canTransitionTo(next)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A complaint that is " + complaint.getStatus() + " cannot become " + next,
                    Map.of("from", complaint.getStatus().name(), "to", next.name()));
        }

        String note = request.resolutionNote() == null ? null : request.resolutionNote().strip();
        if (next == ComplaintStatus.RESOLVED && (note == null || note.isEmpty())) {
            throw new ApiException(ErrorCode.RESOLUTION_NOTE_REQUIRED,
                    "Resolving a complaint requires a note saying what was done",
                    Map.of("complaintId", complaintId));
        }

        complaint.transitionTo(next, actor(), note, Instant.now());
        complaints.save(complaint);
        return complaintMapper.toResponse(complaint);
    }

    /**
     * The complaints dashboard for the caller's students.
     *
     * <p>Three queries, all aggregating in the database: the resolution timings over the
     * window, the backlog as it stands now, and the category grid. The window applies only
     * to the first -- a backlog is a present-tense fact, and narrowing it to the last ninety
     * days would hide the complaint from March that nobody has touched, which is the row the
     * number exists to surface.
     */
    @Transactional(readOnly = true)
    public ComplaintAnalyticsResponse analytics(int windowDays) {
        Set<Gender> genders = currentUser.scope().visibleGenders();
        Instant from = requireWindow(windowDays);

        // The native queries bind gender as text: an enum in a native parameter list is
        // ambiguous between its name and its ordinal, and this column stores the name.
        List<String> genderNames = genders.stream().map(Gender::name).toList();

        ComplaintRepository.ComplaintResolutionRow resolution =
                complaints.findResolutionStats(genderNames, from);
        ComplaintRepository.ComplaintBacklogRow backlog = complaints.findBacklog(genderNames);

        return new ComplaintAnalyticsResponse(
                new ComplaintAnalyticsResponse.Resolution(
                        resolution.getResolvedCount(),
                        resolution.getAvgTotalSeconds(),
                        resolution.getAvgQueueSeconds(),
                        resolution.getAvgWorkSeconds(),
                        resolution.getP90TotalSeconds()),
                new ComplaintAnalyticsResponse.Backlog(
                        backlog.getOpenCount(), backlog.getOldestOpenSeconds()),
                densify(complaints.findCategoryTotals(genders)));
    }

    /** Open complaints in the caller's scope, for the dashboard tile. */
    @Transactional(readOnly = true)
    public long countOpen() {
        return complaints.countByStudentGenderInAndStatus(
                currentUser.scope().visibleGenders(), ComplaintStatus.OPEN);
    }

    /** How many of the caller's own complaints are still not resolved. */
    @Transactional(readOnly = true)
    public long countMineUnresolved() {
        Long studentId = currentUser.scope().requireStudentId();
        return complaints.countByStudentIdAndStatusNot(studentId, ComplaintStatus.RESOLVED);
    }

    /**
     * Turns the sparse group-by into a full grid.
     *
     * <p>The query returns one row per {@code (category, status)} pair that has complaints
     * in it; a category nobody has complained about, or a category with nothing resolved,
     * simply is not there. This produces every category and, inside each, every status --
     * zeros included.
     *
     * <p>Absent cells are filled with zero rather than omitted because zero is what they
     * mean, and a client that has to decide whether a missing key is zero or unknown will
     * eventually decide wrong. Categories with no complaints at all are kept for the same
     * reason: a bar chart with a missing bar makes the reader do arithmetic to notice it.
     *
     * <p>Ordering follows the enum's declaration rather than the counts, so the chart's
     * categories stay in the same places between refreshes. A count-ordered chart animates
     * on every poll and is harder to read for it.
     */
    private List<ComplaintAnalyticsResponse.CategoryCount> densify(List<ComplaintCategoryTotals> rows) {
        Map<ComplaintCategory, Map<ComplaintStatus, Long>> counts = new HashMap<>();
        for (ComplaintCategoryTotals row : rows) {
            counts.computeIfAbsent(row.category(), category -> new EnumMap<>(ComplaintStatus.class))
                    .put(row.status(), row.count());
        }

        List<ComplaintAnalyticsResponse.CategoryCount> dense =
                new ArrayList<>(ComplaintCategory.values().length);
        for (ComplaintCategory category : ComplaintCategory.values()) {
            Map<ComplaintStatus, Long> present = counts.getOrDefault(category, Map.of());
            Map<ComplaintStatus, Long> byStatus = new EnumMap<>(ComplaintStatus.class);
            long total = 0;
            for (ComplaintStatus status : ComplaintStatus.values()) {
                long count = present.getOrDefault(status, 0L);
                byStatus.put(status, count);
                total += count;
            }
            dense.add(new ComplaintAnalyticsResponse.CategoryCount(category, total, byStatus));
        }
        return dense;
    }

    private Complaint requireInScope(Long complaintId) {
        return complaints.findByIdAndStudentGenderIn(complaintId, currentUser.scope().visibleGenders())
                .orElseThrow(() -> ApiException.notFound("complaint", complaintId));
    }

    private Instant requireWindow(int windowDays) {
        if (windowDays < 1 || windowDays > MAX_ANALYTICS_DAYS) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "The analytics window must be between 1 and " + MAX_ANALYTICS_DAYS + " days",
                    Map.of("windowDays", windowDays));
        }
        return Instant.now().minus(windowDays, ChronoUnit.DAYS);
    }

    private UserAccount actor() {
        Long userId = currentUser.require().getUserId();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL, "Acting account not found"));
    }
}
