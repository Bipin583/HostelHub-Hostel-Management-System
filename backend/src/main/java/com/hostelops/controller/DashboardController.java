package com.hostelops.controller;

import com.hostelops.dto.dashboard.DashboardResponse;
import com.hostelops.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The warden landing page.
 *
 * <p>One route, no id, no parameters. The tiles are always about the caller's own hostels,
 * so there is nothing in the URL to vary and nothing to tamper with -- the scope comes from
 * the JWT, and two wardens hitting this endpoint get different numbers without asking
 * differently.
 *
 * <p>Every figure it returns has a list route elsewhere that returns the rows behind it.
 * That is the division: this controller answers "how many, and is any of it waiting for
 * me", and the {@code /fees}, {@code /complaints}, and {@code /absence-alerts} controllers
 * answer "which ones". Keeping the count and the list on the same query -- via the shared
 * service methods {@link DashboardService} calls -- is what stops the tile and the page it
 * links to from disagreeing.
 */
@RestController
@RequestMapping("/api/v1/warden/dashboard")
@Tag(name = "Dashboard (warden)")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "The landing tiles for the caller's hostels",
            description = "Unpaid invoices, open complaints, waiting absence alerts, and how many notices "
                    + "I have posted -- each scoped to my hostels and each agreeing with its own list "
                    + "route, because they come from the same query.")
    public DashboardResponse dashboard() {
        return dashboardService.forStaff();
    }
}
