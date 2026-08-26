package com.hostelops.controller;

import com.hostelops.dto.notice.NoticeResponse;
import com.hostelops.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notice board as a resident sees it.
 *
 * <p>One route, because a student's relationship with the notice board is entirely
 * read-only and entirely about themselves. There is no detail route either: a notice is
 * small enough that the feed carries it whole, so a {@code /{noticeId}} endpoint would
 * exist only to be an id-probe against rows the audience filter is meant to hide.
 *
 * <p>Who a notice is addressed to is decided in SQL, not here and not in the client. The
 * feed query matches on gender, year of study, and the hostel type of the student's active
 * allocation, and it excludes expired notices -- so an applicant with no room yet sees the
 * general announcements and none of the building-specific ones. Filtering after the fetch
 * would page over rows the student may not read and hand back short pages as a result,
 * which is both a leak of row counts and a visibly broken paginator.
 */
@RestController
@RequestMapping("/api/v1/student/notices")
@Tag(name = "Notice board (student)")
public class StudentNoticeController {

    private final NoticeService noticeService;

    public StudentNoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    @Operation(summary = "Live notices addressed to me, newest first",
            description = "Expired notices are excluded, and the whole page is judged against a single "
                    + "instant -- so a feed rendered across a second boundary cannot hold a notice its "
                    + "own timestamp says has lapsed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found, possibly empty"),
            @ApiResponse(responseCode = "403",
                    description = "The authenticated account is not a student",
                    content = @Content),
            @ApiResponse(responseCode = "404",
                    description = "The student record behind the token no longer exists",
                    content = @Content)})
    public Page<NoticeResponse> feed(@PageableDefault(size = 20) Pageable pageable) {
        return noticeService.feed(pageable);
    }
}
