package com.hostelops.service;

import com.hostelops.domain.HostelType;
import com.hostelops.domain.Room;
import com.hostelops.dto.room.OccupancyResponse;
import com.hostelops.dto.room.RoomResponse;
import com.hostelops.dto.student.StudentSummaryResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.mapper.RoomMapper;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.RoomRepository;
import com.hostelops.security.AccessScope;
import com.hostelops.security.CurrentUserProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room reads.
 *
 * <p>Occupancy is always counted, never stored. The predecessor kept a counter on
 * the room and it drifted -- a vacate that failed halfway, a manual data fix, two
 * writers -- and a room that reports itself full when it is not is invisible until
 * a student is turned away. Counting costs one aggregate query per page.
 */
@Service
public class RoomService {

    private final RoomRepository rooms;
    private final AllocationRepository allocations;
    private final RoomMapper roomMapper;
    private final StudentMapper studentMapper;
    private final CurrentUserProvider currentUser;

    public RoomService(
            RoomRepository rooms,
            AllocationRepository allocations,
            RoomMapper roomMapper,
            StudentMapper studentMapper,
            CurrentUserProvider currentUser) {
        this.rooms = rooms;
        this.allocations = allocations;
        this.roomMapper = roomMapper;
        this.studentMapper = studentMapper;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<RoomResponse> list(Pageable pageable) {
        AccessScope scope = currentUser.scope();
        Page<Room> page = rooms.findByHostelTypeIn(scope.visibleHostelTypes(), pageable);
        return withOccupancy(page);
    }

    @Transactional(readOnly = true)
    public Page<RoomResponse> listByBlock(String block, Pageable pageable) {
        AccessScope scope = currentUser.scope();
        Page<Room> page = rooms.findByHostelTypeInAndBlock(scope.visibleHostelTypes(), block, pageable);
        return withOccupancy(page);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(Long roomId) {
        Room room = scopedRoom(roomId);
        return roomMapper.toResponse(room, allocations.countByRoomIdAndActiveTrue(room.getId()));
    }

    @Transactional(readOnly = true)
    public List<StudentSummaryResponse> occupants(Long roomId) {
        Room room = scopedRoom(roomId);
        return allocations.findOccupantsOfRoom(room.getId()).stream()
                .map(allocation -> studentMapper.toSummary(allocation.getStudent()))
                .toList();
    }

    /**
     * Occupancy per hostel and block.
     *
     * <p>Aggregated in the database. Pulling every room and every allocation into
     * the application to count them there is the version of this endpoint that works
     * fine on a demo dataset and falls over on a real one -- and it would ship the
     * whole roster over the wire to produce a handful of percentages.
     */
    @Transactional(readOnly = true)
    public List<OccupancyResponse> occupancyByBlock() {
        AccessScope scope = currentUser.scope();
        List<String> hostelTypes = scope.visibleHostelTypes().stream().map(HostelType::name).toList();

        return rooms.findOccupancyByBlock(hostelTypes).stream()
                .map(row -> new OccupancyResponse(
                        HostelType.valueOf(row.getHostelType()),
                        row.getBlock(),
                        row.getRoomCount(),
                        row.getTotalBeds(),
                        row.getOccupiedBeds(),
                        row.getTotalBeds() - row.getOccupiedBeds(),
                        percentage(row.getOccupiedBeds(), row.getTotalBeds())))
                .toList();
    }

    private Room scopedRoom(Long roomId) {
        AccessScope scope = currentUser.scope();
        return rooms.findByIdAndHostelTypeIn(roomId, scope.visibleHostelTypes())
                .orElseThrow(() -> ApiException.notFound("room", roomId));
    }

    /**
     * Attaches live bed counts to a page of rooms with one extra query.
     *
     * <p>The obvious implementation -- {@code countByRoomIdAndActiveTrue} inside the
     * map -- issues one query per row. On a 20-room page that is 21 round trips for
     * a number the database can produce in one.
     */
    private Page<RoomResponse> withOccupancy(Page<Room> page) {
        if (page.isEmpty()) {
            return page.map(room -> roomMapper.toResponse(room, 0));
        }

        List<Long> roomIds = page.getContent().stream().map(Room::getId).toList();
        Map<Long, Long> occupied = new HashMap<>(allocations.countActiveByRoomIds(roomIds).stream()
                .collect(Collectors.toMap(
                        AllocationRepository.RoomOccupancyRow::getRoomId,
                        AllocationRepository.RoomOccupancyRow::getOccupied)));

        // Absent means zero: an empty room has no allocation rows to group.
        return page.map(room -> roomMapper.toResponse(room, occupied.getOrDefault(room.getId(), 0L)));
    }

    private static double percentage(long part, long whole) {
        if (whole <= 0) {
            return 0.0;
        }
        // Rounded to one decimal at the edge rather than in the frontend, so every
        // client shows the same number.
        return Math.round(part * 1000.0 / whole) / 10.0;
    }
}
