package com.hostelops.dto.notice;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelType;
import java.time.Instant;

/**
 * A notice as the client sees it.
 *
 * <p>{@code audienceLabel} is a rendered string ("LH, year 2" or "Everyone") built
 * server-side. The client could assemble it from the three nullable fields, but then
 * every client would assemble it slightly differently, and "who can see this" is
 * precisely the thing an author must not be able to misread. The raw fields are
 * exposed alongside it for the edit form.
 */
public record NoticeResponse(
        Long id,
        String title,
        String body,
        HostelType audienceHostelType,
        Gender audienceGender,
        Integer audienceYear,
        String audienceLabel,
        String authorName,
        Instant publishedAt,
        Instant expiresAt,
        boolean live) {
}
