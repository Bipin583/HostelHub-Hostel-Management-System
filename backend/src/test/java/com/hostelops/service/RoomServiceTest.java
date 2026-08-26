package com.hostelops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Role;
import com.hostelops.domain.Room;
import com.hostelops.dto.room.OccupancyResponse;
import com.hostelops.dto.room.RoomResponse;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import com.hostelops.mapper.RoomMapper;
import com.hostelops.mapper.StudentMapper;
import com.hostelops.repository.AllocationRepository;
import com.hostelops.repository.RoomRepository;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Room reads, occupancy arithmetic, and the scope filter on both.
 *
 * <p>Two of the behaviours pinned here look like trivia and are not. "A room with no
 * allocation rows reads as zero occupied" is the difference between an empty room
 * showing 0/3 and it vanishing from the listing with a null; "a block with no beds
 * is 0%" is the difference between a dashboard tile and {@code NaN%}. Both are the
 * kind of thing that works on seeded demo data -- where every room has an occupant
 * and every block has beds -- and breaks the first time the data is real.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    private static final long WARDEN_USER_ID = 7L;

    @Mock
    private RoomRepository rooms;
    @Mock
    private AllocationRepository allocations;
    @Mock
    private CurrentUserProvider currentUser;

    @Captor
    private ArgumentCaptor<Collection<HostelType>> hostelTypes;
    @Captor
    private ArgumentCaptor<Collection<String>> hostelTypeNames;

    private RoomService serviceFor(Role role, HostelScope hostelScope) {
        AppUserPrincipal principal = new AppUserPrincipal(
                WARDEN_USER_ID, "caller", null, role, hostelScope, null, true);
        lenient().when(currentUser.scope()).thenReturn(principal.accessScope());
        lenient().when(currentUser.require()).thenReturn(principal);
        RoomMapper roomMapper = new RoomMapper();
        return new RoomService(
                rooms, allocations, roomMapper, new StudentMapper(roomMapper), currentUser);
    }

    private RoomService asLadiesWarden() {
        return serviceFor(Role.WARDEN, HostelScope.LH);
    }

    private RoomService asMensWarden() {
        return serviceFor(Role.WARDEN, HostelScope.MH);
    }

    private RoomService asAdmin() {
        return serviceFor(Role.ADMIN, null);
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        @DisplayName("a ladies-hostel warden lists LH rooms only")
        void ladiesWardenSeesOneHostel() {
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            asLadiesWarden().list(PageRequest.of(0, 20));

            verify(rooms).findByHostelTypeIn(hostelTypes.capture(), any(Pageable.class));
            assertThat(hostelTypes.getValue()).containsExactly(HostelType.LH);
        }

        @Test
        @DisplayName("a men's-hostel warden lists both men's hostels, because MH scope covers BH and MH")
        void mensWardenSeesTwoHostels() {
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            asMensWarden().list(PageRequest.of(0, 20));

            verify(rooms).findByHostelTypeIn(hostelTypes.capture(), any(Pageable.class));
            // One warden, two buildings. This is the asymmetry that makes hostel scope a
            // set rather than a single value -- a scope modelled as one hostel type
            // would have needed a second warden account or a special case.
            assertThat(hostelTypes.getValue())
                    .containsExactlyInAnyOrder(HostelType.BH, HostelType.MH);
        }

        @Test
        @DisplayName("an admin lists every hostel -- the widest set, not a skipped filter")
        void adminSeesEveryHostel() {
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            asAdmin().list(PageRequest.of(0, 20));

            verify(rooms).findByHostelTypeIn(hostelTypes.capture(), any(Pageable.class));
            assertThat(hostelTypes.getValue())
                    .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(HostelType.class));
        }

        @Test
        @DisplayName("the block filter is applied on top of the scope, not instead of it")
        void blockFilterKeepsTheScope() {
            when(rooms.findByHostelTypeInAndBlock(anyCollection(), eq("A"), any(Pageable.class)))
                    .thenReturn(Page.empty());

            asLadiesWarden().listByBlock("A", PageRequest.of(0, 20));

            verify(rooms).findByHostelTypeInAndBlock(hostelTypes.capture(), eq("A"), any(Pageable.class));
            // Blocks are named per building, so "A" exists in LH and in BH. Dropping the
            // hostel filter here would let an LH warden read BH-A by guessing a letter.
            assertThat(hostelTypes.getValue()).containsExactly(HostelType.LH);
        }

        @Test
        @DisplayName("a room in another hostel reads as absent")
        void outOfScopeRoomIsNotFound() {
            when(rooms.findByIdAndHostelTypeIn(eq(404L), anyCollection())).thenReturn(Optional.empty());

            RoomService svc = asLadiesWarden();
            assertThatThrownBy(() -> svc.get(404L))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("occupants of an out-of-scope room are never queried")
        void occupantsRespectScope() {
            when(rooms.findByIdAndHostelTypeIn(eq(404L), anyCollection())).thenReturn(Optional.empty());

            RoomService svc = asLadiesWarden();
            assertThatThrownBy(() -> svc.occupants(404L)).isInstanceOf(ApiException.class);

            // The scope check comes first, so the roster query never runs. Fetching then
            // filtering would have read the other hostel's students into memory before
            // deciding not to return them.
            verify(allocations, never()).findOccupantsOfRoom(any());
        }
    }

    @Nested
    @DisplayName("occupancy on a page of rooms")
    class PageOccupancy {

        @Test
        @DisplayName("counts are zipped onto the rooms they belong to")
        void countsAreMatchedByRoomId() {
            Page<Room> page = pageOf(room(1L, "LH-101", 3), room(2L, "LH-102", 2));
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(page);
            when(allocations.countActiveByRoomIds(List.of(1L, 2L)))
                    .thenReturn(List.of(occupancyRow(2L, 2), occupancyRow(1L, 1)));

            List<RoomResponse> result = asLadiesWarden().list(PageRequest.of(0, 20)).getContent();

            // Deliberately returned out of order above: the rows are keyed by id, not
            // positionally zipped, so a database that returns them in any order is fine.
            assertThat(result).extracting(RoomResponse::id, RoomResponse::occupiedBeds, RoomResponse::freeBeds)
                    .containsExactly(tuple(1L, 1L, 2L), tuple(2L, 2L, 0L));
        }

        @Test
        @DisplayName("a room with no allocation rows reads as empty, not as missing")
        void absentRowMeansZero() {
            Page<Room> page = pageOf(room(1L, "LH-101", 3), room(2L, "LH-102", 4));
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(page);
            // Only room 1 appears: a GROUP BY produces no row for a room with nothing
            // to group. Reading that as null -- or skipping the room -- is how a wholly
            // empty room disappears from the listing that exists to find free beds.
            when(allocations.countActiveByRoomIds(List.of(1L, 2L)))
                    .thenReturn(List.of(occupancyRow(1L, 3)));

            List<RoomResponse> result = asLadiesWarden().list(PageRequest.of(0, 20)).getContent();

            assertThat(result).extracting(RoomResponse::id, RoomResponse::occupiedBeds, RoomResponse::freeBeds)
                    .containsExactly(tuple(1L, 3L, 0L), tuple(2L, 0L, 4L));
        }

        @Test
        @DisplayName("an empty page issues no count query at all")
        void emptyPageSkipsTheCountQuery() {
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(Page.empty());

            assertThat(asLadiesWarden().list(PageRequest.of(0, 20))).isEmpty();

            // Not just an optimisation. The count query is IN (:roomIds), and an empty
            // list renders as IN () -- a syntax error in Postgres. The guard is what
            // keeps the last page of a listing from 500ing.
            verify(allocations, never()).countActiveByRoomIds(anyCollection());
        }

        @Test
        @DisplayName("one page of rooms costs one count query, not one per room")
        void countsAreFetchedInASingleQuery() {
            Page<Room> page = pageOf(room(1L, "LH-101", 3), room(2L, "LH-102", 3), room(3L, "LH-103", 3));
            when(rooms.findByHostelTypeIn(anyCollection(), any(Pageable.class))).thenReturn(page);
            when(allocations.countActiveByRoomIds(List.of(1L, 2L, 3L)))
                    .thenReturn(List.of(occupancyRow(1L, 1)));

            asLadiesWarden().list(PageRequest.of(0, 20));

            // The N+1 this replaces would have called countByRoomIdAndActiveTrue three
            // times here and twenty times on a full page.
            verify(allocations).countActiveByRoomIds(List.of(1L, 2L, 3L));
            verify(allocations, never()).countByRoomIdAndActiveTrue(any());
        }

        @Test
        @DisplayName("a single room's count is authoritative, taken one row at a time")
        void singleRoomCountsDirectly() {
            when(rooms.findByIdAndHostelTypeIn(eq(1L), anyCollection()))
                    .thenReturn(Optional.of(room(1L, "LH-101", 3)));
            when(allocations.countByRoomIdAndActiveTrue(1L)).thenReturn(2L);

            RoomResponse response = asLadiesWarden().get(1L);

            assertThat(response.occupiedBeds()).isEqualTo(2L);
            assertThat(response.freeBeds()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("occupancy per block")
    class BlockOccupancy {

        @Test
        @DisplayName("percentages are rounded to one decimal at the edge")
        void roundsToOneDecimal() {
            when(rooms.findOccupancyByBlock(anyCollection()))
                    .thenReturn(List.of(occupancyByBlock("LH", "A", 4, 12, 4)));

            List<OccupancyResponse> result = asLadiesWarden().occupancyByBlock();

            // 4/12 is 33.333...; rounding here rather than in the frontend is what stops
            // the web app saying 33.3% and a CSV export saying 33.33%.
            assertThat(result).singleElement().satisfies(row -> {
                assertThat(row.occupancyPercent()).isEqualTo(33.3);
                assertThat(row.freeBeds()).isEqualTo(8L);
                assertThat(row.hostelType()).isEqualTo(HostelType.LH);
                assertThat(row.block()).isEqualTo("A");
            });
        }

        @Test
        @DisplayName("a block with no beds is 0%, not NaN")
        void zeroBedBlockIsZeroPercent() {
            when(rooms.findOccupancyByBlock(anyCollection()))
                    .thenReturn(List.of(occupancyByBlock("LH", "NEW", 0, 0, 0)));

            List<OccupancyResponse> result = asLadiesWarden().occupancyByBlock();

            // 0/0 in Java is NaN, which serialises to null in JSON and renders as blank
            // or "NaN%" on a dashboard. A block under construction is a real state, so
            // this is not a hypothetical division.
            assertThat(result).singleElement().satisfies(row -> {
                assertThat(row.occupancyPercent()).isEqualTo(0.0);
                assertThat(row.occupancyPercent()).isNotNaN();
                assertThat(row.freeBeds()).isZero();
            });
        }

        @Test
        @DisplayName("a full block is exactly 100%")
        void fullBlockIsOneHundred() {
            when(rooms.findOccupancyByBlock(anyCollection()))
                    .thenReturn(List.of(occupancyByBlock("LH", "B", 3, 9, 9)));

            assertThat(asLadiesWarden().occupancyByBlock()).singleElement().satisfies(row -> {
                assertThat(row.occupancyPercent()).isEqualTo(100.0);
                assertThat(row.freeBeds()).isZero();
            });
        }

        @Test
        @DisplayName("the aggregate is asked for the caller's hostels only")
        void aggregateIsScoped() {
            when(rooms.findOccupancyByBlock(anyCollection())).thenReturn(List.of());

            asMensWarden().occupancyByBlock();

            verify(rooms).findOccupancyByBlock(hostelTypeNames.capture());
            // Passed as names because the query is native SQL. An LH warden must not be
            // able to read the men's-hostel occupancy figures off a dashboard.
            assertThat(hostelTypeNames.getValue()).containsExactlyInAnyOrder("BH", "MH");
        }
    }

    // ---- fixtures ----

    private static Page<Room> pageOf(Room... rooms) {
        return new PageImpl<>(List.of(rooms), PageRequest.of(0, 20), rooms.length);
    }

    private static Room room(long id, String name, int capacity) {
        Room room = new Room();
        room.setId(id);
        room.setRoomName(name);
        room.setHostelType(HostelType.LH);
        room.setBlock("A");
        room.setFloor(1);
        room.setCapacity(capacity);
        room.setEligibleYear(2);
        room.setEligibleGender(Gender.F);
        return room;
    }

    /**
     * The projection Spring Data would materialise from the grouped count query.
     * Implemented by hand rather than mocked -- a two-method value has no behaviour
     * worth stubbing, and a real instance reads better at the call site.
     */
    private static AllocationRepository.RoomOccupancyRow occupancyRow(long roomId, long occupied) {
        return new AllocationRepository.RoomOccupancyRow() {
            @Override
            public Long getRoomId() {
                return roomId;
            }

            @Override
            public long getOccupied() {
                return occupied;
            }
        };
    }

    private static RoomRepository.OccupancyRow occupancyByBlock(
            String hostelType, String block, long roomCount, long totalBeds, long occupiedBeds) {
        return new RoomRepository.OccupancyRow() {
            @Override
            public String getHostelType() {
                return hostelType;
            }

            @Override
            public String getBlock() {
                return block;
            }

            @Override
            public long getRoomCount() {
                return roomCount;
            }

            @Override
            public long getTotalBeds() {
                return totalBeds;
            }

            @Override
            public long getOccupiedBeds() {
                return occupiedBeds;
            }
        };
    }
}
