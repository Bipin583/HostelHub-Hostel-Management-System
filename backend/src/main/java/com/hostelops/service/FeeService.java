package com.hostelops.service;

import com.hostelops.audit.AuditEntity;
import com.hostelops.audit.Audited;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.FeeStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelFee;
import com.hostelops.domain.Student;
import com.hostelops.dto.fee.FeeCollectionResponse;
import com.hostelops.dto.fee.FeeCreateRequest;
import com.hostelops.dto.fee.FeeResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.FeeMapper;
import com.hostelops.repository.HostelFeeRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoices: raising them, listing them, writing them off, and reporting on collection.
 *
 * <h2>This class does not move money</h2>
 *
 * <p>Nothing here credits an invoice. {@code amount_paid_paise} is written in exactly one
 * place -- {@link PaymentService}, under a row lock, in response to a verified gateway
 * callback -- and the split is the point. An "adjust the paid amount" method on the
 * invoice service is how a ledger acquires a way to mark a fee paid that no payment
 * explains, and this application's whole claim about its fee handling is that every rupee
 * of {@code amount_paid_paise} has a {@code fee_payments} row behind it.
 *
 * <p>{@link #cancel} is the one way an unpaid balance stops being owed, and it writes the
 * status rather than the amounts, so the original billed figure survives the write-off.
 *
 * <h2>Two ways in, two different scopes</h2>
 *
 * <p>Staff reach invoices through {@link #list}, {@link #get} and {@link #collections},
 * all filtered on {@code visibleGenders()}. A student reaches their own through
 * {@link #mine} and {@link #ownFee}, filtered on the student id from the token and never
 * on one from the request. As in {@link ComplaintService}, there is deliberately no shared
 * {@code findById} that both paths call: the two scopes are different questions and a
 * single loader is where they get confused for each other.
 *
 * <h2>Why the create path flushes</h2>
 *
 * <p>{@code uq_hostel_fees_term} makes one invoice per student per term per title. Billing
 * the same term twice is the mistake this prevents, and it is an easy one to make from a
 * spreadsheet import run twice. {@link #create} flushes so the constraint fires inside the
 * method, where {@code GlobalExceptionHandler} already turns it into a 409 naming the
 * clash, rather than at commit after a response has been built.
 */
@Service
public class FeeService {

    private final HostelFeeRepository fees;
    private final StudentRepository students;
    private final FeeMapper feeMapper;
    private final CurrentUserProvider currentUser;

    public FeeService(
            HostelFeeRepository fees,
            StudentRepository students,
            FeeMapper feeMapper,
            CurrentUserProvider currentUser) {
        this.fees = fees;
        this.students = students;
        this.feeMapper = feeMapper;
        this.currentUser = currentUser;
    }

    /**
     * Raises an invoice against a student.
     *
     * <p>The student is resolved through the scoped finder, so a warden billing a student
     * in the other hostel gets a 404 rather than an invoice. {@code status} is not taken
     * from the request -- {@link HostelFee} is born {@code UNPAID} and only the arithmetic
     * in {@code recalculateStatus} moves it after that.
     *
     * <p>The description is stripped to null when blank, so "no description" is one value
     * in the column rather than three that all render as nothing.
     */
    @Audited(entity = AuditEntity.FEE, action = AuditAction.CREATE)
    @Transactional
    public FeeResponse create(FeeCreateRequest request) {
        AccessScope scope = currentUser.scope();
        Student student = students.findByIdAndGenderIn(request.studentId(), scope.visibleGenders())
                .orElseThrow(() -> ApiException.notFound("student", request.studentId()));

        HostelFee fee = new HostelFee();
        fee.setStudent(student);
        fee.setTitle(request.title().strip());
        fee.setAcademicYear(request.academicYear().strip());
        fee.setSemester(request.semester().strip());
        fee.setAmountPaise(request.amountPaise());
        fee.setDueDate(request.dueDate());
        fee.setDescription(blankToNull(request.description()));

        // Flushed rather than queued, so uq_hostel_fees_term is a 409 from this call.
        return feeMapper.toResponse(fees.saveAndFlush(fee), today());
    }

    /**
     * The invoice list for the caller's students, optionally narrowed to one status.
     *
     * <p>Ordered by due date, so the invoices somebody has to chase are at the top. A null
     * status means every status, and the two cases go to two repository methods rather than
     * one query with a nullable predicate -- the same reasoning as
     * {@code ComplaintService.queue}.
     */
    @Transactional(readOnly = true)
    public Page<FeeResponse> list(FeeStatus status, Pageable pageable) {
        Set<Gender> genders = currentUser.scope().visibleGenders();
        LocalDate today = today();
        Page<HostelFee> page = status == null
                ? fees.findInScope(genders, pageable)
                : fees.findInScopeByStatus(genders, status, pageable);
        return page.map(fee -> feeMapper.toResponse(fee, today));
    }

    /** One invoice, as staff see it. Out of scope reads as 404 rather than 403. */
    @Transactional(readOnly = true)
    public FeeResponse get(Long feeId) {
        return feeMapper.toResponse(requireInScope(feeId), today());
    }

    /**
     * Writes an invoice off.
     *
     * <p>The legality check is the entity's -- a fully paid invoice cannot be cancelled,
     * because there is nothing left to forgive and the row would then claim money was
     * written off that was in fact collected. The refusal is built here so the 409 names
     * the status rather than surfacing the entity's backstop
     * {@code IllegalStateException} as a 500.
     *
     * <p>Part payments are deliberately left alone. A cancelled invoice with money against
     * it still has to explain where that money went, which is the argument on
     * {@link HostelFee#cancel()}; zeroing {@code amount_paid_paise} here would erase the
     * only record that it was ever received.
     */
    @Audited(entity = AuditEntity.FEE, action = AuditAction.UPDATE, idParam = "feeId")
    @Transactional
    public FeeResponse cancel(Long feeId) {
        HostelFee fee = requireInScope(feeId);
        if (fee.getStatus() == FeeStatus.PAID) {
            throw new ApiException(ErrorCode.FEE_ALREADY_SETTLED,
                    "A fully paid invoice cannot be cancelled",
                    Map.of("feeId", feeId, "status", fee.getStatus().name()));
        }
        if (fee.getStatus() == FeeStatus.CANCELLED) {
            throw new ApiException(ErrorCode.FEE_ALREADY_SETTLED,
                    "This invoice is already cancelled",
                    Map.of("feeId", feeId, "status", fee.getStatus().name()));
        }
        fee.cancel();
        fees.save(fee);
        return feeMapper.toResponse(fee, today());
    }

    /**
     * The caller's own invoices, newest due date first.
     *
     * <p>A list rather than a page: a student has one invoice per term, so the whole set is
     * a handful of rows and paging it would make the client ask twice for something it
     * always wants in full.
     *
     * <p>The finder does not fetch-join the student, unlike the staff-facing queries. It
     * does not need to: every row here belongs to the same student, so the one lazy load
     * is answered from the persistence context for all of them.
     */
    @Transactional(readOnly = true)
    public List<FeeResponse> mine() {
        Long studentId = currentUser.scope().requireStudentId();
        LocalDate today = today();
        return fees.findByStudentIdOrderByDueDateDesc(studentId).stream()
                .map(fee -> feeMapper.toResponse(fee, today))
                .toList();
    }

    /**
     * One of the caller's own invoices.
     *
     * <p>Filtered by student id in the query rather than fetched and then checked, so a
     * student probing ids gets the same 404 whether the invoice is somebody else's or does
     * not exist.
     */
    @Transactional(readOnly = true)
    public FeeResponse ownFee(Long feeId) {
        Long studentId = currentUser.scope().requireStudentId();
        HostelFee fee = fees.findByIdAndStudentId(feeId, studentId)
                .orElseThrow(() -> ApiException.notFound("fee", feeId));
        return feeMapper.toResponse(fee, today());
    }

    /**
     * The collections dashboard for the caller's students.
     *
     * <p>One query, aggregated in the database, grouped by term. The arithmetic that turns
     * it into a headline figure is {@code FeeMapper.toCollectionResponse}, which sums the
     * same rows the table shows so the two cannot disagree.
     */
    @Transactional(readOnly = true)
    public FeeCollectionResponse collections() {
        return feeMapper.toCollectionResponse(
                fees.findCollectionTotals(currentUser.scope().visibleGenders(), today()));
    }

    /** Invoices in the caller's scope with nothing paid against them, for the dashboard tile. */
    @Transactional(readOnly = true)
    public long countUnpaid() {
        return fees.countByStudentGenderInAndStatus(
                currentUser.scope().visibleGenders(), FeeStatus.UNPAID);
    }

    private HostelFee requireInScope(Long feeId) {
        return fees.findByIdAndStudentGenderIn(feeId, currentUser.scope().visibleGenders())
                .orElseThrow(() -> ApiException.notFound("fee", feeId));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Today in UTC, for the same reason {@code AttendanceService.today()} is. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
