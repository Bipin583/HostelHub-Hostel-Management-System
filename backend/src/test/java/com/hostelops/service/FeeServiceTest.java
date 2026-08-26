package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hostelops.domain.FeeStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelFee;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.dto.fee.FeeCreateRequest;
import com.hostelops.dto.fee.FeeResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.FeeMapper;
import com.hostelops.mapper.RoomMapper;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.HostelFeeRepository;
import com.hostelops.repository.StudentRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * What {@link FeeService} promises, which is mostly about what it refuses to do.
 *
 * <p>The class's central claim is negative: it does not move money. Every rupee of
 * {@code amount_paid_paise} has a {@code fee_payments} row behind it because
 * {@link PaymentService} is the only writer, so the tests here assert the absence of
 * that write -- notably that cancelling an invoice with a part payment against it leaves
 * the part payment on the row, where it still has to be explained.
 *
 * <p>The other testable claims are about scope. Every staff path resolves its row through
 * a finder that takes {@code visibleGenders()}, and every student path through one that
 * takes the student id from the token. The assertions therefore check which finder was
 * called and with which genders, rather than checking that a returned object has the
 * fields it was given -- {@code create} setting a title is a field assignment and proves
 * nothing.
 *
 * <p>{@link FeeMapper} is real, assembled from the real {@link StudentMapper} and
 * {@link RoomMapper}. A mocked mapper would let these tests assert a {@link FeeResponse}
 * that production cannot produce, and the derived fields ({@code outstandingPaise},
 * {@code overdue}) are exactly the ones worth seeing come out of the genuine arithmetic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeService")
class FeeServiceTest {

    private static final long WARDEN_USER_ID = 7L;
    private static final long STUDENT_USER_ID = 41L;
    private static final long ADMIN_USER_ID = 1L;
    private static final long STUDENT_ID = 4L;
    private static final long FEE_ID = 900L;

    /** An LH warden, so {@code visibleGenders()} is exactly {@code {F}} and not "everything". */
    private static final AccessScope LADIES_WARDEN =
            new AccessScope(WARDEN_USER_ID, Role.WARDEN, HostelScope.LH, null);

    @Mock private HostelFeeRepository fees;
    @Mock private StudentRepository students;
    @Mock private CurrentUserProvider currentUser;

    private FeeService service;

    @BeforeEach
    void setUp() {
        service = new FeeService(
                fees, students, new FeeMapper(new StudentMapper(new RoomMapper())), currentUser);
    }

    @Nested
    @DisplayName("raising an invoice")
    class Creating {

