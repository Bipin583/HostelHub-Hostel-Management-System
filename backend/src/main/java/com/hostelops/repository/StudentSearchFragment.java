package com.hostelops.repository;

import com.hostelops.domain.Gender;
import com.hostelops.domain.Student;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The one filtered student listing, hand-built with the Criteria API.
 *
 * <p>Why not a derived query or an HQL {@code :param is null} chain: three optional
 * filters need either eight derived method names or a string of
 * {@code (:x is null or col = :x)} clauses whose parameter types Hibernate has to
 * infer from a null. The Criteria form composes the predicates it actually needs
 * and nothing else, which also means the emitted SQL contains only the filters the
 * caller asked for, so the index chosen for a search-by-roll-number is not the one
 * chosen for a browse-by-year.
 *
 * <p>{@code genders} is a required parameter with no default. That is the same
 * principle as the rest of {@link StudentRepository}: the scope filter is part of
 * the method's signature, so it cannot be forgotten, only passed wrongly -- and
 * passing it wrongly is what {@code WardenScopeIT} checks.
 */
public interface StudentSearchFragment {

    Page<Student> search(StudentSearchCriteria criteria, Collection<Gender> genders, Pageable pageable);
}
