package com.hostelops.config;

import com.hostelops.domain.AbsenceAlert;
import com.hostelops.domain.Allocation;
import com.hostelops.domain.AllocationStatus;
import com.hostelops.domain.ApplicationStatus;
import com.hostelops.domain.Attendance;
import com.hostelops.domain.AttendanceStatus;
import com.hostelops.domain.Complaint;
import com.hostelops.domain.ComplaintCategory;
import com.hostelops.domain.ComplaintStatus;
import com.hostelops.domain.ComplaintUrgency;
import com.hostelops.domain.FeePayment;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelApplication;
import com.hostelops.domain.HostelFee;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Notice;
import com.hostelops.domain.Room;
import com.hostelops.domain.Student;
import com.hostelops.domain.UserAccount;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Operational sample records for the local-development accounts. */
@Component
@Profile("dev")
@Order(20)
public class DevDemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDemoData.class);
    private static final long TERM_FEE_PAISE = 4_500_000L;

    private final EntityManager entityManager;
    private final String seedPassword;

    public DevDemoData(EntityManager entityManager, @Value("${DEV_SEED_PASSWORD:}") String seedPassword) {
        this.entityManager = entityManager;
        this.seedPassword = seedPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.isBlank()) {
            log.info("DEV_SEED_PASSWORD is not set - skipping operational demo data.");
            return;
        }

        List<Student> students = entityManager.createQuery(
                        "select s from Student s join fetch s.user order by s.id", Student.class)
                .getResultList();
        if (students.isEmpty()) {
            log.info("No demo students found - operational demo data is a no-op.");
            return;
        }
        if (entityManager.createQuery("select count(a) from HostelApplication a", Long.class)
                .getSingleResult() > 0) {
            log.info("Operational demo data already present - seeder is a no-op.");
            return;
        }

        UserAccount lhWarden = account("lh_warden");
        UserAccount mhWarden = account("mh_warden");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = Instant.now();

        seedApplications(students, lhWarden, mhWarden, now);
        entityManager.flush();
        seedAllocations(students, lhWarden, mhWarden);
        seedAttendance(students, lhWarden, mhWarden, today);
        seedAbsenceAlerts(students, today, now);
        seedFees(students, today, now);
        seedComplaints(students, lhWarden, mhWarden, now);
        seedNotices(lhWarden, mhWarden, now);

        log.info("Seeded dashboard-ready applications, allocations, attendance, alerts, fees, complaints and notices.");
    }

    private UserAccount account(String username) {
        return entityManager.createQuery(
                        "select u from UserAccount u where u.username = :username", UserAccount.class)
                .setParameter("username", username)
                .getSingleResult();
    }

    private void seedApplications(
            List<Student> students, UserAccount lhWarden, UserAccount mhWarden, Instant now) {
        for (int index = 0; index < students.size(); index++) {
            Student student = students.get(index);
            HostelApplication application = new HostelApplication();
            application.setStudent(student);
            application.setAppliedAt(now.minusSeconds((long) (students.size() - index) * 86_400));
            student.setAllocationStatus(AllocationStatus.PENDING);

            int withinGender = index % 12;
            if (withinGender < 8) {
                application.decide(ApplicationStatus.APPROVED, wardenFor(student, lhWarden, mhWarden),
                        now.minusSeconds((long) (students.size() - index - 1) * 86_400),
                        "Approved for the current academic year.");
                student.setAllocationStatus(AllocationStatus.ALLOCATED);
            } else if (withinGender < 10) {
                application.decide(ApplicationStatus.REJECTED, wardenFor(student, lhWarden, mhWarden),
                        now.minusSeconds((long) (students.size() - index - 1) * 86_400),
                        "Supporting document needs correction.");
                student.setAllocationStatus(AllocationStatus.NOT_APPLIED);
            }
            entityManager.persist(application);
        }
    }

    private void seedAllocations(List<Student> students, UserAccount lhWarden, UserAccount mhWarden) {
        List<Student> allocated = students.stream()
                .filter(student -> student.getAllocationStatus() == AllocationStatus.ALLOCATED)
                .toList();
        for (Student student : allocated) {
            HostelType hostel = student.getGender() == Gender.F ? HostelType.LH : HostelType.MH;
            Room room = entityManager.createQuery("""
                            select r from Room r
                            where r.hostelType = :hostel and r.eligibleYear = :year
                            order by r.id
                            """, Room.class)
                    .setParameter("hostel", hostel)
                    .setParameter("year", student.getYearOfStudy())
                    .setMaxResults(1)
                    .getSingleResult();
            Allocation allocation = new Allocation();
            allocation.setStudent(student);
            allocation.setRoom(room);
            allocation.setAllocatedBy(wardenFor(student, lhWarden, mhWarden));
            entityManager.persist(allocation);
        }
    }

    private void seedAttendance(
            List<Student> students, UserAccount lhWarden, UserAccount mhWarden, LocalDate today) {
        List<LocalDate> markedDays = previousWorkingDays(today, 14);
        for (int dayIndex = 0; dayIndex < markedDays.size(); dayIndex++) {
            for (int studentIndex = 0; studentIndex < students.size(); studentIndex++) {
                Student student = students.get(studentIndex);
                Attendance attendance = new Attendance();
                attendance.setStudent(student);
                attendance.setAttendanceDate(markedDays.get(dayIndex));
                attendance.setMarkedBy(wardenFor(student, lhWarden, mhWarden));
                boolean absent = studentIndex == 0 || (studentIndex + dayIndex) % 9 == 0;
                attendance.setStatus(absent ? AttendanceStatus.ABSENT : AttendanceStatus.PRESENT);
                entityManager.persist(attendance);
            }
        }
    }

    private void seedAbsenceAlerts(List<Student> students, LocalDate today, Instant now) {
        for (int index : List.of(0, 1, 12)) {
            AbsenceAlert alert = new AbsenceAlert();
            alert.setStudent(students.get(index));
            alert.setStreakStartDate(today.minusDays(15 + index));
            alert.setConsecutiveDays(12 + index % 3);
            alert.setTriggeredOn(today.minusDays(3 + index % 2));
            alert.setNotifiedAt(now.minusSeconds((long) (index + 1) * 86_400));
            entityManager.persist(alert);
        }
    }

    private void seedFees(List<Student> students, LocalDate today, Instant now) {
        String academicYear = academicYear(today);
        for (int index = 0; index < students.size(); index++) {
            Student student = students.get(index);
            HostelFee fee = new HostelFee();
            fee.setStudent(student);
            fee.setTitle("Hostel fee");
            fee.setAcademicYear(academicYear);
            fee.setSemester("ODD");
            fee.setAmountPaise(TERM_FEE_PAISE);
            fee.setDueDate(index < 14 ? today.minusDays(10) : today.plusDays(20));
            fee.setDescription("Accommodation, utilities and common-area maintenance.");
            if (index < 8) {
                settleFee(fee, student, TERM_FEE_PAISE, "paid", index, now);
            } else if (index < 12) {
                settleFee(fee, student, 2_000_000L, "part", index, now);
            }
            entityManager.persist(fee);
        }
    }

    private void settleFee(
            HostelFee fee, Student student, long amountPaise, String suffix, int index, Instant now) {
        fee.applyPayment(amountPaise);
        entityManager.persist(fee);
        FeePayment payment = new FeePayment();
        payment.setFee(fee);
        payment.setStudent(student);
        payment.setAmountPaise(amountPaise);
        payment.setProvider("mock");
        payment.setProviderOrderId("demo-order-" + index);
        payment.setIdempotencyKey("demo-" + suffix + "-" + index);
        payment.succeed("demo-payment-" + index, now.minusSeconds((long) (index + 1) * 43_200));
        entityManager.persist(payment);
    }

    private void seedComplaints(
            List<Student> students, UserAccount lhWarden, UserAccount mhWarden, Instant now) {
        String[] titles = {
            "Corridor light is flickering", "Low water pressure", "Desk drawer is jammed",
            "Wi-Fi drops after dinner", "Mess hall needs cleaning", "Window latch is loose"
        };
        ComplaintCategory[] categories = {
            ComplaintCategory.ELECTRICAL, ComplaintCategory.PLUMBING, ComplaintCategory.FURNITURE,
            ComplaintCategory.INTERNET, ComplaintCategory.CLEANLINESS, ComplaintCategory.GENERAL
        };
        ComplaintUrgency[] urgencies = {
            ComplaintUrgency.HIGH, ComplaintUrgency.CRITICAL, ComplaintUrgency.MEDIUM,
            ComplaintUrgency.HIGH, ComplaintUrgency.LOW, ComplaintUrgency.MEDIUM
        };
        for (int index = 0; index < titles.length; index++) {
            Complaint complaint = new Complaint();
            complaint.setStudent(students.get(index * 2));
            complaint.setTitle(titles[index]);
            complaint.setDescription("Demo request with enough detail for the warden queue and analytics views.");
            complaint.setCategory(categories[index]);
            complaint.setUrgency(urgencies[index]);
            complaint.setCreatedAt(now.minusSeconds((long) (index + 2) * 86_400));
            if (index == 2) {
                complaint.transitionTo(ComplaintStatus.IN_PROGRESS,
                        wardenFor(complaint.getStudent(), lhWarden, mhWarden), null,
                        now.minusSeconds(86_400));
            } else if (index >= 4) {
                complaint.transitionTo(ComplaintStatus.RESOLVED,
                        wardenFor(complaint.getStudent(), lhWarden, mhWarden),
                        "Resolved during the routine maintenance round.",
                        now.minusSeconds((long) (index - 3) * 43_200));
            }
            entityManager.persist(complaint);
        }
    }

    private void seedNotices(UserAccount lhWarden, UserAccount mhWarden, Instant now) {
        notice(lhWarden, "Water tank cleaning", "Water will be unavailable from 10:00 to 12:00 on Saturday.",
                HostelType.LH, null, now.minusSeconds(86_400));
        notice(lhWarden, "Quiet hours during exams", "Please keep common areas quiet after 21:00.",
                HostelType.LH, null, now.minusSeconds(172_800));
        notice(mhWarden, "Electrical inspection", "Rooms on block A will be inspected from 14:00.",
                HostelType.MH, null, now.minusSeconds(64_800));
        notice(mhWarden, "Sports ground registration", "Register teams with the warden office by Friday.",
                HostelType.MH, 1, now.minusSeconds(216_000));
    }

    private void notice(UserAccount author, String title, String body, HostelType hostel,
            Integer year, Instant publishedAt) {
        Notice notice = new Notice();
        notice.setAuthor(author);
        notice.setTitle(title);
        notice.setBody(body);
        notice.setAudienceHostelType(hostel);
        notice.setAudienceYear(year);
        notice.setPublishedAt(publishedAt);
        entityManager.persist(notice);
    }

    private UserAccount wardenFor(Student student, UserAccount lhWarden, UserAccount mhWarden) {
        return student.getGender() == Gender.F ? lhWarden : mhWarden;
    }

    private List<LocalDate> previousWorkingDays(LocalDate today, int count) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate candidate = today;
        while (days.size() < count) {
            if (candidate.getDayOfWeek() != DayOfWeek.SATURDAY
                    && candidate.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(0, candidate);
            }
            candidate = candidate.minusDays(1);
        }
        return days;
    }

    private String academicYear(LocalDate date) {
        int startYear = date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
        return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
    }
}
