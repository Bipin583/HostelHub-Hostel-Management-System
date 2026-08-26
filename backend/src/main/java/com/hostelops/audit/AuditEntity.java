package com.hostelops.audit;

/**
 * The values that go in {@code audit_events.entity_type}.
 *
 * <p>Constants rather than string literals at each {@link Audited} site, because a typo
 * in a literal is invisible: {@code "ALLOCATON"} compiles, writes rows, and quietly
 * splits one record's history into two piles that no query joins back together. Referring
 * to a constant makes that a compile error.
 *
 * <p>Constants rather than an enum, even though an annotation attribute could be one,
 * because the column is deliberately a free string. The table outlives the things it
 * describes -- that is the point of it -- and it must stay readable when a type is
 * renamed or a service is deleted. An enum would make deserialising an old row depend on
 * a Java constant still existing to name it. These constants therefore guard the write
 * sites; they do not constrain the column, and reads do not validate against them.
 */
public final class AuditEntity {

    public static final String STUDENT = "STUDENT";
    public static final String APPLICATION = "APPLICATION";
    public static final String ALLOCATION = "ALLOCATION";
    public static final String ROOM = "ROOM";
    public static final String ATTENDANCE = "ATTENDANCE";
    public static final String ABSENCE_ALERT = "ABSENCE_ALERT";
    public static final String COMPLAINT = "COMPLAINT";
    public static final String FEE = "FEE";
    public static final String PAYMENT = "PAYMENT";

    /**
     * The reminder log, and the nightly run that writes it.
     *
     * <p>Separate from {@link #FEE} rather than folded into it: a run touches many invoices
     * and changes none of them, so filing its event under {@code FEE} would mix "somebody
     * edited this invoice" with "the job looked at it" in the one pile this class exists to
     * keep apart.
     */
    public static final String FEE_REMINDER = "FEE_REMINDER";

    public static final String NOTICE = "NOTICE";

    private AuditEntity() {
    }
}