        @Test
        @DisplayName("flushes, so the duplicate-term constraint is a 409 from this call")
        void flushesSoTheTermConstraintFiresHere() {
            asLadiesWarden();
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), any())).thenReturn(Optional.of(student()));
            when(fees.saveAndFlush(any(HostelFee.class))).thenAnswer(invocation -> {
                HostelFee fee = invocation.getArgument(0);
                fee.setId(FEE_ID);
                return fee;
            });

            FeeResponse response = service.create(request("Hostel fee 2026-27 ODD", null));

            // saveAndFlush rather than save: uq_hostel_fees_term has to fire inside the
            // method, where GlobalExceptionHandler can name the clash, rather than at
            // commit after a FeeResponse has already been handed back.
            ArgumentCaptor<HostelFee> saved = ArgumentCaptor.forClass(HostelFee.class);
            verify(fees).saveAndFlush(saved.capture());
            verify(fees, never()).save(any());

            HostelFee fee = saved.getValue();
            // Neither the status nor the paid amount is taken from the request. An invoice
            // is born UNPAID with nothing against it, and only the arithmetic in
            // recalculateStatus moves it after that -- which is the same reason there is no
            // "adjust the paid amount" method on this service at all.
            assertThat(fee.getStatus()).isEqualTo(FeeStatus.UNPAID);
            assertThat(fee.getAmountPaidPaise()).isEqualTo(0L);
            assertThat(fee.getStudent().getId()).isEqualTo(STUDENT_ID);

            assertThat(response.amountPaidPaise()).isEqualTo(0L);
            assertThat(response.outstandingPaise()).isEqualTo(50_000L);
            assertThat(response.status()).isEqualTo(FeeStatus.UNPAID);
            assertThat(response.student().id()).isEqualTo(STUDENT_ID);
            assertThat(response.student().fullName()).isEqualTo("Asha Menon");
        }

        @Test
        @DisplayName("strips the term fields and stores a blank description as null")
        void stripsTheTermFields() {
            asLadiesWarden();
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), any())).thenReturn(Optional.of(student()));
            when(fees.saveAndFlush(any(HostelFee.class))).thenAnswer(invocation -> invocation.getArgument(0));

            service.create(new FeeCreateRequest(
                    STUDENT_ID, "  Hostel fee  ", " 2026-27 ", " ODD ", 50_000L,
                    LocalDate.of(2026, 9, 30), "   "));

            ArgumentCaptor<HostelFee> saved = ArgumentCaptor.forClass(HostelFee.class);
            verify(fees).saveAndFlush(saved.capture());
            HostelFee fee = saved.getValue();

            // uq_hostel_fees_term keys on (student, academic_year, semester, title), so a
            // trailing space is the difference between a duplicate the constraint catches
            // and a second invoice for the same term that it does not.
            assertThat(fee.getTitle()).isEqualTo("Hostel fee");
            assertThat(fee.getAcademicYear()).isEqualTo("2026-27");
            assertThat(fee.getSemester()).isEqualTo("ODD");
            // "No description" is one value in the column rather than three that all render
            // as nothing.
            assertThat(fee.getDescription()).isNull();
        }

        @Test
        @DisplayName("bills through the scoped finder, so another hostel's student is a 404")
        void billsThroughTheScopedFinder() {
            asLadiesWarden();
            when(students.findByIdAndGenderIn(eq(STUDENT_ID), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request("Hostel fee", null)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            // The genders are asserted rather than merely "some collection was passed": an
            // LH warden who could bill an MH student would be reaching into a hostel they
            // cannot otherwise see, and the invoice would be real.
            verify(students).findByIdAndGenderIn(STUDENT_ID, EnumSet.of(Gender.F));
            verify(fees, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("listing invoices")
    class Listing {

        private final Pageable pageable = PageRequest.of(0, 20);

        @Test
        @DisplayName("no status filter goes to the unfiltered scoped query")
        void noStatusUsesTheUnfilteredQuery() {
            asLadiesWarden();
            when(fees.findInScope(any(), eq(pageable))).thenReturn(onePage(unpaidFee(50_000L)));

            Page<FeeResponse> page = service.list(null, pageable);

            assertThat(page.getContent()).hasSize(1);
            verify(fees).findInScope(EnumSet.of(Gender.F), pageable);
            // Two repository methods rather than one query with a nullable predicate, so
            // the "all statuses" case cannot accidentally become "status IS NULL".
            verify(fees, never()).findInScopeByStatus(any(), any(), any());
        }

        @Test
        @DisplayName("a status filter goes to the filtered scoped query")
        void aStatusUsesTheFilteredQuery() {
            asLadiesWarden();
            when(fees.findInScopeByStatus(any(), eq(FeeStatus.UNPAID), eq(pageable)))
                    .thenReturn(onePage(unpaidFee(50_000L)));

            service.list(FeeStatus.UNPAID, pageable);

            verify(fees).findInScopeByStatus(EnumSet.of(Gender.F), FeeStatus.UNPAID, pageable);
            verify(fees, never()).findInScope(any(), any());
        }
    }

    @Nested
    @DisplayName("reading one invoice")
    class Reading {

        @Test
        @DisplayName("staff read through the gender-scoped finder and out of scope is a 404")
        void staffReadsAreScoped() {
            asLadiesWarden();
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            // 404 rather than 403: a 403 would confirm the invoice exists
                            // and tell the caller which hostel to go asking about.
                            .isEqualTo(ErrorCode.NOT_FOUND));

            verify(fees).findByIdAndStudentGenderIn(FEE_ID, EnumSet.of(Gender.F));
            verify(fees, never()).findById(anyLong());
        }

        @Test
        @DisplayName("overdue is derived from today, not stored on the row")
        void overdueIsDerived() {
            asLadiesWarden();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            HostelFee overdue = unpaidFee(50_000L);
            overdue.setDueDate(today.minusDays(1));
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.of(overdue));

            // Nothing has to run at midnight for an invoice to become overdue. The flag is
            // computed against the date the service reads in UTC -- the same date the
            // reminder job and the admin trigger use, so the three cannot disagree.
            assertThat(service.get(FEE_ID).overdue()).isTrue();

            overdue.setDueDate(today.plusDays(1));
            assertThat(service.get(FEE_ID).overdue()).isFalse();
        }

        @Test
        @DisplayName("a student's own invoice is filtered in the query, not fetched and then checked")
        void studentReadsAreFilteredInTheQuery() {
            asStudent();
            when(fees.findByIdAndStudentId(FEE_ID, STUDENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.ownFee(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            // There is deliberately no shared findById that both the staff and student
            // paths call. Fetching first and comparing afterwards is where the two scopes
            // get confused for each other, and where a probe distinguishes "not yours"
            // from "does not exist".
            verify(fees, never()).findById(anyLong());
            verify(fees, never()).findByIdAndStudentGenderIn(anyLong(), any());
        }

        @Test
        @DisplayName("staff cannot use the student route: there is no student id on the token")
        void staffCannotUseTheStudentRoute() {
            when(currentUser.scope())
                    .thenReturn(new AccessScope(ADMIN_USER_ID, Role.ADMIN, null, null));

            assertThatThrownBy(() -> service.mine())
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.FORBIDDEN));
            verifyNoInteractions(fees);
        }
    }

    @Nested
    @DisplayName("cancelling an invoice")
    class Cancelling {

        @Test
        @DisplayName("refuses a fully paid invoice, naming the status rather than throwing a 500")
        void refusesAPaidInvoice() {
            asLadiesWarden();
            HostelFee paid = unpaidFee(50_000L);
            paid.applyPayment(50_000L);
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.of(paid));

            // HostelFee.cancel() would throw IllegalStateException here. The service checks
            // first so the client gets a 409 naming the status instead of a 500 naming the
            // entity's backstop.
            assertThatThrownBy(() -> service.cancel(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.FEE_ALREADY_SETTLED);
                        assertThat(e.getDetails())
                                .containsEntry("feeId", FEE_ID)
                                .containsEntry("status", "PAID");
                    });
            assertThat(paid.getStatus()).isEqualTo(FeeStatus.PAID);
            verify(fees, never()).save(any());
        }

        @Test
        @DisplayName("refuses a second cancellation")
        void refusesASecondCancellation() {
            asLadiesWarden();
            HostelFee cancelled = unpaidFee(50_000L);
            cancelled.cancel();
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.of(cancelled));

            assertThatThrownBy(() -> service.cancel(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        ApiException e = (ApiException) thrown;
                        assertThat(e.getCode()).isEqualTo(ErrorCode.FEE_ALREADY_SETTLED);
                        assertThat(e.getMessage()).contains("already cancelled");
                    });
            verify(fees, never()).save(any());
        }

        @Test
        @DisplayName("writes off the balance but leaves a part payment on the row")
        void leavesPartPaymentsAlone() {
            asLadiesWarden();
            HostelFee partly = unpaidFee(50_000L);
            partly.applyPayment(30_000L);
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.of(partly));

            FeeResponse response = service.cancel(FEE_ID);

            verify(fees).save(partly);
            assertThat(partly.getStatus()).isEqualTo(FeeStatus.CANCELLED);
            // The billed figure and the money received both survive the write-off. Zeroing
            // amount_paid_paise here would erase the only record that 30,000 paise was ever
            // taken -- and a cancelled invoice with money against it still has to explain
            // where that money went.
            assertThat(partly.getAmountPaise()).isEqualTo(50_000L);
            assertThat(partly.getAmountPaidPaise()).isEqualTo(30_000L);
            // Nothing is owed any more, which is what cancelling means.
            assertThat(partly.outstandingPaise()).isEqualTo(0L);

            assertThat(response.status()).isEqualTo(FeeStatus.CANCELLED);
            assertThat(response.amountPaidPaise()).isEqualTo(30_000L);
            assertThat(response.outstandingPaise()).isEqualTo(0L);
            assertThat(response.overdue()).isFalse();
        }

        @Test
        @DisplayName("cancels through the scoped finder, so another hostel's invoice is a 404")
        void cancelIsScoped() {
            asLadiesWarden();
            when(fees.findByIdAndStudentGenderIn(eq(FEE_ID), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(FEE_ID))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
            verify(fees).findByIdAndStudentGenderIn(FEE_ID, EnumSet.of(Gender.F));
            verify(fees, never()).save(any());
        }
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private void asLadiesWarden() {
        lenient().when(currentUser.scope()).thenReturn(LADIES_WARDEN);
    }

    private void asStudent() {
        lenient().when(currentUser.scope())
                .thenReturn(new AccessScope(STUDENT_USER_ID, Role.STUDENT, null, STUDENT_ID));
    }

    private static FeeCreateRequest request(String title, String description) {
        return new FeeCreateRequest(
                STUDENT_ID, title, "2026-27", "ODD", 50_000L, LocalDate.of(2026, 9, 30), description);
    }

    private static HostelFee unpaidFee(long amountPaise) {
        HostelFee fee = new HostelFee();
        fee.setId(FEE_ID);
        fee.setStudent(student());
        fee.setTitle("Hostel fee");
        fee.setAcademicYear("2026-27");
        fee.setSemester("ODD");
        fee.setAmountPaise(amountPaise);
        fee.setDueDate(LocalDate.of(2026, 9, 30));
        return fee;
    }

    /** Female, matching the LH warden's scope, and with the account the mapper reads a name from. */
    private static Student student() {
        UserAccount account = new UserAccount();
        account.setId(STUDENT_USER_ID);
        account.setUsername("22cs001");
        account.setFullName("Asha Menon");
        account.setEmail("asha.menon@example.edu");
        account.setRole(Role.STUDENT);

        Student student = new Student();
        student.setId(STUDENT_ID);
        student.setUser(account);
        student.setRollNumber("22CS001");
        student.setGender(Gender.F);
        student.setYearOfStudy(2);
        student.setBranch("Computer Science");
        return student;
    }

    private static Page<HostelFee> onePage(HostelFee fee) {
        return new PageImpl<>(List.of(fee), PageRequest.of(0, 20), 1);
    }
}
