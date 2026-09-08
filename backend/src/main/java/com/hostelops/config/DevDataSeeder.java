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
import org.springframework.core.annotation.Order;
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
 *
 * <p>This class creates <em>accounts</em> only. The operational content a demo
 * needs -- applications, beds, invoices, a marked register -- is
 * {@link DevDemoData}, which runs after this one and depends on these rows
 * existing. Splitting them keeps the credential reasoning above in a file that
 * has nothing else to argue about.
 */
@Component
@Profile("dev")
@Order(DevDataSeeder.ORDER)
public class DevDataSeeder implements ApplicationRunner {

    /** Accounts first; {@link DevDemoData} is ordered after this and needs them. */
    static final int ORDER = 10;

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
                    + "Set it to seed admin/lh_warden/mh_warden and 24 students.");
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

        // Three per year per gender across four years: twelve female students for the
        // LH warden and twelve male for the MH warden, so scope isolation is visible
        // the moment you sign in as either one.
        //
        // Twenty-four rather than the original six because a roster of six never
        // paginates, never fills an occupancy chart, and leaves every dashboard figure
        // looking like a rounding error. The first thing anyone does with this stack is
        // look at it, and six rows do not show what the screens are for.
        record Seed(String username, String fullName, String roll, Gender gender, int year,
                String branch, String mobile, String parentMobile) {
        }
        List<Seed> seeds = List.of(
                new Seed("asha.rao", "Asha Rao", "24CS001", Gender.F, 1,
                        "Computer Science", "9845010001", "9845020001"),
                new Seed("divya.pillai", "Divya Pillai", "24EC008", Gender.F, 1,
                        "Electronics", "9845010002", "9845020002"),
                new Seed("fatima.sheikh", "Fatima Sheikh", "24IT015", Gender.F, 1,
                        "Information Technology", "9845010003", "9845020003"),
                new Seed("neha.iyer", "Neha Iyer", "23EC014", Gender.F, 2,
                        "Electronics", "9845010004", "9845020004"),
                new Seed("kavya.reddy", "Kavya Reddy", "23CS022", Gender.F, 2,
                        "Computer Science", "9845010005", "9845020005"),
                new Seed("sneha.joshi", "Sneha Joshi", "23ME031", Gender.F, 2,
                        "Mechanical", "9845010006", "9845020006"),
                new Seed("priya.nair", "Priya Nair", "22ME027", Gender.F, 3,
                        "Mechanical", "9845010007", "9845020007"),
                new Seed("ritu.bansal", "Ritu Bansal", "22CE019", Gender.F, 3,
                        "Civil", "9845010008", "9845020008"),
                new Seed("anjali.verma", "Anjali Verma", "22CS036", Gender.F, 3,
                        "Computer Science", "9845010009", "9845020009"),
                new Seed("meera.krishnan", "Meera Krishnan", "21EE004", Gender.F, 4,
                        "Electrical", "9845010010", "9845020010"),
                new Seed("tanvi.gupta", "Tanvi Gupta", "21IT011", Gender.F, 4,
                        "Information Technology", "9845010011", "9845020011"),
                new Seed("lakshmi.sundar", "Lakshmi Sundar", "21CS029", Gender.F, 4,
                        "Computer Science", "9845010012", "9845020012"),
                new Seed("arjun.das", "Arjun Das", "24CS042", Gender.M, 1,
                        "Computer Science", "9845010013", "9845020013"),
                new Seed("imran.qureshi", "Imran Qureshi", "24ME050", Gender.M, 1,
                        "Mechanical", "9845010014", "9845020014"),
                new Seed("karthik.bose", "Karthik Bose", "24EC061", Gender.M, 1,
                        "Electronics", "9845010015", "9845020015"),
                new Seed("rahul.menon", "Rahul Menon", "23EE009", Gender.M, 2,
                        "Electrical", "9845010016", "9845020016"),
                new Seed("sanjay.patil", "Sanjay Patil", "23CS017", Gender.M, 2,
                        "Computer Science", "9845010017", "9845020017"),
                new Seed("aditya.rane", "Aditya Rane", "23CE024", Gender.M, 2,
                        "Civil", "9845010018", "9845020018"),
                new Seed("vikram.shah", "Vikram Shah", "22CE033", Gender.M, 3,
                        "Civil", "9845010019", "9845020019"),
                new Seed("nikhil.varma", "Nikhil Varma", "22IT040", Gender.M, 3,
                        "Information Technology", "9845010020", "9845020020"),
                new Seed("harish.kumar", "Harish Kumar", "22CS047", Gender.M, 3,
                        "Computer Science", "9845010021", "9845020021"),
                new Seed("faisal.ahmed", "Faisal Ahmed", "21ME013", Gender.M, 4,
                        "Mechanical", "9845010022", "9845020022"),
                new Seed("gaurav.thakur", "Gaurav Thakur", "21EC026", Gender.M, 4,
                        "Electronics", "9845010023", "9845020023"),
                new Seed("dinesh.raj", "Dinesh Raj", "21CS038", Gender.M, 4,
                        "Computer Science", "9845010024", "9845020024"));

        for (Seed seed : seeds) {
            UserAccount account = users.save(account(
                    seed.username(), seed.fullName(), seed.username() + "@example.edu",
                    Role.STUDENT, null, hash));

            Student student = new Student();
            student.setUser(account);
            student.setRollNumber(seed.roll());
            student.setGender(seed.gender());
            student.setYearOfStudy(seed.year());
            student.setBranch(seed.branch());
            student.setMobileNo(seed.mobile());
            student.setParentMobileNo(seed.parentMobile());
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
