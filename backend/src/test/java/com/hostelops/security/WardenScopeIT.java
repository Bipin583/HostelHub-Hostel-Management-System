package com.hostelops.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.domain.Gender;
import com.hostelops.domain.HostelScope;
import com.hostelops.domain.HostelType;
import com.hostelops.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Row-level authorisation, asserted over HTTP.
 *
 * <p>Role gates are the easy half and Spring Security handles them declaratively.
 * The half that actually goes wrong is data scoping: a warden who is legitimately
 * allowed to call an endpoint but must only see their own hostel's rows. In the
 * predecessor that was a pair of helper functions each view had to remember to
 * call, so the security property was "every author remembered", which is not a
 * property. Here it comes from {@link AccessScope} narrowing the values a query
 * can match, and the tests below check the result at the boundary rather than
 * trusting the helper.
 *
 * <h2>Why 404 and not 403 for a row out of scope</h2>
 *
 * <p>An MH warden asking about an LH student gets "not found", because to that
 * warden the row does not exist. A 403 would confirm the id is real, which turns
 * an enumeration of ids into an enumeration of the other hostel's roster. 403 is
 * reserved for the case where the caller is asking about something they can name
 * legitimately but may not act on -- a student reaching for another student's
 * record.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WardenScopeIT extends AbstractPostgresIT {

    private static final String ALLOCATIONS = "/api/v1/warden/allocations";
    private static final String STUDENTS = "/api/v1/warden/students";
    private static final String MY_ALLOCATIONS = "/api/v1/student/allocations";
    private static final String MY_PROFILE = "/api/v1/student/me";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Nested
    @DisplayName("a warden's scope narrows the rows, not just the routes")
    class ScopeNarrowsRows {

        @Test
        @DisplayName("an MH warden cannot see a female student")
        void mhWardenCannotReachAnLhStudent() {
            String mhWarden = accessTokenFor(seedWarden(HostelScope.MH), HostelScope.MH);
            SeededStudent femaleStudent = seedStudent(Gender.F, 2);
            SeededRoom mhRoom = seedRoom(HostelType.MH, 2, 4);

            ResponseEntity<String> response =
                    allocate(mhWarden, femaleStudent.studentId(), mhRoom.roomId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCodeOf(response)).isEqualTo("NOT_FOUND");
            // The message must not confirm the student exists.
            assertThat(response.getBody()).doesNotContain(femaleStudent.rollNumber());
        }

        @Test
        @DisplayName("an LH warden cannot see a men's-hostel room")
        void lhWardenCannotReachABhRoom() {
            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);
            SeededStudent femaleStudent = seedStudent(Gender.F, 2);
            SeededRoom bhRoom = seedRoom(HostelType.BH, 2, 4);

            ResponseEntity<String> response =
                    allocate(lhWarden, femaleStudent.studentId(), bhRoom.roomId());

            // The student is in scope, so the room is what is missing -- and the
            // room lookup is scoped too, which is the point.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(activeAllocationCount(bhRoom.roomId())).isZero();
        }

        @Test
        @DisplayName("an MH warden covers both men's hostels, BH and MH")
        void mhWardenSpansBothMensHostels() {
            String mhWarden = accessTokenFor(seedWarden(HostelScope.MH), HostelScope.MH);

            // The asymmetry is real, not a bug: the men's wardenship covers two
            // buildings, so HostelScope.MH maps to {BH, MH} while LH maps to {LH}.
            SeededRoom bhRoom = seedRoom(HostelType.BH, 1, 2);
            SeededRoom mhRoom = seedRoom(HostelType.MH, 1, 2);

            assertThat(allocate(mhWarden, seedStudent(Gender.M, 1).studentId(), bhRoom.roomId())
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(allocate(mhWarden, seedStudent(Gender.M, 1).studentId(), mhRoom.roomId())
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("an admin is the widest scope, not a special case")
        void adminSeesEveryHostel() {
            String admin = accessTokenForAdmin(seedAdmin());
            SeededRoom lhRoom = seedRoom(HostelType.LH, 3, 2);
            SeededRoom bhRoom = seedRoom(HostelType.BH, 3, 2);

            // Admin is expressed as "all genders, all hostel types" rather than an
            // `if (isAdmin) skipTheFilter` branch, so an endpoint cannot accidentally
            // grant an admin more than the scope model describes -- nor forget to
            // grant them anything.
            assertThat(allocate(admin, seedStudent(Gender.F, 3).studentId(), lhRoom.roomId())
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(allocate(admin, seedStudent(Gender.M, 3).studentId(), bhRoom.roomId())
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("a listing shows only the caller's own hostel")
        void listingIsScoped() throws Exception {
            SeededRoom lhRoom = seedRoom(HostelType.LH, 4, 2);
            SeededRoom bhRoom = seedRoom(HostelType.BH, 4, 2);
            String admin = accessTokenForAdmin(seedAdmin());

            SeededStudent female = seedStudent(Gender.F, 4);
            SeededStudent male = seedStudent(Gender.M, 4);
            allocate(admin, female.studentId(), lhRoom.roomId());
            allocate(admin, male.studentId(), bhRoom.roomId());

            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);
            String body = rest.exchange(ALLOCATIONS + "?size=200", HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(lhWarden)), String.class).getBody();

            // Asserting on the roll numbers this test seeded, not on page totals:
            // the database is shared with every other IT, so a count assertion here
            // would depend on execution order.
            assertThat(body).contains(female.rollNumber());
            assertThat(body).doesNotContain(male.rollNumber());
        }

        @Test
        @DisplayName("the student search applies scope to the filter, not after it")
        void studentSearchIsScoped() {
            SeededStudent female = seedStudent(Gender.F, 4);
            SeededStudent male = seedStudent(Gender.M, 4);
            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);
            String admin = accessTokenForAdmin(seedAdmin());

            // Searching for her own hostel's student finds her.
            assertThat(search(lhWarden, female.rollNumber())).contains(female.rollNumber());

            // Searching by an exact roll number that exists but is out of scope
            // returns nothing at all -- the scope predicate is part of the query, so
            // there is no row for the filter to match and nothing to accidentally
            // leak through a count or a page total.
            String outOfScope = search(lhWarden, male.rollNumber());
            assertThat(outOfScope).doesNotContain(male.rollNumber());
            assertThat(outOfScope).contains("\"totalElements\":0");

            // The same search as an admin proves the row is really there, so the
            // assertion above is about scoping rather than about a broken filter.
            assertThat(search(admin, male.rollNumber())).contains(male.rollNumber());
        }

        @Test
        @DisplayName("a year filter and the scope filter combine rather than replace each other")
        void studentSearchCombinesFilters() {
            SeededStudent yearOne = seedStudent(Gender.F, 1);
            SeededStudent yearThree = seedStudent(Gender.F, 3);
            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);

            String body = rest.exchange(
                    STUDENTS + "?yearOfStudy=1&query=" + yearOne.rollNumber() + "&size=50",
                    HttpMethod.GET, new HttpEntity<>(jsonWithToken(lhWarden)), String.class).getBody();

            assertThat(body).contains(yearOne.rollNumber());
            assertThat(body).doesNotContain(yearThree.rollNumber());
        }

        @Test
        @DisplayName("an out-of-range year is a 400, not a 500")
        void studentSearchRejectsAnImpossibleYear() {
            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);

            ResponseEntity<String> response = rest.exchange(STUDENTS + "?yearOfStudy=99",
                    HttpMethod.GET, new HttpEntity<>(jsonWithToken(lhWarden)), String.class);

            // Parameter constraints are enforced by the handler adapter in Spring 6.1+,
            // which raises HandlerMethodValidationException. Without an explicit
            // handler for it that lands in the catch-all and reports a client mistake
            // as a server fault.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCodeOf(response)).isEqualTo("VALIDATION_FAILED");
        }

        private String search(String token, String query) {
            return rest.exchange(STUDENTS + "?size=50&query=" + query, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(token)), String.class).getBody();
        }
    }

    @Nested
    @DisplayName("role gates")
    class RoleGates {

        @Test
        @DisplayName("a student token is refused at a warden endpoint with 403")
        void studentCannotReachWardenEndpoints() {
            SeededStudent student = seedStudent(Gender.F, 1);
            String token = accessTokenForStudent(student);

            ResponseEntity<String> response = rest.exchange(ALLOCATIONS, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(token)), String.class);

            // 403, not 404: the caller is authenticated and the route exists, they
            // are simply not permitted. Hiding the route's existence buys nothing
            // here because the route is in the published OpenAPI spec anyway.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCodeOf(response)).isEqualTo("FORBIDDEN");
        }

        @Test
        @DisplayName("a student reading another student's allocation gets 403 OUT_OF_SCOPE")
        void studentCannotReadAnotherStudent() {
            SeededStudent self = seedStudent(Gender.F, 1);
            SeededStudent other = seedStudent(Gender.F, 1);
            String token = accessTokenForStudent(self);

            // Deliberately the student-facing route. The warden route with the same
            // shape sits behind a role gate, so a student is turned away there before
            // any ownership check runs -- testing it would assert the right status for
            // the wrong reason and would keep passing if requireSelf were deleted.
            ResponseEntity<String> response = rest.exchange(
                    MY_ALLOCATIONS + "/" + other.studentId(), HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(token)), String.class);

            // Here 403 is right and 404 would be wrong: the student can see that
            // other students exist, so there is nothing to conceal, and OUT_OF_SCOPE
            // tells a client this is a permission problem it should not retry.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCodeOf(response)).isEqualTo("OUT_OF_SCOPE");
        }

        @Test
        @DisplayName("a student reading their own allocation is allowed through the same route")
        void studentCanReadTheirOwnAllocation() {
            SeededStudent self = seedStudent(Gender.F, 1);
            String token = accessTokenForStudent(self);

            ResponseEntity<String> response = rest.exchange(
                    MY_ALLOCATIONS + "/" + self.studentId(), HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(token)), String.class);

            // 409 rather than 200 because this student holds no room yet -- which is
            // the point: the ownership check passed and the request reached the
            // business rule. A 403 here would mean requireSelf was rejecting the
            // caller's own id.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCodeOf(response)).isEqualTo("STUDENT_NOT_ALLOCATED");
        }

        @Test
        @DisplayName("a student's own profile needs no id and cannot be aimed at anyone else")
        void studentProfileComesFromTheToken() {
            SeededStudent self = seedStudent(Gender.F, 3);
            SeededStudent other = seedStudent(Gender.F, 3);
            String token = accessTokenForStudent(self);

            ResponseEntity<String> response = rest.exchange(MY_PROFILE, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(token)), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .contains(self.rollNumber())
                    .doesNotContain(other.rollNumber());
        }

        @Test
        @DisplayName("a request with no token gets the standard 401 envelope, not an HTML page")
        void anonymousIsRejectedWithJson() {
            ResponseEntity<String> response = rest.getForEntity(ALLOCATIONS, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCodeOf(response)).isEqualTo("UNAUTHENTICATED");
            // A typed client parses one shape for every failure. A container-default
            // HTML error page here would break that contract at the least convenient
            // moment.
            assertThat(response.getHeaders().getContentType())
                    .isNotNull()
                    .satisfies(type -> assertThat(type.includes(MediaType.APPLICATION_JSON)).isTrue());
        }

        @Test
        @DisplayName("a forged token is rejected, not merely unparsed")
        void aTamperedTokenIsRejected() {
            String valid = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);
            // Flip the last character of the signature. The claims still decode, so
            // anything that read them without verifying would sail past this.
            char last = valid.charAt(valid.length() - 1);
            String forged = valid.substring(0, valid.length() - 1) + (last == 'A' ? 'B' : 'A');

            ResponseEntity<String> response = rest.exchange(ALLOCATIONS, HttpMethod.GET,
                    new HttpEntity<>(jsonWithToken(forged)), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a token whose scope claim was widened is still only its own scope")
        void scopeComesFromTheSignedClaimNotTheRequest() {
            String lhWarden = accessTokenFor(seedWarden(HostelScope.LH), HostelScope.LH);
            SeededRoom bhRoom = seedRoom(HostelType.BH, 2, 2);
            SeededStudent male = seedStudent(Gender.M, 2);

            HttpHeaders headers = jsonWithToken(lhWarden);
            // A client asserting its own scope in a header, which the server must
            // ignore entirely. Scope is read from the signed token and nowhere else.
            headers.add("X-Hostel-Scope", "MH");

            ResponseEntity<String> response = rest.exchange(ALLOCATIONS, HttpMethod.POST,
                    new HttpEntity<>("{\"studentId\":%d,\"roomId\":%d}"
                            .formatted(male.studentId(), bhRoom.roomId()), headers),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(activeAllocationCount(bhRoom.roomId())).isZero();
        }
    }

    // ---- helpers ----

    private ResponseEntity<String> allocate(String token, long studentId, long roomId) {
        String body = "{\"studentId\":%d,\"roomId\":%d}".formatted(studentId, roomId);
        return rest.exchange(ALLOCATIONS, HttpMethod.POST,
                new HttpEntity<>(body, jsonWithToken(token)), String.class);
    }
}
