package com.hostelops.repository;

import com.hostelops.domain.Gender;
import com.hostelops.domain.Student;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Implements {@link StudentSearchFragment}.
 *
 * <p>Found by Spring Data through the {@code Impl} suffix on the fragment
 * interface's name and mixed into {@link StudentRepository} automatically.
 */
public class StudentSearchFragmentImpl implements StudentSearchFragment {

    /** Sort keys a client may name. Anything else is ignored, not passed through. */
    private static final List<String> SORTABLE =
            List.of("rollNumber", "yearOfStudy", "allocationStatus", "branch", "gender", "id");

    private final EntityManager em;

    public StudentSearchFragmentImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public Page<Student> search(
            StudentSearchCriteria criteria, Collection<Gender> genders, Pageable pageable) {

        if (genders == null || genders.isEmpty()) {
            // Not an error state to paper over: an empty scope means "may see
            // nothing", and an empty page is the honest answer. Building the query
            // anyway would emit `gender in ()`, which Postgres rejects outright.
            return Page.empty(pageable);
        }

        CriteriaBuilder cb = em.getCriteriaBuilder();

        // -- content --
        CriteriaQuery<Student> query = cb.createQuery(Student.class);
        Root<Student> student = query.from(Student.class);
        query.select(student).where(predicates(cb, student, criteria, genders));
        query.orderBy(ordering(cb, student, pageable.getSort()));

        // Every response needs the account's full name, and the association is
        // lazy. A load graph is used rather than a criteria fetch join because the
        // roll-number/name filter below needs a *predicate* join on the same
        // association: asking for both in the criteria tree yields two joins to the
        // same table, and the usual workaround -- casting the Fetch to a Join --
        // depends on a Hibernate implementation detail. The hint asks for the same
        // SQL without either problem.
        EntityGraph<Student> withAccount = em.createEntityGraph(Student.class);
        withAccount.addAttributeNodes("user");

        List<Student> content = em.createQuery(query)
                .setHint("jakarta.persistence.loadgraph", withAccount)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // -- count --
        // Built from a fresh root. Reusing the one above would count over its joins
        // and, with the load graph, fetch rows nobody reads.
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Student> countRoot = countQuery.from(Student.class);
        countQuery.select(cb.count(countRoot))
                .where(predicates(cb, countRoot, criteria, genders));
        long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private Predicate[] predicates(
            CriteriaBuilder cb,
            Root<Student> student,
            StudentSearchCriteria criteria,
            Collection<Gender> genders) {

        List<Predicate> predicates = new ArrayList<>();

        // Always present. This is the row-level authorisation, expressed as a
        // narrowing of the values the query can match rather than as a check applied
        // to rows already in hand.
        predicates.add(student.get("gender").in(genders));

        if (criteria.yearOfStudy() != null) {
            predicates.add(cb.equal(student.get("yearOfStudy"), criteria.yearOfStudy()));
        }
        if (criteria.allocationStatus() != null) {
            predicates.add(cb.equal(student.get("allocationStatus"), criteria.allocationStatus()));
        }
        if (criteria.query() != null) {
            String pattern = "%" + criteria.query().toLowerCase() + "%";
            var account = student.join("user", JoinType.INNER);
            predicates.add(cb.or(
                    cb.like(cb.lower(student.get("rollNumber")), pattern),
                    cb.like(cb.lower(account.get("fullName")), pattern)));
        }

        return predicates.toArray(new Predicate[0]);
    }

    /**
     * Translates the request's sort, falling back to roll number.
     *
     * <p>The fallback is not cosmetic. Without a total ordering, two pages of the
     * same query can legitimately repeat or skip a row, and that reads as a data bug
     * rather than as a missing {@code ORDER BY}.
     */
    private List<Order> ordering(CriteriaBuilder cb, Root<Student> student, Sort sort) {
        List<Order> orders = new ArrayList<>();
        for (Sort.Order requested : sort) {
            // Whitelisted, because a sort key that reaches `student.get(...)`
            // unchecked lets a caller probe entity internals and turn a typo into a
            // 500.
            if (!SORTABLE.contains(requested.getProperty())) {
                continue;
            }
            orders.add(requested.isAscending()
                    ? cb.asc(student.get(requested.getProperty()))
                    : cb.desc(student.get(requested.getProperty())));
        }
        if (orders.isEmpty()) {
            orders.add(cb.asc(student.get("rollNumber")));
        }
        return orders;
    }
}
