package com.hostelops.repository;

import com.hostelops.domain.UserAccount;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Accounts are not hostel-scoped: user administration is an ADMIN-only concern
 * and is gated at the role level, not the row level.
 */
public interface UserAccountRepository extends Repository<UserAccount, Long> {

    UserAccount save(UserAccount user);

    Optional<UserAccount> findById(Long id);

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
