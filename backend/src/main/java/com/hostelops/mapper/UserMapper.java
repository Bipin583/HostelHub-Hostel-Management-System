package com.hostelops.mapper;

import com.hostelops.domain.UserAccount;
import com.hostelops.dto.UserSummaryResponse;
import org.springframework.stereotype.Component;

/**
 * Entity to DTO by hand.
 *
 * <p>MapStruct was the alternative. Hand-written mapping was chosen because the
 * annotation processor has to be ordered against Lombok's to see the generated
 * accessors, and a build that breaks on processor ordering is a worse trade than
 * a few explicit constructor calls. It also keeps every field that leaves the
 * server visible in source -- there is no generated file to check when asking
 * "can this response contain a password hash?".
 */
@Component
public class UserMapper {

    public UserSummaryResponse toSummary(UserAccount account, Long studentId) {
        return new UserSummaryResponse(
                account.getId(),
                account.getUsername(),
                account.getFullName(),
                account.getEmail(),
                account.getRole(),
                account.getHostelScope(),
                studentId);
    }
}
