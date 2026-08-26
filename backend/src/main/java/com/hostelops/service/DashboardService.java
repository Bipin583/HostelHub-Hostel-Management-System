package com.hostelops.service;

import com.hostelops.dto.dashboard.DashboardResponse;
import com.hostelops.dto.dashboard.StudentDashboardResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the landing-page tiles.
 *
 * <p>The only service here that depends on other services rather than on repositories, and
 * the exception is deliberate. Each count it needs already exists, already applies the
 * caller's {@code AccessScope}, and is already the number the corresponding list route
 * agrees with. Reaching past those methods to the repositories would mean re-deriving four
 * scope predicates in a fifth place, and the first time one of them changed the dashboard
 * would quietly start disagreeing with the page it summarises.
 *
 * <p>So this class owns no query and no authorization rule. It has one job -- call four
 * counts and put them in a record -- and the reason it exists at all is the transaction
 * boundary described below.
 *
 * <h2>Why one transaction</h2>
 *
 * <p>Every count is wrapped in one {@code @Transactional(readOnly = true)} here, and the
 * inner services join it rather than starting their own. What that buys is not a consistent
 * snapshot: under READ COMMITTED each statement re-snapshots, so a payment landing between
 * the first and second count will show up in one tile and not the other. It is worth being
 * exact about that rather than claiming a guarantee the isolation level does not give.
 *
 * <p>What it does buy is worth having anyway. One connection is acquired instead of four, so
 * a dashboard render costs the pool one lease rather than four -- and this is the single most
 * requested endpoint in the application, since it loads on every login. The caller's scope is
 * resolved once. And the four counts either all succeed or all fail: a partial dashboard,
 * with two real numbers and two zeroes from swallowed errors, is worse than an error page,
 * because zero is a plausible answer to every one of these questions.
 */
@Service
public class DashboardService {

    private final FeeService fees;
    private final ComplaintService complaints;
    private final AbsenceAlertService alerts;
    private final NoticeService notices;

    public DashboardService(
            FeeService fees,
            ComplaintService complaints,
            AbsenceAlertService alerts,
            NoticeService notices) {
        this.fees = fees;
        this.complaints = complaints;
        this.alerts = alerts;
        this.notices = notices;
    }

    /**
     * The four staff tiles, scoped to the caller's hostels.
     *
     * <p>Not audited. Reading a count changes nothing, and an audit row per dashboard load
     * would be the highest-volume entry in the table by a wide margin -- drowning the
     * allocations and fee cancellations the trail exists to make findable.
     */
    @Transactional(readOnly = true)
    public DashboardResponse forStaff() {
        return new DashboardResponse(
                fees.countUnpaid(),
                complaints.countOpen(),
                alerts.countOpen(),
                notices.countMine());
    }

    /**
     * The resident's tile.
     *
     * <p>One count, and still routed through here rather than straight from the controller to
     * {@link ComplaintService}, so that the student dashboard has the same shape and the same
     * place to grow as the staff one. A controller that called a count directly today would
     * be the controller that grows a second dependency tomorrow.
     */
    @Transactional(readOnly = true)
    public StudentDashboardResponse forStudent() {
        return new StudentDashboardResponse(complaints.countMineUnresolved());
    }
}
