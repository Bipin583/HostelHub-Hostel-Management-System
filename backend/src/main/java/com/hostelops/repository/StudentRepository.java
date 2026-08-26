package com.hostelops.repository;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.Student;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Student queries, every one of which takes the caller's permitted genders.
 *
 * <p>This interface extends the bare {@link Repository} marker rather than
 * {@code JpaRepository} on purpose. {@code JpaRepository} would inherit
 * {@code findAll()}, {@code findAllById(...)} and friends -- unscoped finders
 * that a future endpoint could call in one line and leak every hostel's students
 * through. Here the only ways to reach a collection of students are the methods
 * below, and each one requires a {@code genders} argument, so omitting the scope
 * filter is a compile error rather than a data breach.
 *
 * <p>Callers pass {@code AccessScope#visibleGenders()}, which is the full set for
 * an admin and a single value for a warden. Same query, different width.
 *
 * <p>{@link StudentSearchFragment} adds the one filtered listing. It is a
 * hand-written Criteria query rather than a derived method because the filters are
 * optional and combinable; its {@code genders} parameter is mandatory for the same
 * reason every method here takes one.
 */
public interface StudentRepository extends Repository<Student, Long>, StudentSearchFragment {

    Student save(Student student);

    /** Scoped single fetch: a warden asking for another hostel's student gets empty. */
    Optional<Student> findByIdAndGenderIn(Long id, Collection<Gender> genders);

    /**
     * A handle to a student row, for attaching one to a new record.
     *
     * <p>The one method here that takes no {@code genders}, and it is defensible because it
     * reads nothing: it returns a lazy reference whose only populated field is the id, so
     * calling it can neither confirm that a student exists nor disclose anything about them.
     * Whether the id is real is settled by the foreign key when the owning row is inserted.
     *
     * <p>It exists for the absence scan, which has no caller to be scoped against -- it is a
     * system job over the whole register -- and which needs to point an alert at a student it
     * only knows by id. The alternative, a scoped {@code findById} with the full gender set
     * passed in, would be a fetch of a row nobody reads plus a lie about being scoped.
     *
     * <p>Declared explicitly rather than inherited: it is implemented by Spring Data's base
     * class, so naming it here is enough, and naming it means the exception to the rule at
     * the top of this interface is visible in the interface rather than in
     * {@code JpaRepository}'s inherited surface.
     */
    Student getReferenceById(Long id);

    Page<Student> findByGenderIn(Collection<Gender> genders, Pageable pageable);

    /**
     * Scoped batch fetch, for marking a whole register in one transaction.
     *
     * <p>One query for the two hundred students named in a bulk submission rather than
     * two hundred calls to {@link #findByIdAndGenderIn}. The {@code genders} argument is
     * what makes the omission safe to look at: ids outside the caller's scope simply do
     * not come back, and {@code AttendanceService} reports them as skipped rather than
     * failing the batch.
     */
    List<Student> findByIdInAndGenderIn(Collection<Long> ids, Collection<Gender> genders);

    Page<Student> findByGenderInAndAllocationStatus(
            Collection<Gender> genders, AllocationStatus allocationStatus, Pageable pageable);

    Page<Student> findByGenderInAndYearOfStudy(
            Collection<Gender> genders, Integer yearOfStudy, Pageable pageable);

    Optional<Student> findByRollNumberAndGenderIn(String rollNumber, Collection<Gender> genders);

    long countByGenderIn(Collection<Gender> genders);

    long countByGenderInAndAllocationStatus(Collection<Gender> genders, AllocationStatus allocationStatus);

    /**
     * Resolves the caller's own student row from their account id.
     *
     * <p>Unscoped by design and safe: the only id ever passed is the
     * authenticated user's own, taken from the JWT subject rather than from a
     * request parameter.
     */
    Optional<Student> findByUserId(Long userId);

    boolean existsByRollNumber(String rollNumber);
}
