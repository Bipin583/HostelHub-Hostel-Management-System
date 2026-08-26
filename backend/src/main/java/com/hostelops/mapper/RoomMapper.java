package com.hostelops.mapper;

import com.hostelops.domain.Room;
import com.hostelops.dto.room.RoomResponse;
import com.hostelops.dto.room.RoomSummaryResponse;
import org.springframework.stereotype.Component;

@Component
public class RoomMapper {

    public RoomSummaryResponse toSummary(Room room) {
        return new RoomSummaryResponse(
                room.getId(),
                room.getRoomName(),
                room.getHostelType(),
                room.getBlock(),
                room.getFloor(),
                room.getCapacity(),
                room.getEligibleYear(),
                room.getEligibleGender());
    }

    /**
     * @param occupiedBeds counted by the caller, which is the only component that
     *                     knows whether it holds the room's lock and therefore
     *                     whether the number is authoritative or merely current
     */
    public RoomResponse toResponse(Room room, long occupiedBeds) {
        return new RoomResponse(
                room.getId(),
                room.getRoomName(),
                room.getHostelType(),
                room.getBlock(),
                room.getFloor(),
                room.getCapacity(),
                room.getEligibleYear(),
                room.getEligibleGender(),
                occupiedBeds,
                Math.max(0, room.getCapacity() - occupiedBeds));
    }
}
