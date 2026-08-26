package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.domain.Role;
import com.hostelops.exception.ApiException;
import com.hostelops.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Warden containment, tested as a rule rather than as a side effect of some
 * endpoint.
 *
 * <p>The predecessor's equivalent logic lived in two helper functions that every
 * view had to remember to call, so "is a warden actually contained?" could only be
 * answered by auditing every view in the project. Here it is one value object with
 * one set of tests, and an endpoint inherits the behaviour by construction.
 */
class AccessScopeTest {

    @Nested
    @DisplayName("a ladies' hostel warden")
    class LadiesHostelWarden {

        private final AccessScope scope = new AccessScope(1L, Role.WARDEN, HostelScope.LH, null);

        @Test
        void seesOnlyFemaleStudents() {
            assertThat(scope.visibleGenders()).containsExactly(Gender.F);
        }

        @Test
        void seesOnlyTheLadiesHostel() {
            assertThat(scope.visibleHostelTypes()).containsExactly(HostelType.LH);
        }

        @Test
        void isRefusedAMaleStudent() {
            assertThatThrownBy(() -> scope.requireVisible(Gender.M))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.OUT_OF_SCOPE);
        }

        @Test
        @DisplayName("is refused both men's hostels, not just one of them")
        void isRefusedEitherMensHostel() {
            assertThatThrownBy(() -> scope.requireVisible(HostelType.BH))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.OUT_OF_SCOPE);
            assertThatThrownBy(() -> scope.requireVisible(HostelType.MH))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.OUT_OF_SCOPE);
        }

        @Test
        void isAllowedTheirOwnHostelAndTheirOwnStudents() {
            scope.requireVisible(HostelType.LH);
            scope.requireVisible(Gender.F);
        }
    }

    @Nested
    @DisplayName("a men's hostel warden")
    class MensHostelWarden {

        private final AccessScope scope = new AccessScope(2L, Role.WARDEN, HostelScope.MH, null);

        @Test
        void seesOnlyMaleStudents() {
            assertThat(scope.visibleGenders()).containsExactly(Gender.M);
        }

        @Test
        @DisplayName("covers both men's hostels, which is the asymmetry in this domain")
        void seesBothMensHostels() {
            assertThat(scope.visibleHostelTypes())
                    .containsExactlyInAnyOrder(HostelType.BH, HostelType.MH);
            scope.requireVisible(HostelType.BH);
            scope.requireVisible(HostelType.MH);
        }

        @Test
        void isRefusedTheLadiesHostel() {
            assertThatThrownBy(() -> scope.requireVisible(HostelType.LH))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.OUT_OF_SCOPE);
        }

        @Test
        void isRefusedAFemaleStudent() {
            assertThatThrownBy(() -> scope.requireVisible(Gender.F))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("an admin")
    class Admin {

        private final AccessScope scope = new AccessScope(3L, Role.ADMIN, null, null);

        @Test
        @DisplayName("is the widest set, not a special case in the calling code")
        void seesEverything() {
            // The point of this test: services contain no `if (isAdmin())` branch.
            // An admin runs the same scoped query as a warden, with a wider IN list.
            assertThat(scope.visibleGenders()).containsExactlyInAnyOrder(Gender.M, Gender.F);
            assertThat(scope.visibleHostelTypes())
                    .containsExactlyInAnyOrder(HostelType.LH, HostelType.BH, HostelType.MH);
            assertThat(scope.visibleGenders()).hasSameSizeAs(Gender.values());
            assertThat(scope.visibleHostelTypes()).hasSameSizeAs(HostelType.values());
        }

        @Test
        void isRefusedNothing() {
            for (Gender gender : Gender.values()) {
                scope.requireVisible(gender);
            }
            for (HostelType hostelType : HostelType.values()) {
                scope.requireVisible(hostelType);
            }
        }

        @Test
        @DisplayName("may act on any student's record, unlike a student")
        void isNotRestrictedToSelf() {
            scope.requireSelf(999L);
        }
    }

    @Nested
    @DisplayName("a student")
    class StudentCaller {

        private final AccessScope scope = new AccessScope(4L, Role.STUDENT, null, 42L);

        @Test
        void mayActOnTheirOwnRecord() {
            scope.requireSelf(42L);
        }

        @Test
        void mayNotActOnSomeoneElsesRecord() {
            assertThatThrownBy(() -> scope.requireSelf(43L))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).getCode())
                    .isEqualTo(ErrorCode.OUT_OF_SCOPE);
        }

        @Test
        @DisplayName("is contained by identity, not by gender filtering")
        void isGovernedBySelfChecksNotGenderFilters() {
            // A student's containment is identity-based: their endpoints call
            // requireSelf, and nothing about them relies on visibleGenders().
            // Asserted so anyone tempted to secure a student endpoint with a gender
            // filter alone can see here that it would not narrow anything.
            assertThat(scope.visibleGenders()).containsExactlyInAnyOrder(Gender.M, Gender.F);
        }
    }

    @Nested
    @DisplayName("the containment rule itself")
    class ContainmentRule {

        @Test
        @DisplayName("every hostel is governed by some scope")
        void everyHostelHasAGoverningScope() {
            // If a fourth hostel is ever added, this fails until the enum is
            // updated -- which is the whole reason the mapping lives in one place.
            for (HostelType hostelType : HostelType.values()) {
                assertThat(HostelScope.forHostel(hostelType)).isNotNull();
            }
        }

        @Test
        @DisplayName("no hostel is governed by both scopes")
        void scopesDoNotOverlap() {
            assertThat(HostelScope.LH.hostelTypes())
                    .doesNotContainAnyElementsOf(HostelScope.MH.hostelTypes());
        }

        @Test
        @DisplayName("between them the scopes cover the whole estate")
        void scopesPartitionTheEstate() {
            var covered = new java.util.HashSet<HostelType>();
            for (HostelScope scope : HostelScope.values()) {
                covered.addAll(scope.hostelTypes());
            }
            assertThat(covered).containsExactlyInAnyOrder(HostelType.values());
        }

        @Test
        @DisplayName("a warden with no scope is a state two other layers already refuse")
        void wardenWithoutScopeIsGuardedElsewhere() {
            // ck_users_scope_matches_role rejects such a row, and JwtService rejects a
            // WARDEN token carrying no scope claim. This test documents that the guard
            // is held in the schema and the token parser rather than here: a scopeless
            // warden reaching AccessScope would fall through to the admin-wide set,
            // which is precisely why it must never get this far.
            AccessScope scopeless = new AccessScope(5L, Role.WARDEN, null, null);
            assertThat(scopeless.isWarden()).isTrue();
            assertThat(scopeless.hostelScope()).isNull();
            assertThat(scopeless.visibleHostelTypes()).hasSameSizeAs(HostelType.values());
        }
    }
}
