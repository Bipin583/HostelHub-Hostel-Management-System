package com.hostelops.repository;

import com.hostelops.domain.Allocation;
import com.hostelops.domain.Gender;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AllocationRepository extends Repository<Allocation, Long> {

    Allocation save(Allocation allocation);

    /**
     * Writes the row and flushes immediately.
     *
     * <p>Used by allocation so the database's capacity trigger and the partial
     * unique index fire inside the service call, where the failure can be
     * translated, rather than at commit time after the method has returned.
     */
    Allocation saveAndFlush(Allocation allocation);

    Optional<Allocation> findById(Long id);

    /** The student's current room, if they hold one. */
    Optional<Allocation> findByStudentIdAndActiveTrue(Long studentId);

    /**
     * Live occupancy of a room.
     *
     * <p>Only trustworthy while the caller holds the room's write lock -- see
     * {@link RoomRepository#findByIdForUpdate}.
     */
    long countByRoomIdAndActiveTrue(Long roomId);

    List<Allocation> findByRoomIdAndActiveTrue(Long roomId);

    /**
     * Everyone currently living in a room, with their accounts loaded.
     *
     * <p>The fetch joins are the difference between one query and one-plus-two-per-
     * occupant. {@code findByRoomIdAndActiveTrue} above is fine for counting or for
     * touching a single row; this is the one to use when the students themselves are
     * going into a response.
     */
    @Query("""
            select a from Allocation a
            join fetch a.student s
            join fetch s.user u
            join fetch a.room r
            where a.room.id = :roomId and a.active = true
            order by s.rollNumber asc
            """)
    List<Allocation> findOccupantsOfRoom(@Param("roomId") Long roomId);

    /**
     * Active allocations for a set of rooms, counted in one statement.
     *
     * <p>A room listing needs live occupancy for every row on the page. Counting
     * per room is the textbook N+1; this returns the whole page's counts at once and
     * the service zips them onto the rooms. Rooms with nobody in them are simply
     * absent from the result, which the caller reads as zero.
     */
    @Query(value = """
            SELECT a.room_id AS roomId, count(*) AS occupied
            FROM allocations a
            WHERE a.active AND a.room_id IN (:roomIds)
            GROUP BY a.room_id
            """, nativeQuery = true)
    List<RoomOccupancyRow> countActiveByRoomIds(@Param("roomIds") Collection<Long> roomIds);

    /** Projection for {@link #countActiveByRoomIds}. */
    interface RoomOccupancyRow {

        Long getRoomId();

        long getOccupied();
    }

    @Query(value = """
            select a from Allocation a
            join fetch a.student s
            join fetch s.user u
            join fetch a.room r
            where a.active = true and s.gender in :genders
            """,
            countQuery = """
                    select count(a) from Allocation a
                    where a.active = true and a.student.gender in :genders
                    """)
    Page<Allocation> findActiveInScope(@Param("genders") Collection<Gender> genders, Pageable pageable);

    @Query("""
            select a from Allocation a
            join fetch a.student s
            join fetch s.user u
            join fetch a.room r
            where a.student.id = :studentId
            order by a.allocatedAt desc
            """)
    List<Allocation> findHistoryForStudent(@Param("studentId") Long studentId);
}
