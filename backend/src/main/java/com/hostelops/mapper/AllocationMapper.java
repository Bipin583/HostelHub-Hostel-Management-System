package com.hostelops.mapper;

import com.hostelops.domain.Allocation;
import com.hostelops.dto.allocation.AllocationResponse;
import org.springframework.stereotype.Component;

@Component
public class AllocationMapper {

    private final StudentMapper studentMapper;
    private final RoomMapper roomMapper;

    public AllocationMapper(StudentMapper studentMapper, RoomMapper roomMapper) {
        this.studentMapper = studentMapper;
        this.roomMapper = roomMapper;
    }

    public AllocationResponse toResponse(Allocation allocation) {
        return new AllocationResponse(
                allocation.getId(),
                studentMapper.toSummary(allocation.getStudent()),
                roomMapper.toSummary(allocation.getRoom()),
                allocation.isActive(),
                allocation.getAllocatedAt(),
                allocation.getVacatedAt());
    }
}
