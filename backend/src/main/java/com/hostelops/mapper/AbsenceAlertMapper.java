package com.hostelops.mapper;

import com.hostelops.domain.AbsenceAlert;
import com.hostelops.dto.attendance.AbsenceAlertResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class AbsenceAlertMapper {

    private final StudentMapper studentMapper;

    public AbsenceAlertMapper(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /**
     * @param today the date to measure the alert's age against, passed in rather than
     *              read from a clock here. Every alert in one response is then aged
     *              against the same date -- a list mapped across midnight would
     *              otherwise show two different todays -- and a test can assert on an
     *              exact {@code ageDays} without freezing a clock.
     */
    public AbsenceAlertResponse toResponse(AbsenceAlert alert, LocalDate today) {
        return new AbsenceAlertResponse(
                alert.getId(),
                studentMapper.toSummary(alert.getStudent()),
                alert.getStreakStartDate(),
                alert.getConsecutiveDays(),
                alert.getTriggeredOn(),
                // From triggeredOn, not streakStartDate: the age of the alert, not the
                // length of the absence. consecutiveDays already reports the latter, and
                // conflating them would let a still-growing streak disguise an alert
                // nobody has looked at.
                ChronoUnit.DAYS.between(alert.getTriggeredOn(), today),
                alert.getNotifiedAt(),
                alert.getAcknowledgedBy() == null ? null : alert.getAcknowledgedBy().getFullName(),
                alert.getAcknowledgedAt(),
                alert.isAcknowledged());
    }
}
