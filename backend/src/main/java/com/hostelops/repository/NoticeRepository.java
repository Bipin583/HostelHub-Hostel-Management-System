package com.hostelops.repository;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Notice;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends Repository<Notice, Long> {

    Notice save(Notice notice);

    Optional<Notice> findById(Long id);

    void delete(Notice notice);

    /**
     * A student's notice feed.
     *
     * <p>Each audience predicate is "unset, or matches", which is how a NULL column means
     * everyone. Written out three times rather than hidden behind a helper because this is
     * the authorization rule for the feed and it should be readable in one place.
     *
     * <p>{@code :studentHostelType} is null for a student with no allocation, and the
     * comparison then fails for any hostel-targeted notice -- correct, since an applicant
     * has no business reading maintenance notices for a building they have not moved into.
     * Note that {@code audienceHostelType is null} still matches, so they see the general
     * ones.
     *
     * <p>Mirrored by {@code Notice.targets(...)}, which exists so the same rule can be
     * unit-tested over a matrix of audiences without a database. Two expressions of one
     * rule is a real cost; an integration test pins them together.
     */
    @Query(value = """
            select n from Notice n
            join fetch n.author a
            where n.publishedAt <= :now
              and (n.expiresAt is null or n.expiresAt > :now)
              and (n.audienceGender is null or n.audienceGender = :gender)
              and (n.audienceYear is null or n.audienceYear = :yearOfStudy)
              and (n.audienceHostelType is null or n.audienceHostelType = :hostelType)
            order by n.publishedAt desc, n.id desc
            """,
            countQuery = """
                    select count(n) from Notice n
                    where n.publishedAt <= :now
                      and (n.expiresAt is null or n.expiresAt > :now)
                      and (n.audienceGender is null or n.audienceGender = :gender)
                      and (n.audienceYear is null or n.audienceYear = :yearOfStudy)
                      and (n.audienceHostelType is null or n.audienceHostelType = :hostelType)
                    """)
    Page<Notice> findFeedFor(
            @Param("gender") Gender gender,
            @Param("yearOfStudy") Integer yearOfStudy,
            @Param("hostelType") HostelType hostelType,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Notices a warden or admin may manage.
     *
     * <p>Scoped to the hostels in the caller's remit, plus the untargeted ones. A warden
     * sees the campus-wide notices because they need to know what their students have been
     * told; they cannot edit them, which the service enforces on authorship.
     *
     * <p>Expired notices are included -- this is the management list, not the feed, and
     * "what did we post last term" is the question it answers.
     */
    @Query(value = """
            select n from Notice n
            join fetch n.author a
            where n.audienceHostelType is null or n.audienceHostelType in :hostelTypes
            order by n.publishedAt desc, n.id desc
            """,
            countQuery = """
                    select count(n) from Notice n
                    where n.audienceHostelType is null or n.audienceHostelType in :hostelTypes
                    """)
    Page<Notice> findManageableIn(
            @Param("hostelTypes") Collection<HostelType> hostelTypes, Pageable pageable);

    long countByAuthorId(Long authorId);
}
