package com.hostelops.mapper;

import com.hostelops.domain.Notice;
import com.hostelops.dto.notice.NoticeResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NoticeMapper {

    /**
     * @param now the instant liveness is judged against, so every notice in one feed
     *            agrees on whether it has expired
     */
    public NoticeResponse toResponse(Notice notice, Instant now) {
        return new NoticeResponse(
                notice.getId(),
                notice.getTitle(),
                notice.getBody(),
                notice.getAudienceHostelType(),
                notice.getAudienceGender(),
                notice.getAudienceYear(),
                audienceLabel(notice),
                notice.getAuthor().getFullName(),
                notice.getPublishedAt(),
                notice.getExpiresAt(),
                notice.isLiveAt(now));
    }

    /**
     * Renders the three nullable audience columns as one phrase.
     *
     * <p>All three null reads "Everyone" rather than an empty string. An author looking
     * at a notice with a blank audience would reasonably read it as "nobody yet"; the
     * truth is the opposite and the most consequential thing on the form, so the
     * broadest audience is the one that gets spelled out.
     *
     * <p>The gender clause is dropped when a hostel is named, because the hostels are
     * single-sex: "LH, women" tells a reader nothing "LH" did not, and a redundant
     * clause invites the suspicion that it might one day disagree. If mixed hostels
     * ever exist, {@code HostelType} is where that changes and this reads from it.
     */
    private String audienceLabel(Notice notice) {
        List<String> parts = new ArrayList<>(3);
        if (notice.getAudienceHostelType() != null) {
            parts.add(notice.getAudienceHostelType().name());
        } else if (notice.getAudienceGender() != null) {
            parts.add(switch (notice.getAudienceGender()) {
                case F -> "Women";
                case M -> "Men";
            });
        }
        if (notice.getAudienceYear() != null) {
            parts.add("year " + notice.getAudienceYear());
        }
        return parts.isEmpty() ? "Everyone" : String.join(", ", parts);
    }
}
