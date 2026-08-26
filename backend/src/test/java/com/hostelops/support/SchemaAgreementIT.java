package com.hostelops.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the hand-written schema and the JPA entities still describe the same
 * database, and that the guarantees which live only in the database are present.
 *
 * <p>This project deliberately does not let Hibernate create the schema:
 * {@code ddl-auto} is {@code none} everywhere and every table arrives through a
 * Flyway migration. That choice buys reviewable, ordered, production-safe DDL,
 * and it costs exactly one thing -- nothing checks that an entity and its table
 * agree any more. A renamed column in an entity used to be a startup failure;
 * with {@code none} it becomes a runtime failure on whichever endpoint touches
 * that column first, discovered by a user.
 *
 * <p>So this one class raises {@code ddl-auto} to {@code validate}. Hibernate
 * then compares every entity against the real, migrated database at startup and
 * refuses to boot on a mismatch. The context loading <em>is</em> the assertion;
 * the tests below add what validation does not look at.
 *
 * <p>What it does not look at is most of the interesting part. Hibernate checks
 * tables and columns, and ignores unique indexes, partial indexes, check
 * constraints and triggers entirely -- which is to say it ignores every
 * database-level guarantee this system's correctness argument rests on. The
 * capacity trigger and the one-active-allocation index are the last line of
 * defence behind the pessimistic lock in {@code AllocationService}; if a future
 * migration drops one, the application-level check still passes its own tests
 * and the defence in depth is silently gone. Naming them here means that
 * migration fails CI instead.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SchemaAgreementIT extends AbstractPostgresIT {

    @Nested
    @DisplayName("entity and schema agreement")
    class Agreement {

        @Test
        @DisplayName("every entity validates against the migrated schema")
        void entitiesMatchTheMigratedSchema() {
            // Reaching this line means Hibernate's schema validator accepted every
            // @Entity against the tables Flyway built. A mismatch -- a renamed column, a
            // wrong type, a missing table -- fails the context before any test body runs.
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("the schema came from migrations, not from Hibernate")
        void migrationsAreTheSourceOfTruth() {
            List<String> applied = jdbc.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                    String.class);

            assertThat(applied).containsExactly("1", "2");
        }

        @Test
        @DisplayName("no migration is recorded as failed")
        void noFailedMigrations() {
            Integer failures = jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);

            assertThat(failures).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "users", "refresh_tokens", "students", "rooms", "applications", "allocations",
                "attendance", "absence_alerts", "complaints", "hostel_fees", "fee_payments",
                "fee_reminders", "notices", "audit_events"})
        @DisplayName("the table exists")
        void tableExists(String table) {
            assertThat(tableExistsInDatabase(table)).as(table).isTrue();
        }
    }

    @Nested
    @DisplayName("guarantees Hibernate never checks")
    class DatabaseLevelGuarantees {

        @ParameterizedTest(name = "{0} -- {1}")
        @CsvSource(delimiter = '|', value = {
                "uq_allocations_active_student | one active allocation per student, "
                        + "the constraint that makes double-booking a database error and not a bug report",
                "uq_applications_one_pending   | one pending application per student",
                "uq_fee_payments_provider_ref  | one payment row per gateway reference",
        })
        @DisplayName("the partial unique index survives")
        void partialUniqueIndexExists(String indexName, String why) {
            // Partial (WHERE-clauses) unique indexes cannot be expressed as JPA
            // constraints, so nothing in the Java model refers to them. They exist only
            // in the migration, which is exactly why a test has to name them.
            assertThat(indexExists(indexName)).as(why).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "idx_rooms_match",
                "idx_students_user",
                "idx_allocations_room",
                "idx_allocations_student",
                "idx_attendance_student_date",
                "idx_students_gender_alloc",
                "idx_hostel_fees_student"})
        @DisplayName("the index the scoped queries depend on is present")
        void scopedQueryIndexExists(String indexName) {
            // Every one of these backs a filter that runs on every warden request. An
            // index dropped by accident does not fail a functional test -- it just makes
            // the listing endpoints scan, which is invisible on seeded data and obvious
            // on real data.
            assertThat(indexExists(indexName)).as(indexName).isTrue();
        }

        @Test
        @DisplayName("the room capacity trigger is installed on allocations")
        void capacityTriggerExists() {
            Integer triggers = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                            + "WHERE NOT t.tgisinternal AND c.relname = 'allocations' "
                            + "AND t.tgname = 'trg_allocations_capacity'",
                    Integer.class);

            // The application takes a row lock and counts before inserting, and that is
            // what produces a clean 409. This trigger is what happens if that logic is
            // ever bypassed -- a manual INSERT, a future service, a data fix run by hand.
            // AllocationConcurrencyIT proves the lock works; this proves the net is still
            // under it.
            assertThat(triggers).isEqualTo(1);
        }

        @Test
        @DisplayName("the capacity function the trigger calls still exists")
        void capacityFunctionExists() {
            Integer functions = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_proc WHERE proname = 'enforce_room_capacity'", Integer.class);

            assertThat(functions).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ck_students_gender",
                "ck_students_year",
                "ck_students_alloc_status",
                "ck_students_mobile"})
        @DisplayName("the check constraint on students is present")
        void studentCheckConstraintExists(String constraintName) {
            // Enum-valued columns are CHECK-constrained VARCHARs rather than Postgres
            // ENUM types: adding a value is an ALTER of one constraint instead of an
            // ALTER TYPE that cannot run inside a transaction. The tradeoff only pays off
            // while the constraints are actually there.
            assertThat(constraintExists("students", constraintName)).as(constraintName).isTrue();
        }

        @Test
        @DisplayName("the room reference data the matcher needs is loaded")
        void roomInventoryIsPresent() {
            Integer rooms = jdbc.queryForObject(
                    "SELECT count(*) FROM rooms WHERE block NOT LIKE 'IT-%'", Integer.class);

            // 5 wings x 4 floors x 6 rooms. Rooms are reference data, not fixtures: the
            // allocation matcher has nothing to match against in an empty database, so
            // they ship in a migration. Demo *users* deliberately do not.
            assertThat(rooms).isEqualTo(120);
        }

        @Test
        @DisplayName("no migration ships a user account")
        void migrationsSeedNoCredentials() {
            // Everything in `users` at this point was seeded by a test helper, which
            // prefixes its usernames. A row from anywhere else would mean a migration
            // created an account -- which is how a default password ends up in
            // production, and is the specific thing this rebuild set out to remove.
            List<String> unexpected = jdbc.queryForList(
                    "SELECT username FROM users WHERE username NOT LIKE 'it_%'", String.class);

            assertThat(unexpected).isEmpty();
        }
    }

    // ---- helpers ----

    private boolean tableExistsInDatabase(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
        return count != null && count == 1;
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class, indexName);
        return count != null && count == 1;
    }

    private boolean constraintExists(String table, String constraintName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid "
                        + "WHERE rel.relname = ? AND con.conname = ?",
                Integer.class, table, constraintName);
        return count != null && count == 1;
    }
}
