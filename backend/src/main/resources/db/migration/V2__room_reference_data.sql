-- ============================================================================
-- V2: room inventory reference data.
--
-- Rooms are real reference data, not test fixtures: the allocation matcher has
-- nothing to match against without them, so they belong in a migration that
-- runs in every environment. Demo *users* deliberately do NOT live here -- see
-- DevDataSeeder, which is @Profile("dev") only. Shipping seeded credentials in
-- a migration would recreate exactly the default-password problem this rebuild
-- set out to remove.
--
-- Layout: floor N houses year-N students, three beds per room, six rooms per
-- floor, four floors per block.
--   LH blocks A,B  -> female     (2 x 4 x 6 x 3 = 144 beds)
--   BH blocks A,B  -> male       (144 beds)
--   MH block  A    -> male       ( 72 beds)
-- ============================================================================

INSERT INTO rooms (room_name, hostel_type, block, floor, capacity, eligible_year, eligible_gender)
SELECT
    spec.block || f.floor::text || lpad(r.n::text, 2, '0') AS room_name,
    spec.hostel_type,
    spec.block,
    f.floor,
    3                                                      AS capacity,
    f.floor                                                AS eligible_year,
    spec.eligible_gender
FROM (
    VALUES
        ('LH', 'A', 'F'),
        ('LH', 'B', 'F'),
        ('BH', 'A', 'M'),
        ('BH', 'B', 'M'),
        ('MH', 'A', 'M')
) AS spec (hostel_type, block, eligible_gender)
CROSS JOIN generate_series(1, 4) AS f (floor)
CROSS JOIN generate_series(1, 6) AS r (n);
