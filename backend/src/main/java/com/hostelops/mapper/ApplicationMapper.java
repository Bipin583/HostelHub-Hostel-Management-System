package com.hostelops.mapper;

import com.hostelops.domain.HostelApplication;
import com.hostelops.domain.Student;
import com.hostelops.dto.application.ApplicationResponse;
import org.springframework.stereotype.Component;

/**
 * Applications to DTOs.
 *
 * <p>Reads three lazy associations -- {@code student}, {@code student.user} and
 * {@code decidedBy} -- so it must run inside the transaction that loaded them, and
 * the repository queries fetch all three. If a future query forgets one, this
 * throws {@code LazyInitializationException} rather than quietly issuing an extra
 * select, because {@code open-in-view} is off. That is the desired failure: loud,
 * in a test, instead of an N+1 nobody notices until the queue has a thousand rows.
 */
@Component
public class ApplicationMapper {

    public ApplicationResponse toResponse(HostelApplication application) {
        Student student = application.getStudent();
        return new ApplicationResponse(
                application.getId(),
                student.getId(),
                student.getRollNumber(),
                student.getUser().getFullName(),
                application.getStatus(),
                application.getAppliedAt(),
                application.getDecidedAt(),
                application.getDecidedBy() == null ? null : application.getDecidedBy().getFullName(),
                application.getNote());
    }
}
