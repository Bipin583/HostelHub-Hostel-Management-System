package com.hostelops.dto.notice;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A new notice board post.
 *
 * <p>The three audience fields are all nullable, and null means "everyone" -- so the
 * default request body targets the whole campus. That is the right default for an
 * admin and the wrong one for a warden, which is why {@code NoticeService} overwrites
 * {@code audienceHostelType} with the warden's own scope rather than trusting this
 * field. A warden cannot post into another hostel by omitting it.
 *
 * <p>There is no {@code authorId} and no {@code publishedAt}: the author is the
 * caller, and the publication time is when the server accepted it. Both would be
 * forgeable if they were request fields.
 */
public record NoticeCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 20000) String body,
        HostelType audienceHostelType,
        Gender audienceGender,
        @Min(1) @Max(5) Integer audienceYear,
        /*
         * A notice that expired before it was posted is always a mistake -- usually a
         * timezone slip in the client. Rejecting it here means ck_notices_expiry never
         * has to explain itself to a user.
         */
        @Future Instant expiresAt) {
}
