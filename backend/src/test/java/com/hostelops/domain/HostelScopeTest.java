package com.hostelops.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The containment rule at its source.
 *
 * <p>{@code AccessScopeTest} covers how a caller is contained; this covers the
 * table the containment reads from. They are separate because the enum is the one
 * definition the rest of the codebase trusts -- the scoped repository queries
 * take its sets as parameters -- so it is worth pinning down on its own terms
 * rather than only through a caller.
 */
class HostelScopeTest {

    @Test
    @DisplayName("the ladies' hostel scope covers exactly one hostel and one gender")
    void ladiesScope() {
        assertThat(HostelScope.LH.gender()).isEqualTo(Gender.F);
        assertThat(HostelScope.LH.hostelTypes()).containsExactly(HostelType.LH);
        assertThat(HostelScope.LH.covers(HostelType.LH)).isTrue();
        assertThat(HostelScope.LH.covers(HostelType.BH)).isFalse();
        assertThat(HostelScope.LH.covers(HostelType.MH)).isFalse();
        assertThat(HostelScope.LH.covers(Gender.F)).isTrue();
        assertThat(HostelScope.LH.covers(Gender.M)).isFalse();
    }

    @Test
    @DisplayName("the men's hostel scope covers two hostels, which is the asymmetry")
    void mensScope() {
        // BH and MH are separate buildings under one warden. Any code that assumes
        // one scope means one hostel is wrong, and this is where that shows.
        assertThat(HostelScope.MH.gender()).isEqualTo(Gender.M);
        assertThat(HostelScope.MH.hostelTypes())
                .containsExactlyInAnyOrder(HostelType.BH, HostelType.MH);
        assertThat(HostelScope.MH.covers(HostelType.BH)).isTrue();
        assertThat(HostelScope.MH.covers(HostelType.MH)).isTrue();
        assertThat(HostelScope.MH.covers(HostelType.LH)).isFalse();
        assertThat(HostelScope.MH.covers(Gender.M)).isTrue();
        assertThat(HostelScope.MH.covers(Gender.F)).isFalse();
    }

    @Test
    @DisplayName("forHostel agrees with covers for every hostel")
    void forHostelAgreesWithCovers() {
        // Two ways of asking the same question. If they ever disagree, one caller
        // is being contained differently from another.
        for (HostelType hostelType : HostelType.values()) {
            HostelScope governing = HostelScope.forHostel(hostelType);
            assertThat(governing.covers(hostelType)).isTrue();
            for (HostelScope other : HostelScope.values()) {
                if (other != governing) {
                    assertThat(other.covers(hostelType)).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("a hostel with no governing scope is an error, not a silent null")
    void forHostelRejectsTheUngoverned() {
        // The failure mode this guards: returning null here would give the caller a
        // scope object that filters nothing.
        assertThatThrownBy(() -> HostelScope.forHostel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No scope governs hostel");
    }

    @Test
    @DisplayName("a scope's hostel set cannot be edited by a caller")
    void hostelTypesAreImmutable() {
        // These sets are handed straight to repository queries. A caller that could
        // add to one would widen a warden's visibility for the life of the JVM,
        // since the enum constant is shared.
        assertThatThrownBy(() -> HostelScope.LH.hostelTypes().add(HostelType.BH))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("each gender has exactly one governing scope")
    void gendersMapOneToOne() {
        // Relied on by the scoped student queries: a student's gender determines
        // which warden may see them, with no ambiguity to resolve.
        for (Gender gender : Gender.values()) {
            long governing = java.util.Arrays.stream(HostelScope.values())
                    .filter(scope -> scope.covers(gender))
                    .count();
            assertThat(governing).as("scopes covering %s", gender).isEqualTo(1);
        }
    }
}
