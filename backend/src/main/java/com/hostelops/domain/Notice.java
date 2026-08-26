package com.hostelops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A notice board post.
 *
 * <p>Audience is three nullable columns rather than a join table, and NULL means
 * "everyone". A warden posting about the LH water supply sets
 * {@code audienceHostelType = LH}; an admin posting about exam dates leaves all
 * three null. The alternative -- a {@code notice_recipients} table -- would need a
 * row per student per notice, be wrong the moment a student changes year, and answer
 * a question nobody asks ("which individuals were targeted") instead of the one
 * everyone does ("who should see this now").
 *
 * <p>A warden's notice is stamped with their own scope at creation, in
 * {@code NoticeService}, so the scope filter that keeps them from reading other
 * hostels' data also keeps them from writing into them.
 */
@Entity
@Table(name = "notices")
@Getter
@Setter
@NoArgsConstructor
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code ON DELETE RESTRICT} in the schema, unlike the nullable actor columns
     * elsewhere. An unattributed notice is worse than an undeletable account: students
     * need to know who told them the water is off.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private UserAccount author;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** NULL = every hostel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_hostel_type", length = 2)
    private HostelType audienceHostelType;

    /** NULL = both genders. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_gender", length = 1)
    private Gender audienceGender;

    /** NULL = every year. */
    @Column(name = "audience_year")
    private Integer audienceYear;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** NULL = never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (this.publishedAt == null) {
            this.publishedAt = Instant.now();
        }
    }

    public boolean isLiveAt(Instant when) {
        return !this.publishedAt.isAfter(when)
                && (this.expiresAt == null || this.expiresAt.isAfter(when));
    }

    /**
     * Whether this notice is addressed to the given student.
     *
     * <p>Mirrors the SQL predicate in {@code NoticeRepository.findFeedFor}. Duplicating
     * the rule is a real cost, and it is here so the rule can be unit-tested over a
     * matrix of audiences without a database; the repository query is what the feed
     * actually uses, and an integration test pins the two together.
     */
    public boolean targets(Student student, HostelType studentHostelType) {
        if (this.audienceGender != null && this.audienceGender != student.getGender()) {
            return false;
        }
        if (this.audienceYear != null && !this.audienceYear.equals(student.getYearOfStudy())) {
            return false;
        }
        // A student with no allocation has no hostel type, so a hostel-targeted notice
        // cannot reach them. Treating "unallocated" as a match would show a first-year
        // applicant maintenance notices for a building they have never entered.
        return this.audienceHostelType == null || this.audienceHostelType == studentHostelType;
    }
}
