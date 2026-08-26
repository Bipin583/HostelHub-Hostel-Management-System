package com.hostelops.mapper;

import com.hostelops.domain.Complaint;
import com.hostelops.dto.complaint.ComplaintResponse;
import org.springframework.stereotype.Component;

@Component
public class ComplaintMapper {

    private final StudentMapper studentMapper;

    public ComplaintMapper(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /**
     * Requires {@code resolvedBy} to have been fetch-joined, which every finder in
     * {@code ComplaintRepository} does with a {@code left join fetch} -- left, because
     * an open complaint has nobody who resolved it and an inner join would drop the
     * whole queue.
     *
     * <p>The category-by-status analytics are assembled in {@code ComplaintService},
     * not here. Those numbers come from a sparse group-by that has to be filled in
     * against the full set of enum values, which is a decision about what a missing
     * cell means rather than a translation of a row.
     */
    public ComplaintResponse toResponse(Complaint complaint) {
        return new ComplaintResponse(
                complaint.getId(),
                studentMapper.toSummary(complaint.getStudent()),
                complaint.getTitle(),
                complaint.getDescription(),
                complaint.getCategory(),
                complaint.getUrgency(),
                complaint.getStatus(),
                complaint.getCreatedAt(),
                complaint.getInProgressAt(),
                complaint.getResolvedAt(),
                complaint.getResolvedBy() == null ? null : complaint.getResolvedBy().getFullName(),
                complaint.getResolutionNote());
    }
}
