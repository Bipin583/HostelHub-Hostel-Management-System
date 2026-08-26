package com.hostelops.repository;

import com.hostelops.domain.HostelType;
import com.hostelops.domain.Room;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Room queries. Same rule as {@link StudentRepository}: no inherited unscoped
 * finder exists, and every collection method takes the caller's permitted hostel
 * types.
 */
public interface RoomRepository extends Repository<Room, Long> {

    Room save(Room room);

    Optional<Room> findByIdAndHostelTypeIn(Long id, Collection<HostelType> hostelTypes);

    Page<Room> findByHostelTypeIn(Collection<HostelType> hostelTypes, Pageable pageable);

    Page<Room> findByHostelTypeInAndBlock(
            Collection<HostelType> hostelTypes, String block, Pageable pageable);

    long countByHostelTypeIn(Collection<HostelType> hostelTypes);

    /**
     * Takes a row-level write lock on the room, then returns it.
     *
     * <p>This is the primary concurrency control for allocation. Postgres runs at
     * READ COMMITTED by default, where two transactions can each read "2 of 3 beds
     * taken" and each insert a third allocation, because neither sees the other's
     * uncommitted row. {@code SELECT ... FOR UPDATE} on the room forces the second
     * transaction to block until the first commits or rolls back, so its
     * subsequent count sees the committed truth.
     *
     * <p>The lock must be taken <em>before</em> the capacity count, not after --
     * counting first and locking second reintroduces exactly the window it is
     * meant to close.
     *
     * <p>Deliberately unscoped: this is called only after the room has already
     * been fetched through a scoped finder, and narrowing a {@code FOR UPDATE}
     * query by hostel type would make the lock conditional on authorization,
     * which is the wrong coupling.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") Long id);

    /**
     * Rooms this student is eligible for that still have a free bed, best first.
     *
     * <p>Ordered by fewest free beds so partly-filled rooms finish filling before
     * empty ones are opened -- fewer half-empty rooms at the end of intake.
     *
     * <p>The result is advisory only. Between this query and the insert, another
     * request can take the last bed, which is why the caller locks each candidate
     * with {@link #findByIdForUpdate} and re-counts before inserting, falling
     * through to the next candidate if it filled up. This query narrows the search;
     * the lock is what makes it correct.
     *
     * <p>Native SQL with string parameters rather than JPQL: an aggregate
     * {@code HAVING} over a left join expresses "has a free bed" in one pass, and
     * binding the enums as text avoids any ordinal-versus-name ambiguity in native
     * parameter binding.
     */
    @Query(value = """
            SELECT r.id
            FROM rooms r
            LEFT JOIN allocations a ON a.room_id = r.id AND a.active
            WHERE r.hostel_type IN (:hostelTypes)
              AND r.eligible_gender = :gender
              AND r.eligible_year = :yearOfStudy
            GROUP BY r.id, r.capacity
            HAVING COUNT(a.id) < r.capacity
            ORDER BY (r.capacity - COUNT(a.id)) ASC, r.id ASC
            """, nativeQuery = true)
    List<Long> findEligibleRoomIdsWithFreeBeds(
            @Param("hostelTypes") Collection<String> hostelTypes,
            @Param("gender") String gender,
            @Param("yearOfStudy") Integer yearOfStudy);

    /**
     * Occupancy per hostel and block, aggregated in the database.
     *
     * <p>The dashboard figure is computed here rather than by loading rooms and
     * allocations into the application and summing them in Java, which is what
     * turns a dashboard into a table scan as the data grows.
     */
    @Query(value = """
            SELECT r.hostel_type       AS hostelType,
                   r.block             AS block,
                   COUNT(DISTINCT r.id) AS roomCount,
                   SUM(r.capacity)     AS totalBeds,
                   COUNT(a.id)         AS occupiedBeds
            FROM rooms r
            LEFT JOIN allocations a ON a.room_id = r.id AND a.active
            WHERE r.hostel_type IN (:hostelTypes)
            GROUP BY r.hostel_type, r.block
            ORDER BY r.hostel_type, r.block
            """, nativeQuery = true)
    List<OccupancyRow> findOccupancyByBlock(@Param("hostelTypes") Collection<String> hostelTypes);

    /** Projection for {@link #findOccupancyByBlock}. */
    interface OccupancyRow {
        String getHostelType();

        String getBlock();

        long getRoomCount();

        long getTotalBeds();

        long getOccupiedBeds();
    }
}
