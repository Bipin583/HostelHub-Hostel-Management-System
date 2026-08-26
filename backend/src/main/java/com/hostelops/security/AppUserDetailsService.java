package com.hostelops.security;

import com.hostelops.domain.Role;
import com.hostelops.domain.UserAccount;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads an account for password authentication.
 *
 * <p>This runs on login only. Once a token is issued, subsequent requests rebuild
 * the principal from its claims, so this is not on the per-request path.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;
    private final StudentRepository students;

    public AppUserDetailsService(UserAccountRepository users, StudentRepository students) {
        this.users = users;
        this.students = students;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUserPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = users.findByUsername(username)
                // DaoAuthenticationProvider converts this to BadCredentialsException
                // (hideUserNotFoundExceptions defaults to true), so a missing account
                // and a wrong password are indistinguishable to the caller.
                .orElseThrow(() -> new UsernameNotFoundException("No account for the supplied username"));

        Long studentId = account.getRole() == Role.STUDENT
                ? students.findByUserId(account.getId()).map(s -> s.getId()).orElse(null)
                : null;

        return new AppUserPrincipal(
                account.getId(),
                account.getUsername(),
                account.getPasswordHash(),
                account.getRole(),
                account.getHostelScope(),
                studentId,
                account.isEnabled());
    }
}
