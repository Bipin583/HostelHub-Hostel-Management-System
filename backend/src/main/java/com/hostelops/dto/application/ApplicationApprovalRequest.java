package com.hostelops.dto.application;

import jakarta.validation.constraints.Size;

/**
 * A warden's approval.
 *
 * <p>The note is optional -- an approval explains itself. Compare
 * {@link ApplicationRejectionRequest}, where the reason is mandatory because the
 * student is owed one.
 */
public record ApplicationApprovalRequest(@Size(max = 500) String note) {
}
