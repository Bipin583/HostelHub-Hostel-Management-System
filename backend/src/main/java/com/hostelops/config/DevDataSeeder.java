package com.hostelops.config;

import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.Role;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import com.hostelops.repository.StudentRepository;
import com.hostelops.repository.UserAccountRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo accounts for local development.
 *
 * <p>Three things about this class are deliberate.
 *
 * <p><b>It is not a migration.</b> Flyway migrations run in every environment
 * including production; seeded logins in {@code V*.sql} would be exactly the
 * default-credential problem this rebuild set out to remove. This runs only
 * under the {@code dev} profile.
 *
 * <p><b>There is no password in this file.</b> The seed password comes from
 * {@code DEV_SEED_PASSWORD}. If it is unset, the seeder logs and creates nothing
 * -- it does not fall back to {@code changeme}, and there is no hardcoded value
 * anywhere for anyone to find and try against a deployed instance. A developer
 * chooses the password when they start the stack, and it is stored as a BCrypt
 * hash like any other.
 *
 * <p><b>It is idempotent.</b> Presence of the admin account means the seed has
 * already run, so restarting the stack neither duplicates rows nor resets a
 * password someone has since changed.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** BCrypt hashes at most 72 bytes; a longer secret is silently truncated. */
    private static final int MAX_USABLE_PASSWORD_BYTES = 72;

    private final UserAccountRepository users;
    private final StudentRepository students;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    public DevDataSeeder(
            UserAccountRepository users,
            StudentRepository students,
            PasswordEncoder passwordEncoder,
            @Value("${DEV_SEED_PASSWORD:}") String seedPassword) {
        this.users = users;
        this.students = students;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.isBlank()) {
            log.warn("DEV_SEED_PASSWORD is not set - skipping demo accounts. "
                    + "Set it to seed admin/lh_warden/mh_warden and six students.");
            return;
        }
        if (seedPassword.length() > MAX_USABLE_PASSWORD_BYTES) {
            log.warn("DEV_SEED_PASSWORD is longer than {} characters; BCrypt will ignore the remainder.",
                    MAX_USABLE_PASSWORD_BYTES);
        }
        if (users.existsByUsername("admin")) {
            log.info("Demo accounts already present - seeder is a no-op.");
            return;
        }

        String hash = passwordEncoder.encode(seedPassword);

        users.save(account("admin", "Platform Administrator", "admin@example.edu", Role.ADMIN, null, hash));
        users.save(account("lh_warden", "Ladies Hostel Warden", "lh.warden@example.edu",
                Role.WARDEN, HostelScope.LH, hash));
        users.save(account("mh_warden", "Mens Hostel Warden", "mh.warden@example.edu",
                Role.WARDEN, HostelScope.MH, hash));

        // Two per year across both genders, so warden scope isolation is visible
        // the moment you sign in as either warden.
        record Seed(String username, String fullName, String roll, Gender gender, int year) {
        }
        List<Seed> seeds = List.of(
                new Seed("asha.rao", "Asha Rao", "21CS001", Gender.F, 1),
                new Seed("neha.iyer", "Neha Iyer", "20EC014", Gender.F, 2),
                new Seed("priya.nair", "Priya Nair", "19ME027", Gender.F, 3),
                new Seed("arjun.das", "Arjun Das", "21CS042", Gender.M, 1),
                new Seed("rahul.menon", "Rahul Menon", "20EE009", Gender.M, 2),
                new Seed("vikram.shah", "Vikram Shah", "19CE033", Gender.M, 3));

        for (Seed seed : seeds) {
            UserAccount account = users.save(account(
                    seed.username(), seed.fullName(), seed.username() + "@example.edu",
                    Role.STUDENT, null, hash));

            Student student = new Student();
            student.setUser(account);
            student.setRollNumber(seed.roll());
            student.setGender(seed.gender());
            student.setYearOfStudy(seed.year());
            student.setBranch("Computer Science");
            student.setAllocationStatus(AllocationStatus.NOT_APPLIED);
            students.save(student);
        }

        log.info("Seeded 1 admin, 2 wardens and {} students with the supplied DEV_SEED_PASSWORD.", seeds.size());
    }

    private UserAccount account(
            String username, String fullName, String email, Role role, HostelScope scope, String passwordHash) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setFullName(fullName);
        account.setEmail(email);
        account.setRole(role);
        account.setHostelScope(scope);
        account.setPasswordHash(passwordHash);
        account.setEnabled(true);
        return account;
    }
}
