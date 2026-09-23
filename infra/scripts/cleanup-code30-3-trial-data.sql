-- One-time Code30.3 production trial-data cleanup (receipt v2).
--
-- Deletes exactly the 223 pinned rows of the five trial shifts opened from
-- 20 September 2026 and records one versioned replay receipt. Paid sales are
-- protected by append-only triggers; the owner approved a single guarded
-- exception: exactly four triggers are disabled inside this transaction and
-- must be re-enabled byte-identically before COMMIT. Audit rows, idempotency
-- receipts, delivered Sheets ledger rows and the invoice counter are retained.
-- The default is a dry run that deletes, verifies and always rolls back
-- without inserting the receipt or consuming an audit sequence value.
--
-- Required psql variables: cleanup_apply (true|false), expected_state_fingerprint,
-- backup_sha256, source_git_sha, executor_name.

\set ON_ERROR_STOP on
\set QUIET on

BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '300s';
-- Whole-row JSON fingerprints include timestamptz, bytea and float fields.
-- Pin their textual rendering so session settings cannot change a hash.
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL extra_float_digits = 1;

CREATE TEMP TABLE c3_inputs (
    apply boolean NOT NULL,
    expected_state_fingerprint text NOT NULL,
    backup_sha256 text NOT NULL,
    source_git_sha text NOT NULL,
    executor_name text NOT NULL
) ON COMMIT DROP;
INSERT INTO c3_inputs VALUES (
    :'cleanup_apply'::boolean,
    :'expected_state_fingerprint',
    :'backup_sha256',
    :'source_git_sha',
    :'executor_name'
);

DO $$
DECLARE i c3_inputs;
BEGIN
    SELECT * INTO i FROM c3_inputs;
    IF i.apply AND NOT (
        i.expected_state_fingerprint ~ '^[0-9a-f]{64}$'
        AND i.backup_sha256 ~ '^[0-9a-f]{64}$'
        AND i.source_git_sha ~ '^[0-9a-f]{40}$'
        AND i.executor_name ~ '^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,99}$'
    ) THEN
        RAISE EXCEPTION 'cleanup evidence or operator identity is invalid';
    END IF;
    IF NOT pg_try_advisory_xact_lock(hashtext('dcompany-code30.3-trial-cleanup')) THEN
        RAISE EXCEPTION 'another Code30.3 trial cleanup holds the advisory lock';
    END IF;
END
$$;

-- Any overlapping writer or reader must make this maintenance fail at once.
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT format('%I.%I', schemaname, tablename) AS name
          FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
    LOOP
        EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE NOWAIT', r.name);
    END LOOP;
END
$$;

DO $$
DECLARE revision text;
BEGIN
    SELECT version_num INTO revision FROM alembic_version;
    IF revision IS NULL OR revision NOT IN ('0078', '0082') THEN
        RAISE EXCEPTION 'expected database migration 0078 or 0082, found %', revision;
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION pg_temp.c3_table_digest(target regclass)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text, '
        'E''\n'' ORDER BY to_jsonb(t)::text), ''''), ''UTF8'')), ''hex'') FROM %s t',
        target
    );
END
$$;

-- Pinned targets from backup sha256 c437afd0e1b359c24fbf061b0803854b9ee38384fe23d50948b69d17e177b0ea (live audit max id 29179).
CREATE TEMP TABLE c3_target (table_name text NOT NULL, id uuid NOT NULL, PRIMARY KEY (table_name, id)) ON COMMIT DROP;
INSERT INTO c3_target (table_name, id) VALUES
    ('gaming_session_extensions', '2e821dd7-9586-4903-9f06-20467ae61aae'),
    ('gaming_session_extensions', '2fb24396-81a0-4525-b124-e0f89be675c9'),
    ('gaming_session_extensions', '58662d08-7bc8-49e8-81fb-8f1d5f8c3243'),
    ('gaming_session_extensions', '5bee040e-98f5-4692-95b0-3f32e0feb975'),
    ('gaming_session_extensions', '5dad85a1-5e01-42b7-ab0a-a99ebdcef043'),
    ('gaming_session_extensions', '9c2445d4-5f30-4de9-96a1-863d7bdf2ee5'),
    ('gaming_session_extensions', 'a7d7932b-3c1d-4291-863f-87bfa0ea005d'),
    ('gaming_session_extensions', 'ac7cf529-a163-4412-81ae-45266a2aa137'),
    ('gaming_session_extensions', 'acb6c8e9-d613-47d7-9b5b-44e75fbd615b'),
    ('gaming_session_extensions', 'c00dcaff-29d9-4f7a-bef5-ced5e3622e5d'),
    ('gaming_session_extensions', 'da80e015-38ad-4e11-a9b2-9dce36f9b8a4'),
    ('gaming_session_extensions', 'fd55d37b-81c6-4756-8018-523dcec25ad5'),
    ('gaming_sessions', '0075b816-db7b-49e8-8932-6a974c27b078'),
    ('gaming_sessions', '00f4307d-cd05-45fc-a32b-6ec6e2a73e74'),
    ('gaming_sessions', '03966756-4bcf-4f9e-8099-1e0304a77c0d'),
    ('gaming_sessions', '07e449b0-7f65-4ca5-bdd8-79e604c0db4c'),
    ('gaming_sessions', '0b1f287d-a2fa-4acf-81b6-98e7e6fd35ed'),
    ('gaming_sessions', '0ec7b4e1-ba77-4d86-ace6-62274cd36779'),
    ('gaming_sessions', '10c9296b-56af-4c42-b7e9-5811a90df77c'),
    ('gaming_sessions', '10f13ea2-e267-4a3c-8300-cb85a7f9ed36'),
    ('gaming_sessions', '143d50ac-7435-4bae-9529-4e8ce9e91a85'),
    ('gaming_sessions', '174ac5c9-28b6-4952-90c0-290ca4b41bb2'),
    ('gaming_sessions', '1b7d6353-1cb1-4ebb-b8ac-344e97c4c281'),
    ('gaming_sessions', '2340841d-262c-4f08-aef2-0d430c095e02'),
    ('gaming_sessions', '24a0566a-2d6f-4804-8cd1-6ae5a346fa1d'),
    ('gaming_sessions', '267606ae-3193-4a63-8b24-5946c4482486'),
    ('gaming_sessions', '2c51140c-45f9-4088-bc2f-f2ca40dc3afb'),
    ('gaming_sessions', '2d88f005-04ee-40e4-8c34-3c07a4e2c603'),
    ('gaming_sessions', '30366d70-b66b-4d00-9aae-aee2fcd019ac'),
    ('gaming_sessions', '30d19d94-211c-46f3-be0b-1151ce7647b5'),
    ('gaming_sessions', '317045fd-57eb-4a6a-8990-12fd9d8c4a18'),
    ('gaming_sessions', '359bc094-8589-4970-8bbb-edfbdd8df4b3'),
    ('gaming_sessions', '37d0dc0e-83e9-43c6-addd-dc50c15bcfaa'),
    ('gaming_sessions', '39863b72-a1bf-46ac-ae7a-a2a4ab8dac66'),
    ('gaming_sessions', '3b240c86-ece6-449e-aea6-e7d95b082df8'),
    ('gaming_sessions', '3db09809-4e5b-4fdc-99a2-db810dcd9268'),
    ('gaming_sessions', '428c7e34-25bb-486b-9c6e-ec2df89eef65'),
    ('gaming_sessions', '439f2784-c869-45c2-a34a-bd61d93123d3'),
    ('gaming_sessions', '476f159f-0eb4-4bbc-b8d7-4929891e6a3d'),
    ('gaming_sessions', '4a7c10c6-e540-454d-b9eb-9431ae502b30'),
    ('gaming_sessions', '4c209c00-cafe-4950-a2c7-f995ddcf2ac0'),
    ('gaming_sessions', '4eee27d2-a4cf-4a9e-bc13-872d11589455'),
    ('gaming_sessions', '4fa969fd-9d93-45e6-936e-204e805e2fb5'),
    ('gaming_sessions', '4fe5eb8e-9079-4510-b47d-247cc35fc025'),
    ('gaming_sessions', '5034d651-e045-4f3d-ae9f-9814140ab283'),
    ('gaming_sessions', '50a4ae47-e045-4967-9b84-79917f65f503'),
    ('gaming_sessions', '54da5f74-adea-4233-bf72-892e5cb3c166'),
    ('gaming_sessions', '5ee319b8-a9d5-4910-9780-fce9502c642a'),
    ('gaming_sessions', '60382b4d-2549-4732-95f4-da4c7b650cd1'),
    ('gaming_sessions', '60432e05-3dff-478f-ab66-37174e237403'),
    ('gaming_sessions', '640787b2-2c71-4c80-8905-d54d1bb1e6b8'),
    ('gaming_sessions', '67df8017-ee6f-47a2-9542-4c3608e09054'),
    ('gaming_sessions', '69f0504f-a1d1-4d59-a059-63bac6c8df97'),
    ('gaming_sessions', '6eb71a87-8334-4b47-8f12-7690878a7cdf'),
    ('gaming_sessions', '7045e4d2-6b98-46db-be9f-8dfabb97ca9f'),
    ('gaming_sessions', '70ae8eb9-5d52-452e-8abe-3db14d0beb40'),
    ('gaming_sessions', '7c36d23c-defb-4740-b671-8feba7c85a39'),
    ('gaming_sessions', '83c1befa-b40b-4dbb-bece-fd296eb7510c'),
    ('gaming_sessions', '8ee1d023-c215-42ec-a6d6-2871d48de162'),
    ('gaming_sessions', '99a7f509-bc0e-407e-b898-492f930111c5'),
    ('gaming_sessions', '9ad2b91f-d753-467d-8140-aae50c14ea9a'),
    ('gaming_sessions', 'a549a74d-b2e0-4a5e-abe1-0a0fcaf683d3'),
    ('gaming_sessions', 'a8bd5e53-9d94-44ee-9693-2c48ab21e2dc'),
    ('gaming_sessions', 'b88e23c3-2fcf-40ac-b14c-8a8ba86072bf'),
    ('gaming_sessions', 'c176d823-a05a-4458-b16e-77e891368c77'),
    ('gaming_sessions', 'ca320a0a-06b5-4b4f-b125-b655499a24b7'),
    ('gaming_sessions', 'cc5392d7-142a-4bb0-986c-b78c9a6f2271'),
    ('gaming_sessions', 'db37d18f-f866-4082-abd9-eb4047b03342'),
    ('gaming_sessions', 'eef7c745-8839-4051-8cc4-78632a33faab'),
    ('gaming_sessions', 'f4b20a17-4419-4a0e-9040-7cd92174a231'),
    ('gaming_sessions', 'f4cc4ebd-1790-4551-8d66-a919a233354a'),
    ('gaming_sessions', 'f7a323c0-701d-44dc-9779-1b3bc64b7fa0'),
    ('gaming_sessions', 'f9f7aebc-8cb1-497f-a598-b6f1314f6ccb'),
    ('order_lines', '05ce6903-f574-455a-b2cc-7e8ef20f5949'),
    ('order_lines', '121e6fae-791f-4607-8497-dce43199f0cd'),
    ('order_lines', '138d5633-a682-4c2f-a885-0684d8f008b4'),
    ('order_lines', '167260b6-fd43-4085-96e4-a03bb2027d31'),
    ('order_lines', '19e781aa-eb5b-474c-8a0b-a34eb969a177'),
    ('order_lines', '1d6c35d8-a88e-4d97-b111-9563f42921c0'),
    ('order_lines', '21cbafdd-c992-4dfd-abf9-a4fe33c36ec9'),
    ('order_lines', '2639f7fb-f3ee-46a4-894a-bbb68c3ea8c0'),
    ('order_lines', '2f751de0-c0dd-4e4e-96de-e44fed4ed8f8'),
    ('order_lines', '30ed46e4-66bf-4bba-980e-6c31a13ba3d0'),
    ('order_lines', '317b61d2-29dd-4e41-878a-3eb435c4e532'),
    ('order_lines', '32c363ef-4b1e-448a-82f9-e42f5f5e9ba2'),
    ('order_lines', '3375c4bd-edc1-4313-a60f-b672222ed614'),
    ('order_lines', '34ea3c28-5ac5-4d49-ac7c-08c473f76121'),
    ('order_lines', '3829af24-fe77-44c1-9cfd-bdee1e2e51c1'),
    ('order_lines', '4233082d-f0ad-4734-a6f0-79cb32996363'),
    ('order_lines', '441f10c3-c9e7-475b-ae2f-59c06b691ad7'),
    ('order_lines', '48a6a79c-6e83-4caf-9f9d-7f576c8ce973'),
    ('order_lines', '4df52c7f-5bee-406d-8a94-7cb381f59338'),
    ('order_lines', '520424e1-5cf4-40a0-9826-5e825f3f5880'),
    ('order_lines', '5609358b-1abc-4434-88d2-70a2736b8999'),
    ('order_lines', '5efb9b38-fac3-4fec-b8fa-b63db51ab691'),
    ('order_lines', '68adea1d-d81b-41bc-bd11-22e6283e69b3'),
    ('order_lines', '7460f98b-05d1-4c60-bc6c-9da5e0df191a'),
    ('order_lines', '7a6dabab-f97e-4cfb-a4da-9c2cd4c596a7'),
    ('order_lines', '8620d689-e028-4ca0-b003-a58de7a5ac54'),
    ('order_lines', '9b217892-51d3-416d-b495-0ae08eee3aff'),
    ('order_lines', 'a29a5299-0bc1-4c56-9497-f88b01ce8b60'),
    ('order_lines', 'a418db6b-8c1a-4738-b572-6b5cb768800f'),
    ('order_lines', 'a861ad3d-2a93-4e4b-a4d8-b5b37562fcb5'),
    ('order_lines', 'addca719-e775-4f83-8fe8-fdaf58d1e67a'),
    ('order_lines', 'b099728b-d0cc-47a5-b991-c606139341c0'),
    ('order_lines', 'b2bb254f-0d47-45b0-9c5b-1450fef53fbf'),
    ('order_lines', 'b554fc67-299d-4f2b-b233-1e9cdf0265cb'),
    ('order_lines', 'b91a3a70-4b6e-418f-9264-3a4f2320ba67'),
    ('order_lines', 'bdda9900-333b-4519-aebb-0c2a6fd7f4a3'),
    ('order_lines', 'caf24d98-428b-4212-a96a-49f3ab289fdd'),
    ('order_lines', 'cc7b704d-997e-4738-b46c-24de14c3093e'),
    ('order_lines', 'cf277983-bf6e-4f32-a215-971fafd75698'),
    ('order_lines', 'd08f3b77-5a44-4562-adfa-d2f2faa11c92'),
    ('order_lines', 'd0f7d644-0e28-4795-bf70-4d95572ebf51'),
    ('order_lines', 'd79529e3-ec8b-427d-94db-74023fa7621f'),
    ('order_lines', 'e58d12b6-38a6-44b8-849e-262457cfddd8'),
    ('order_lines', 'e60412d7-41b5-48ea-8462-7cc2a26935f8'),
    ('order_lines', 'ed3d1273-e099-47d2-96b5-10b003a48185'),
    ('order_lines', 'eee8c914-097b-40d4-8aee-0dc152a8f050'),
    ('order_lines', 'f6b66647-f3ee-485b-97db-59101c42c37b'),
    ('order_lines', 'fee413af-7e7f-4d94-997b-765d8d7f9b61'),
    ('order_lines', 'ffd783dc-6a96-4f5e-a014-f82fb9fd5a1a'),
    ('orders', '08eb21be-3d4b-4bda-ba09-a38ec0c12fb0'),
    ('orders', '09188f7b-065a-478d-8a6d-1b8fd1894390'),
    ('orders', '0f351e3c-7c33-48f2-bd76-b3f881a63b58'),
    ('orders', '138dd2af-8f54-43b9-8805-039120bdfaf5'),
    ('orders', '15653b4a-3616-4488-9269-d3fcb6d139a6'),
    ('orders', '1a64c124-5f7d-4004-9c45-e6f62b6985e6'),
    ('orders', '1d1a39d8-431e-4334-9b56-3590291c2ba8'),
    ('orders', '1f68c067-e179-44a5-aebf-035b818af582'),
    ('orders', '29727e25-7f41-4890-bd48-edbbf326eebc'),
    ('orders', '2edb1fa2-6bdb-4978-b98b-045f0953f259'),
    ('orders', '3229ebae-47db-45cf-a9b5-66974248eb02'),
    ('orders', '38dca08f-d810-4d35-8666-3f9a11777502'),
    ('orders', '3f553e72-0499-4b19-bc52-d7465957e92d'),
    ('orders', '41a3bc3b-5f35-4838-a83e-b526ee7d3bd8'),
    ('orders', '494aa103-6186-4a58-acf3-67954762bdd8'),
    ('orders', '4c4b9e55-d929-4fd9-b1ad-222497b4a96e'),
    ('orders', '4c877d6c-7bdc-4264-a3df-11e82234dbdf'),
    ('orders', '4e5e9fa8-556f-4d5b-9cde-ece9059c825b'),
    ('orders', '4fdcdd5f-6926-462e-a6fa-213267bfda3f'),
    ('orders', '511b3daf-f421-4b2c-bd3d-05aa0b1a6db7'),
    ('orders', '531498ee-2efd-4aae-aadc-fcaf7b21400b'),
    ('orders', '57066319-4487-4b40-afe4-ae55c901c257'),
    ('orders', '57df100f-10fe-4160-b543-b9943ba93acf'),
    ('orders', '589b9815-fcad-4014-b3af-2f565123d69f'),
    ('orders', '5e728a09-2c49-4efc-abb0-35a0c1322fce'),
    ('orders', '5e8e396a-bb8c-49dd-b97d-bfe9e3b76b76'),
    ('orders', '5fc995cf-3715-462f-87d0-f9a5552ee1c9'),
    ('orders', '70ba6ed4-d787-4fb6-a478-dcb6a09ccf63'),
    ('orders', '7af28313-a05e-4c60-afe7-7952ed182049'),
    ('orders', '837e9117-c376-4959-ad71-71263aaba617'),
    ('orders', '856d90da-330a-45f0-81db-b0c03a05953b'),
    ('orders', '8c5856a5-3c5c-4c00-9ca5-479d1666d5c7'),
    ('orders', '917e1052-670a-4b1d-a113-a7405d0cefcd'),
    ('orders', 'a0d339f4-38ae-47a0-b002-78641929bc93'),
    ('orders', 'a3b79ed5-65ab-4f6a-963d-05412e781fee'),
    ('orders', 'a6f44bd3-7d31-47ac-8eaf-45821dd8b6ce'),
    ('orders', 'a8aae04e-251c-47a0-b9ce-051d2c5f97b6'),
    ('orders', 'a8b2ae8c-7e24-454b-8fe4-dbe6224659e6'),
    ('orders', 'af51bfd4-f14d-4b7f-9439-44038d3fa5c2'),
    ('orders', 'b03d9708-b7e8-4562-bb0c-2bbfbd2e1b56'),
    ('orders', 'b529f59b-da7c-4393-ab60-3162a19094cf'),
    ('orders', 'c7346753-e11c-4746-8874-de3a01b00846'),
    ('orders', 'd256ff57-8b2f-412b-84f4-8e6c536d16dd'),
    ('orders', 'd4952acb-597a-4c9d-80cf-fc95f0111dce'),
    ('orders', 'da81ea74-ce64-4fda-a0af-ee380c285ab0'),
    ('orders', 'f1e8cdfa-e151-46c7-972f-7addc636beee'),
    ('orders', 'f7bf206a-caa1-443b-b42f-8da7dfb0a502'),
    ('orders', 'fbf6e4fd-9496-4b75-be63-512af024713a'),
    ('orders', 'fdea649e-1d7c-4d94-9c0d-fc51af8d8786'),
    ('payments', '024dbb4f-5142-44de-aac7-dbe25d5611fa'),
    ('payments', '02a6757c-773c-4e30-8c2f-6e15b3d78309'),
    ('payments', '04e7c7c8-f391-41d7-81e8-73e1d152834e'),
    ('payments', '07935f62-5880-482e-b8a0-10ccf227b2f2'),
    ('payments', '08361e8e-e42b-4f4b-8a96-17c3339ce812'),
    ('payments', '0b9cd6ad-0cf5-4ff8-8cbb-05df6902d7a6'),
    ('payments', '131435c9-be1f-49c9-9203-08e86255a3ab'),
    ('payments', '18870108-257a-4196-8eee-03baeb3ae5ae'),
    ('payments', '25a2aea0-d458-4d5f-9eea-c513286ddf4f'),
    ('payments', '2a11bafa-a262-4634-a0e4-5ff768d49aab'),
    ('payments', '2f91562d-7c64-4b0a-92c6-37bc1520dd45'),
    ('payments', '30376270-8f92-48a0-9f19-4d5f04bbbbfc'),
    ('payments', '41761c47-a63d-419a-8005-1e88a7e41a5d'),
    ('payments', '4291abda-5d9d-40de-8e6e-ce1ceba043c6'),
    ('payments', '4ea006f3-2afd-4e06-b69e-656056197487'),
    ('payments', '52ebfd5b-35b9-4a4e-b997-500c67ced602'),
    ('payments', '52f9e362-308c-4180-b642-d7911d542e01'),
    ('payments', '53423bc4-1f5d-4114-affc-5ad6cbc2808f'),
    ('payments', '54ae6da0-4eb0-4601-a96a-1e2b3d8e726e'),
    ('payments', '58b91bc4-6c91-446b-9f09-f9c7e1c088d9'),
    ('payments', '5fac2384-4d7e-42a5-b334-0bf05e48590e'),
    ('payments', '6c67fb02-a612-4ea1-96b2-4450b1a7722a'),
    ('payments', '7e9510bf-c1f3-4f55-9327-3e43aded7704'),
    ('payments', '82ceb522-9388-40a5-a391-b6cd2b78073f'),
    ('payments', '8b9b8a7d-8f28-4419-946b-35d192dc22c4'),
    ('payments', '8cc5e929-6af2-406a-aba1-db4f021c0e59'),
    ('payments', '9512c56b-6b10-48f3-81c5-773e668b0a0f'),
    ('payments', '9b7df54f-4ad2-4cd0-b182-df4685ad76d0'),
    ('payments', '9dfcd953-1c5b-4b88-b348-1fe38ac57a79'),
    ('payments', 'affe239e-e4ce-4bc1-bf3a-4f71c170ab90'),
    ('payments', 'b057537c-81c1-4062-9687-ef4be61968e7'),
    ('payments', 'b3ea226c-715e-4a48-8a10-1ab077aed067'),
    ('payments', 'b52c37f3-01a3-4e5e-bf21-33ca08104298'),
    ('payments', 'bec66180-3c3c-4be8-ba30-28accc15471a'),
    ('payments', 'c66b76be-2534-4d77-9007-531a0cf42ad2'),
    ('payments', 'c999c161-b00f-4422-9ff5-0d39d77ffca9'),
    ('payments', 'ccbae79a-6455-4bc1-ac8d-0e8170990e6c'),
    ('payments', 'ce1a5820-87e5-4cc8-91b9-e6a7fdc933d4'),
    ('payments', 'd4278221-2660-47e8-aa70-58b4bb0bfbec'),
    ('payments', 'd8a174aa-6ff8-4230-9e91-8287ade2fd4c'),
    ('payments', 'd95084c7-2527-442b-baa4-98409e275f7e'),
    ('payments', 'dcf57789-bcfb-494e-a4a1-3c931996e1bd'),
    ('payments', 'e8817190-bc73-4288-b764-68d6adc8f302'),
    ('payments', 'ea30f056-2840-42de-a088-9d6349f83ce4'),
    ('payments', 'ea50fd4e-c888-4b4d-847c-5909f3618b84'),
    ('payments', 'fbca505c-f2b8-4443-ab6a-276e82826033'),
    ('payments', 'fcf32700-acbb-4be8-9fff-5b060025cda2'),
    ('shifts', '0ca7ce2a-4bee-41c2-95b7-7243afccf11d'),
    ('shifts', '4014702e-7a3e-4a76-a6bb-833461686706'),
    ('shifts', '5310511b-8f15-42bd-89d0-947377edc85e'),
    ('shifts', '6515ff88-fd55-4a7c-8060-4bbfa4ca534f'),
    ('shifts', 'bc7aa569-b8c3-4b72-b1f1-30370d69b6f1');

CREATE TEMP TABLE c3_expected_target (table_name text PRIMARY KEY, row_count bigint NOT NULL, row_sha256 text NOT NULL) ON COMMIT DROP;
INSERT INTO c3_expected_target VALUES
    ('gaming_session_extensions', 12, '64db447aa9658479f8caf545b5afe2d4d10783a3350c002d341932a9bd5689a6'),
    ('gaming_sessions', 61, 'aaf62d66e40556dcca8a2a0f55d5b26b3dbb8a63813d4c94d5630055cda5aa2e'),
    ('order_lines', 49, 'ba8545e88af71f3fde0c26377724d6e2532aa59ebb52eb30c7cd0855ee5e9ec2'),
    ('orders', 49, '6aac359a52a303b0c71f6721fa33b7e08d1cb9afd2b46a9a10beb52f3a014e25'),
    ('payments', 47, 'ab636f53707c25072ab6598f0db857a184087c3588900f7683c084d16949193e'),
    ('shifts', 5, '1d4c8f58789ff72bcc6d73ae2baa13b2f64f02ff7e3060b1d16eaadfa602e275');

CREATE OR REPLACE FUNCTION pg_temp.c3_target_digest(target_table text)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text, '
        'E''\n'' ORDER BY t.id), ''''), ''UTF8'')), ''hex'') FROM %I t '
        'WHERE t.id IN (SELECT id FROM c3_target WHERE table_name = %L)',
        target_table, target_table
    );
END
$$;

DO $$
DECLARE mismatch text;
BEGIN
    SELECT string_agg(e.table_name, ', ' ORDER BY e.table_name) INTO mismatch
      FROM c3_expected_target e
      CROSS JOIN LATERAL pg_temp.c3_target_digest(e.table_name) d
     WHERE d.row_count <> e.row_count OR d.row_sha256 <> e.row_sha256;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'pinned trial rows changed or are missing: %', mismatch;
    END IF;
END
$$;

-- The discovery rule must still produce exactly the pinned cohort, so any
-- later trial row (or any genuine row) makes the cleanup refuse.
DO $$
BEGIN
    IF EXISTS (
        (SELECT id FROM shifts WHERE opened_at >= '2026-09-20T00:00:00Z'
         EXCEPT SELECT id FROM c3_target WHERE table_name = 'shifts')
        UNION ALL
        (SELECT id FROM c3_target WHERE table_name = 'shifts'
         EXCEPT SELECT id FROM shifts WHERE opened_at >= '2026-09-20T00:00:00Z')
    ) OR EXISTS (
        SELECT 1 FROM orders o
         WHERE coalesce((o.shift_id IN (SELECT id FROM c3_target WHERE table_name = 'shifts')), false)
               <> coalesce((o.id IN (SELECT id FROM c3_target WHERE table_name = 'orders')), false)
    ) OR EXISTS (
        SELECT 1 FROM payments p
         WHERE coalesce((p.shift_id IN (SELECT id FROM c3_target WHERE table_name = 'shifts')
                OR p.order_id IN (SELECT id FROM c3_target WHERE table_name = 'orders')), false)
               <> coalesce((p.id IN (SELECT id FROM c3_target WHERE table_name = 'payments')), false)
    ) OR EXISTS (
        SELECT 1 FROM gaming_sessions g
         WHERE coalesce((g.shift_id IN (SELECT id FROM c3_target WHERE table_name = 'shifts')
                OR g.order_id IN (SELECT id FROM c3_target WHERE table_name = 'orders')), false)
               <> coalesce((g.id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_sessions')), false)
    ) OR EXISTS (
        SELECT 1 FROM order_lines l
         WHERE coalesce((l.order_id IN (SELECT id FROM c3_target WHERE table_name = 'orders')), false)
               <> coalesce((l.id IN (SELECT id FROM c3_target WHERE table_name = 'order_lines')), false)
    ) OR EXISTS (
        SELECT 1 FROM gaming_session_extensions x
         WHERE coalesce((x.gaming_session_id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_sessions')), false)
               <> coalesce((x.id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_session_extensions')), false)
    ) THEN
        RAISE EXCEPTION 'trial cohort no longer matches the pinned allowlist';
    END IF;
END
$$;

-- Human-readable business invariants complement the opaque row hashes.
DO $$
BEGIN
    IF (SELECT count(*) FROM shifts WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'shifts')
          AND status = 'closed' AND closed_at IS NOT NULL AND opening_action_id IS NOT NULL
          AND opening_request_hash ~ '^[0-9a-f]{64}$') <> 5 THEN
        RAISE EXCEPTION 'target shifts are no longer five closed keyed shifts';
    END IF;
    IF (SELECT count(*) FROM orders WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'orders')
          AND status = 'paid' AND invoice_no IS NOT NULL AND customer_id IS NULL) <> 47
       OR (SELECT count(*) FROM orders WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'orders')
          AND status = 'void' AND invoice_no IS NULL AND customer_id IS NULL) <> 2
       OR (SELECT coalesce(sum(total_minor), 0) FROM orders
            WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'orders') AND status = 'paid') <> 750000
       OR (SELECT coalesce(sum(amount_minor), 0) FROM payments
            WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'payments')) <> 750000
    THEN
        RAISE EXCEPTION 'target orders or payments no longer reconcile to 47 paid / 2 void / 750000';
    END IF;
    IF (SELECT count(*) FROM gaming_sessions WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_sessions')
          AND customer_id IS NULL
          AND ((status = 'ended' AND order_id IS NOT NULL) OR (status = 'cancelled' AND order_id IS NULL))) <> 61
    THEN
        RAISE EXCEPTION 'target Gaming sessions are no longer 49 billed ended / 12 cancelled';
    END IF;
    IF EXISTS (SELECT 1 FROM refunds WHERE order_id IN (SELECT id FROM c3_target WHERE table_name = 'orders')) THEN
        RAISE EXCEPTION 'a refund now depends on a trial order';
    END IF;
END
$$;

-- Business quiescence across the whole database.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM shifts WHERE status <> 'closed')
       OR EXISTS (SELECT 1 FROM orders WHERE status NOT IN ('paid', 'void', 'refunded'))
       OR EXISTS (SELECT 1 FROM gaming_sessions WHERE status NOT IN ('ended', 'cancelled'))
       OR EXISTS (SELECT 1 FROM google_sheets_deliveries WHERE status <> 'delivered')
    THEN
        RAISE EXCEPTION 'business activity is not quiescent (open shift/order/session or undelivered Sheets event)';
    END IF;
END
$$;

-- Retained evidence that must be exactly as reviewed.
DO $$
BEGIN
    IF (SELECT count(*) FROM in_invoice_counters) <> 1
       OR (SELECT count(*) FROM in_invoice_counters WHERE id = '9f78c3b5-c5f4-425d-9def-4b34298a2931'::uuid AND last_seq = 70) <> 1
    THEN
        RAISE EXCEPTION 'invoice counter is not the reviewed single counter at 70';
    END IF;
    IF (SELECT count(*) FROM google_sheets_deliveries
         WHERE event_type = 'pos.order.paid' AND source_type = 'pos_order'
           AND source_id IN (SELECT id::text FROM c3_target WHERE table_name = 'orders')
           AND status = 'delivered') <> 47
    THEN
        RAISE EXCEPTION 'the 47 delivered Sheets events for trial orders changed';
    END IF;
    IF (SELECT count(*) FROM audit_log
         WHERE action = 'production_trial_cleanup' AND entity_id = 'code30.1-20260920') <> 1
    THEN
        RAISE EXCEPTION 'the Code30.1 cleanup receipt is missing or duplicated';
    END IF;
    IF EXISTS (SELECT 1 FROM audit_log
                WHERE action = 'verified_trial_cleanup' OR entity_type = 'TrialCleanupReceipt') THEN
        RAISE EXCEPTION 'a versioned trial-cleanup receipt already exists; refusing to run twice';
    END IF;
    IF (SELECT count(*) FROM users WHERE id = '7016c42c-11c9-48fa-a30a-5d66a8c970b7'::uuid AND status = 'active' AND company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid) <> 1
       OR (SELECT count(*) FROM terminals t JOIN branches b ON b.id = t.branch_id
            WHERE t.id = '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid AND t.is_active AND b.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid) <> 1
    THEN
        RAISE EXCEPTION 'cleanup receipt actor or terminal is no longer active in the expected company';
    END IF;
END
$$;

-- Every foreign key into a target table may reference target rows only from
-- another target table. Unknown or new dependencies fail closed.
DO $$
DECLARE r record; n bigint; bad text := '';
BEGIN
    FOR r IN
        SELECT c.conrelid::regclass::text AS child, a.attname AS col, c.confrelid::regclass::text AS parent
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
         WHERE c.contype = 'f' AND array_length(c.conkey, 1) = 1
           AND c.confrelid::regclass::text IN (SELECT DISTINCT table_name FROM c3_target)
    LOOP
        IF r.child IN (SELECT DISTINCT table_name FROM c3_target) THEN
            EXECUTE format(
                'SELECT count(*) FROM %I x WHERE x.%I IN (SELECT id FROM c3_target WHERE table_name = %L)'
                ' AND x.id NOT IN (SELECT id FROM c3_target WHERE table_name = %L)',
                r.child, r.col, r.parent, r.child
            ) INTO n;
        ELSE
            EXECUTE format(
                'SELECT count(*) FROM %I x WHERE x.%I IN (SELECT id FROM c3_target WHERE table_name = %L)',
                r.child, r.col, r.parent
            ) INTO n;
        END IF;
        IF n > 0 THEN bad := bad || format('%s.%s->%s:%s ', r.child, r.col, r.parent, n); END IF;
    END LOOP;
    IF bad <> '' THEN
        RAISE EXCEPTION 'unexpected dependency on a trial row: %', bad;
    END IF;
END
$$;

-- Whole-database fingerprint. The operator's expected value comes from a dry
-- run on a fresh backup restored to a disposable database.
CREATE TEMP TABLE c3_all_pre ON COMMIT DROP AS
SELECT t.tablename AS table_name, d.row_count, d.row_sha256
  FROM pg_tables t
  CROSS JOIN LATERAL pg_temp.c3_table_digest(format('%I.%I', t.schemaname, t.tablename)::regclass) d
 WHERE t.schemaname = 'public';

CREATE TEMP TABLE c3_state ON COMMIT DROP AS
SELECT encode(sha256(convert_to(string_agg(
           table_name || ':' || row_count || ':' || row_sha256, E'\n' ORDER BY table_name), 'UTF8')), 'hex')
           AS state_fingerprint,
       (SELECT version_num FROM alembic_version) AS schema_revision,
       (SELECT max(id) FROM audit_log) AS audit_max_id
  FROM c3_all_pre;

DO $$
DECLARE i c3_inputs; s c3_state;
BEGIN
    SELECT * INTO i FROM c3_inputs;
    SELECT * INTO s FROM c3_state;
    IF i.apply AND s.state_fingerprint <> i.expected_state_fingerprint THEN
        RAISE EXCEPTION 'live state fingerprint % does not match the rehearsed fingerprint', s.state_fingerprint;
    END IF;
END
$$;

-- Retained rows of every mutated table must not move.
CREATE OR REPLACE FUNCTION pg_temp.c3_retained_digest(target_table text)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text, '
        'E''\n'' ORDER BY t.id), ''''), ''UTF8'')), ''hex'') FROM %I t '
        'WHERE t.id NOT IN (SELECT id FROM c3_target WHERE table_name = %L)',
        target_table, target_table
    );
END
$$;
CREATE TEMP TABLE c3_retained_pre ON COMMIT DROP AS
SELECT e.table_name, d.row_count, d.row_sha256
  FROM c3_expected_target e CROSS JOIN LATERAL pg_temp.c3_retained_digest(e.table_name) d;

-- Snapshot every trigger and trigger function on the mutated tables.
CREATE TEMP TABLE c3_triggers_pre ON COMMIT DROP AS
SELECT tg.tgrelid::regclass::text AS table_name, tg.tgname, tg.tgenabled,
       pg_get_triggerdef(tg.oid, true) AS definition,
       encode(sha256(convert_to(pg_get_functiondef(tg.tgfoid), 'UTF8')), 'hex') AS function_sha256
  FROM pg_trigger tg
 WHERE NOT tg.tgisinternal
   AND tg.tgrelid::regclass::text IN (SELECT table_name FROM c3_expected_target);

DO $$
BEGIN
    IF (SELECT count(*) FROM c3_triggers_pre
         WHERE tgenabled = 'O' AND (table_name, tgname) IN (
             ('payments', 'trg_payments_immutable'),
             ('orders', 'trg_orders_paid_source_integrity'),
             ('order_lines', 'trg_order_lines_paid_source_integrity'),
             ('gaming_session_extensions', 'trg_gaming_session_extensions_immutable'))) <> 4
       OR EXISTS (SELECT 1 FROM c3_triggers_pre WHERE tgenabled <> 'O')
    THEN
        RAISE EXCEPTION 'integrity triggers are not in their reviewed enabled state';
    END IF;
END
$$;

-- The owner-approved exception: exactly these four guards, only inside this
-- transaction. PostgreSQL makes ALTER TABLE transactional, so any failure or
-- ROLLBACK restores them.
ALTER TABLE gaming_session_extensions DISABLE TRIGGER trg_gaming_session_extensions_immutable;
ALTER TABLE payments DISABLE TRIGGER trg_payments_immutable;
ALTER TABLE orders DISABLE TRIGGER trg_orders_paid_source_integrity;
ALTER TABLE order_lines DISABLE TRIGGER trg_order_lines_paid_source_integrity;

CREATE TEMP TABLE c3_deleted (table_name text PRIMARY KEY, row_count bigint NOT NULL) ON COMMIT DROP;
WITH d AS (DELETE FROM gaming_session_extensions WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_session_extensions') RETURNING 1)
INSERT INTO c3_deleted SELECT 'gaming_session_extensions', count(*) FROM d;
WITH d AS (DELETE FROM gaming_sessions WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'gaming_sessions') RETURNING 1)
INSERT INTO c3_deleted SELECT 'gaming_sessions', count(*) FROM d;
WITH d AS (DELETE FROM payments WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'payments') RETURNING 1)
INSERT INTO c3_deleted SELECT 'payments', count(*) FROM d;
WITH d AS (DELETE FROM order_lines WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'order_lines') RETURNING 1)
INSERT INTO c3_deleted SELECT 'order_lines', count(*) FROM d;
WITH d AS (DELETE FROM orders WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'orders') RETURNING 1)
INSERT INTO c3_deleted SELECT 'orders', count(*) FROM d;
WITH d AS (DELETE FROM shifts WHERE id IN (SELECT id FROM c3_target WHERE table_name = 'shifts') RETURNING 1)
INSERT INTO c3_deleted SELECT 'shifts', count(*) FROM d;

-- Fire any deferred constraint triggers now, inside the checked region.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE gaming_session_extensions ENABLE TRIGGER trg_gaming_session_extensions_immutable;
ALTER TABLE payments ENABLE TRIGGER trg_payments_immutable;
ALTER TABLE orders ENABLE TRIGGER trg_orders_paid_source_integrity;
ALTER TABLE order_lines ENABLE TRIGGER trg_order_lines_paid_source_integrity;

DO $$
DECLARE mismatch text;
BEGIN
    SELECT string_agg(coalesce(e.table_name, d.table_name), ', ') INTO mismatch
      FROM c3_expected_target e FULL JOIN c3_deleted d USING (table_name)
     WHERE e.row_count IS DISTINCT FROM d.row_count;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'a deletion affected an unexpected row count: %', mismatch;
    END IF;
    IF EXISTS (
        SELECT tgrelid::regclass::text, tgname, tgenabled, pg_get_triggerdef(oid, true),
               encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
          FROM pg_trigger
         WHERE NOT tgisinternal
           AND tgrelid::regclass::text IN (SELECT table_name FROM c3_expected_target)
        EXCEPT
        SELECT table_name, tgname, tgenabled, definition, function_sha256 FROM c3_triggers_pre
    ) OR EXISTS (
        SELECT table_name, tgname, tgenabled, definition, function_sha256 FROM c3_triggers_pre
        EXCEPT
        SELECT tgrelid::regclass::text, tgname, tgenabled, pg_get_triggerdef(oid, true),
               encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
          FROM pg_trigger
         WHERE NOT tgisinternal
           AND tgrelid::regclass::text IN (SELECT table_name FROM c3_expected_target)
    ) THEN
        RAISE EXCEPTION 'integrity triggers were not restored byte-identically';
    END IF;
END
$$;

-- Versioned replay receipt. A dry run skips this entirely, so it neither
-- writes an audit row nor consumes audit_log_id_seq.
CREATE TEMP TABLE c3_receipt (id bigint) ON COMMIT DROP;
DO $$
DECLARE i c3_inputs; s c3_state; receipt_id bigint;
BEGIN
    SELECT * INTO i FROM c3_inputs;
    SELECT * INTO s FROM c3_state;
    IF NOT i.apply THEN
        RETURN;
    END IF;
    INSERT INTO audit_log (
        actor_user_id, company_id, action, entity_type, entity_id, before, after,
        ip, user_agent, terminal_id, request_id, client_platform, client_version_code,
        client_action_id, client_reported_at, client_was_offline, synced_at, reason
    ) VALUES (
        '7016c42c-11c9-48fa-a30a-5d66a8c970b7'::uuid,
        '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid,
        'verified_trial_cleanup',
        'TrialCleanupReceipt',
        'code30.3-trial-cleanup-20260923',
        jsonb_build_object(
            'schema_revision', s.schema_revision,
            'state_fingerprint', s.state_fingerprint,
            'backup_sha256', i.backup_sha256
        ),
        jsonb_build_object(
            'receipt_version', 2,
            'cleanup_id', 'code30.3-trial-cleanup-20260923',
            'source_git_sha', i.source_git_sha,
            'executor', i.executor_name,
            'executed_at', to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"+00:00"'),
            'deleted_counts', (SELECT jsonb_object_agg(table_name, row_count) FROM c3_deleted),
            'deleted_shift_ids', '["0ca7ce2a-4bee-41c2-95b7-7243afccf11d","4014702e-7a3e-4a76-a6bb-833461686706","5310511b-8f15-42bd-89d0-947377edc85e","6515ff88-fd55-4a7c-8060-4bbfa4ca534f","bc7aa569-b8c3-4b72-b1f1-30370d69b6f1"]'::jsonb,
            'replay_fence', '[{"action_key":"shift-open:01ebce52-90a0-495c-a937-9074bf1a87c6","action_type":"shift_open","request_hash":"da806282ca9bcbd9ec74672c1a73fda124d9d6d4dd0ebe2bec46e3d5fa4ccae0","source_entity_id":"0ca7ce2a-4bee-41c2-95b7-7243afccf11d","terminal_id":"789353a8-09e4-4ef2-9fa8-ac73c426bfc8","user_id":"c2aed53f-401f-4e09-8237-11c366e612ef"},{"action_key":"shift-open:33f645f5-9ed1-4481-96b4-e486f9050aca","action_type":"shift_open","request_hash":"9b052bd4665602b6b815e65900bbef9237553e9b1f1af79a281d27a25ae60cd9","source_entity_id":"4014702e-7a3e-4a76-a6bb-833461686706","terminal_id":"789353a8-09e4-4ef2-9fa8-ac73c426bfc8","user_id":"7016c42c-11c9-48fa-a30a-5d66a8c970b7"},{"action_key":"shift-open:7f3b8ab0-bdee-4fb0-876d-f17404534575","action_type":"shift_open","request_hash":"a54912c64c96638879a4ca3de11e4f1db92773a7f82ca4c43c438144c63fdc00","source_entity_id":"bc7aa569-b8c3-4b72-b1f1-30370d69b6f1","terminal_id":"789353a8-09e4-4ef2-9fa8-ac73c426bfc8","user_id":"c2aed53f-401f-4e09-8237-11c366e612ef"},{"action_key":"shift-open:882526c1-7428-4357-92da-e95bdd262a55","action_type":"shift_open","request_hash":"ceb585cabf405955cf9e5977b54609446c702337524af1e892b96761f631e226","source_entity_id":"5310511b-8f15-42bd-89d0-947377edc85e","terminal_id":"789353a8-09e4-4ef2-9fa8-ac73c426bfc8","user_id":"c2aed53f-401f-4e09-8237-11c366e612ef"},{"action_key":"shift-open:91d33f5d-5751-4100-bca9-3a541dc811a9","action_type":"shift_open","request_hash":"6578ab038e0943574684a0517e8e672d45a48696f2e0418081f2ae72c331a862","source_entity_id":"6515ff88-fd55-4a7c-8060-4bbfa4ca534f","terminal_id":"789353a8-09e4-4ef2-9fa8-ac73c426bfc8","user_id":"c2aed53f-401f-4e09-8237-11c366e612ef"}]'::jsonb,
            'evidence', '{"cohort_rule":"shifts opened at or after 2026-09-20T00:00:00Z and their dependent rows","deleted_gaming_session_extension_ids":["2e821dd7-9586-4903-9f06-20467ae61aae","2fb24396-81a0-4525-b124-e0f89be675c9","58662d08-7bc8-49e8-81fb-8f1d5f8c3243","5bee040e-98f5-4692-95b0-3f32e0feb975","5dad85a1-5e01-42b7-ab0a-a99ebdcef043","9c2445d4-5f30-4de9-96a1-863d7bdf2ee5","a7d7932b-3c1d-4291-863f-87bfa0ea005d","ac7cf529-a163-4412-81ae-45266a2aa137","acb6c8e9-d613-47d7-9b5b-44e75fbd615b","c00dcaff-29d9-4f7a-bef5-ced5e3622e5d","da80e015-38ad-4e11-a9b2-9dce36f9b8a4","fd55d37b-81c6-4756-8018-523dcec25ad5"],"deleted_gaming_session_ids":["0075b816-db7b-49e8-8932-6a974c27b078","00f4307d-cd05-45fc-a32b-6ec6e2a73e74","03966756-4bcf-4f9e-8099-1e0304a77c0d","07e449b0-7f65-4ca5-bdd8-79e604c0db4c","0b1f287d-a2fa-4acf-81b6-98e7e6fd35ed","0ec7b4e1-ba77-4d86-ace6-62274cd36779","10c9296b-56af-4c42-b7e9-5811a90df77c","10f13ea2-e267-4a3c-8300-cb85a7f9ed36","143d50ac-7435-4bae-9529-4e8ce9e91a85","174ac5c9-28b6-4952-90c0-290ca4b41bb2","1b7d6353-1cb1-4ebb-b8ac-344e97c4c281","2340841d-262c-4f08-aef2-0d430c095e02","24a0566a-2d6f-4804-8cd1-6ae5a346fa1d","267606ae-3193-4a63-8b24-5946c4482486","2c51140c-45f9-4088-bc2f-f2ca40dc3afb","2d88f005-04ee-40e4-8c34-3c07a4e2c603","30366d70-b66b-4d00-9aae-aee2fcd019ac","30d19d94-211c-46f3-be0b-1151ce7647b5","317045fd-57eb-4a6a-8990-12fd9d8c4a18","359bc094-8589-4970-8bbb-edfbdd8df4b3","37d0dc0e-83e9-43c6-addd-dc50c15bcfaa","39863b72-a1bf-46ac-ae7a-a2a4ab8dac66","3b240c86-ece6-449e-aea6-e7d95b082df8","3db09809-4e5b-4fdc-99a2-db810dcd9268","428c7e34-25bb-486b-9c6e-ec2df89eef65","439f2784-c869-45c2-a34a-bd61d93123d3","476f159f-0eb4-4bbc-b8d7-4929891e6a3d","4a7c10c6-e540-454d-b9eb-9431ae502b30","4c209c00-cafe-4950-a2c7-f995ddcf2ac0","4eee27d2-a4cf-4a9e-bc13-872d11589455","4fa969fd-9d93-45e6-936e-204e805e2fb5","4fe5eb8e-9079-4510-b47d-247cc35fc025","5034d651-e045-4f3d-ae9f-9814140ab283","50a4ae47-e045-4967-9b84-79917f65f503","54da5f74-adea-4233-bf72-892e5cb3c166","5ee319b8-a9d5-4910-9780-fce9502c642a","60382b4d-2549-4732-95f4-da4c7b650cd1","60432e05-3dff-478f-ab66-37174e237403","640787b2-2c71-4c80-8905-d54d1bb1e6b8","67df8017-ee6f-47a2-9542-4c3608e09054","69f0504f-a1d1-4d59-a059-63bac6c8df97","6eb71a87-8334-4b47-8f12-7690878a7cdf","7045e4d2-6b98-46db-be9f-8dfabb97ca9f","70ae8eb9-5d52-452e-8abe-3db14d0beb40","7c36d23c-defb-4740-b671-8feba7c85a39","83c1befa-b40b-4dbb-bece-fd296eb7510c","8ee1d023-c215-42ec-a6d6-2871d48de162","99a7f509-bc0e-407e-b898-492f930111c5","9ad2b91f-d753-467d-8140-aae50c14ea9a","a549a74d-b2e0-4a5e-abe1-0a0fcaf683d3","a8bd5e53-9d94-44ee-9693-2c48ab21e2dc","b88e23c3-2fcf-40ac-b14c-8a8ba86072bf","c176d823-a05a-4458-b16e-77e891368c77","ca320a0a-06b5-4b4f-b125-b655499a24b7","cc5392d7-142a-4bb0-986c-b78c9a6f2271","db37d18f-f866-4082-abd9-eb4047b03342","eef7c745-8839-4051-8cc4-78632a33faab","f4b20a17-4419-4a0e-9040-7cd92174a231","f4cc4ebd-1790-4551-8d66-a919a233354a","f7a323c0-701d-44dc-9779-1b3bc64b7fa0","f9f7aebc-8cb1-497f-a598-b6f1314f6ccb"],"deleted_order_ids":["08eb21be-3d4b-4bda-ba09-a38ec0c12fb0","09188f7b-065a-478d-8a6d-1b8fd1894390","0f351e3c-7c33-48f2-bd76-b3f881a63b58","138dd2af-8f54-43b9-8805-039120bdfaf5","15653b4a-3616-4488-9269-d3fcb6d139a6","1a64c124-5f7d-4004-9c45-e6f62b6985e6","1d1a39d8-431e-4334-9b56-3590291c2ba8","1f68c067-e179-44a5-aebf-035b818af582","29727e25-7f41-4890-bd48-edbbf326eebc","2edb1fa2-6bdb-4978-b98b-045f0953f259","3229ebae-47db-45cf-a9b5-66974248eb02","38dca08f-d810-4d35-8666-3f9a11777502","3f553e72-0499-4b19-bc52-d7465957e92d","41a3bc3b-5f35-4838-a83e-b526ee7d3bd8","494aa103-6186-4a58-acf3-67954762bdd8","4c4b9e55-d929-4fd9-b1ad-222497b4a96e","4c877d6c-7bdc-4264-a3df-11e82234dbdf","4e5e9fa8-556f-4d5b-9cde-ece9059c825b","4fdcdd5f-6926-462e-a6fa-213267bfda3f","511b3daf-f421-4b2c-bd3d-05aa0b1a6db7","531498ee-2efd-4aae-aadc-fcaf7b21400b","57066319-4487-4b40-afe4-ae55c901c257","57df100f-10fe-4160-b543-b9943ba93acf","589b9815-fcad-4014-b3af-2f565123d69f","5e728a09-2c49-4efc-abb0-35a0c1322fce","5e8e396a-bb8c-49dd-b97d-bfe9e3b76b76","5fc995cf-3715-462f-87d0-f9a5552ee1c9","70ba6ed4-d787-4fb6-a478-dcb6a09ccf63","7af28313-a05e-4c60-afe7-7952ed182049","837e9117-c376-4959-ad71-71263aaba617","856d90da-330a-45f0-81db-b0c03a05953b","8c5856a5-3c5c-4c00-9ca5-479d1666d5c7","917e1052-670a-4b1d-a113-a7405d0cefcd","a0d339f4-38ae-47a0-b002-78641929bc93","a3b79ed5-65ab-4f6a-963d-05412e781fee","a6f44bd3-7d31-47ac-8eaf-45821dd8b6ce","a8aae04e-251c-47a0-b9ce-051d2c5f97b6","a8b2ae8c-7e24-454b-8fe4-dbe6224659e6","af51bfd4-f14d-4b7f-9439-44038d3fa5c2","b03d9708-b7e8-4562-bb0c-2bbfbd2e1b56","b529f59b-da7c-4393-ab60-3162a19094cf","c7346753-e11c-4746-8874-de3a01b00846","d256ff57-8b2f-412b-84f4-8e6c536d16dd","d4952acb-597a-4c9d-80cf-fc95f0111dce","da81ea74-ce64-4fda-a0af-ee380c285ab0","f1e8cdfa-e151-46c7-972f-7addc636beee","f7bf206a-caa1-443b-b42f-8da7dfb0a502","fbf6e4fd-9496-4b75-be63-512af024713a","fdea649e-1d7c-4d94-9c0d-fc51af8d8786"],"deleted_order_line_ids":["05ce6903-f574-455a-b2cc-7e8ef20f5949","121e6fae-791f-4607-8497-dce43199f0cd","138d5633-a682-4c2f-a885-0684d8f008b4","167260b6-fd43-4085-96e4-a03bb2027d31","19e781aa-eb5b-474c-8a0b-a34eb969a177","1d6c35d8-a88e-4d97-b111-9563f42921c0","21cbafdd-c992-4dfd-abf9-a4fe33c36ec9","2639f7fb-f3ee-46a4-894a-bbb68c3ea8c0","2f751de0-c0dd-4e4e-96de-e44fed4ed8f8","30ed46e4-66bf-4bba-980e-6c31a13ba3d0","317b61d2-29dd-4e41-878a-3eb435c4e532","32c363ef-4b1e-448a-82f9-e42f5f5e9ba2","3375c4bd-edc1-4313-a60f-b672222ed614","34ea3c28-5ac5-4d49-ac7c-08c473f76121","3829af24-fe77-44c1-9cfd-bdee1e2e51c1","4233082d-f0ad-4734-a6f0-79cb32996363","441f10c3-c9e7-475b-ae2f-59c06b691ad7","48a6a79c-6e83-4caf-9f9d-7f576c8ce973","4df52c7f-5bee-406d-8a94-7cb381f59338","520424e1-5cf4-40a0-9826-5e825f3f5880","5609358b-1abc-4434-88d2-70a2736b8999","5efb9b38-fac3-4fec-b8fa-b63db51ab691","68adea1d-d81b-41bc-bd11-22e6283e69b3","7460f98b-05d1-4c60-bc6c-9da5e0df191a","7a6dabab-f97e-4cfb-a4da-9c2cd4c596a7","8620d689-e028-4ca0-b003-a58de7a5ac54","9b217892-51d3-416d-b495-0ae08eee3aff","a29a5299-0bc1-4c56-9497-f88b01ce8b60","a418db6b-8c1a-4738-b572-6b5cb768800f","a861ad3d-2a93-4e4b-a4d8-b5b37562fcb5","addca719-e775-4f83-8fe8-fdaf58d1e67a","b099728b-d0cc-47a5-b991-c606139341c0","b2bb254f-0d47-45b0-9c5b-1450fef53fbf","b554fc67-299d-4f2b-b233-1e9cdf0265cb","b91a3a70-4b6e-418f-9264-3a4f2320ba67","bdda9900-333b-4519-aebb-0c2a6fd7f4a3","caf24d98-428b-4212-a96a-49f3ab289fdd","cc7b704d-997e-4738-b46c-24de14c3093e","cf277983-bf6e-4f32-a215-971fafd75698","d08f3b77-5a44-4562-adfa-d2f2faa11c92","d0f7d644-0e28-4795-bf70-4d95572ebf51","d79529e3-ec8b-427d-94db-74023fa7621f","e58d12b6-38a6-44b8-849e-262457cfddd8","e60412d7-41b5-48ea-8462-7cc2a26935f8","ed3d1273-e099-47d2-96b5-10b003a48185","eee8c914-097b-40d4-8aee-0dc152a8f050","f6b66647-f3ee-485b-97db-59101c42c37b","fee413af-7e7f-4d94-997b-765d8d7f9b61","ffd783dc-6a96-4f5e-a014-f82fb9fd5a1a"],"deleted_payment_ids":["024dbb4f-5142-44de-aac7-dbe25d5611fa","02a6757c-773c-4e30-8c2f-6e15b3d78309","04e7c7c8-f391-41d7-81e8-73e1d152834e","07935f62-5880-482e-b8a0-10ccf227b2f2","08361e8e-e42b-4f4b-8a96-17c3339ce812","0b9cd6ad-0cf5-4ff8-8cbb-05df6902d7a6","131435c9-be1f-49c9-9203-08e86255a3ab","18870108-257a-4196-8eee-03baeb3ae5ae","25a2aea0-d458-4d5f-9eea-c513286ddf4f","2a11bafa-a262-4634-a0e4-5ff768d49aab","2f91562d-7c64-4b0a-92c6-37bc1520dd45","30376270-8f92-48a0-9f19-4d5f04bbbbfc","41761c47-a63d-419a-8005-1e88a7e41a5d","4291abda-5d9d-40de-8e6e-ce1ceba043c6","4ea006f3-2afd-4e06-b69e-656056197487","52ebfd5b-35b9-4a4e-b997-500c67ced602","52f9e362-308c-4180-b642-d7911d542e01","53423bc4-1f5d-4114-affc-5ad6cbc2808f","54ae6da0-4eb0-4601-a96a-1e2b3d8e726e","58b91bc4-6c91-446b-9f09-f9c7e1c088d9","5fac2384-4d7e-42a5-b334-0bf05e48590e","6c67fb02-a612-4ea1-96b2-4450b1a7722a","7e9510bf-c1f3-4f55-9327-3e43aded7704","82ceb522-9388-40a5-a391-b6cd2b78073f","8b9b8a7d-8f28-4419-946b-35d192dc22c4","8cc5e929-6af2-406a-aba1-db4f021c0e59","9512c56b-6b10-48f3-81c5-773e668b0a0f","9b7df54f-4ad2-4cd0-b182-df4685ad76d0","9dfcd953-1c5b-4b88-b348-1fe38ac57a79","affe239e-e4ce-4bc1-bf3a-4f71c170ab90","b057537c-81c1-4062-9687-ef4be61968e7","b3ea226c-715e-4a48-8a10-1ab077aed067","b52c37f3-01a3-4e5e-bf21-33ca08104298","bec66180-3c3c-4be8-ba30-28accc15471a","c66b76be-2534-4d77-9007-531a0cf42ad2","c999c161-b00f-4422-9ff5-0d39d77ffca9","ccbae79a-6455-4bc1-ac8d-0e8170990e6c","ce1a5820-87e5-4cc8-91b9-e6a7fdc933d4","d4278221-2660-47e8-aa70-58b4bb0bfbec","d8a174aa-6ff8-4230-9e91-8287ade2fd4c","d95084c7-2527-442b-baa4-98409e275f7e","dcf57789-bcfb-494e-a4a1-3c931996e1bd","e8817190-bc73-4288-b764-68d6adc8f302","ea30f056-2840-42de-a088-9d6349f83ce4","ea50fd4e-c888-4b4d-847c-5909f3618b84","fbca505c-f2b8-4443-ab6a-276e82826033","fcf32700-acbb-4be8-9fff-5b060025cda2"],"google_sheets_event_ids_owner_deletes":["012862d2-28e2-5ae3-a61e-95227f3976f3","1387fe86-9ae7-50e3-845f-da49b0e0bdf0","1f8f82da-603e-5c5b-b6c5-45b4e452a76b","2385acba-82a3-580d-a25f-4f18eee8811e","2e29d499-688e-565b-9acc-8c3607ff4d14","36309c2d-6965-5527-a172-9cdbebf444b5","36ee897e-b51a-5b43-9a96-db12975dc0a3","3e0f14e9-8c7a-53ad-af19-33befde9d3e6","405a92db-6e10-511a-9c84-b3cd8af84eed","41305d3a-2ace-5fa3-918f-84d8f937fd8e","454ea590-cd74-5649-8296-69640a85105f","4ea99eb4-2e90-5273-92c7-6ecfae4f257e","52bf799e-bf7b-5601-989b-bb98b01ae1d9","56de2684-09a9-51e2-bf81-fc18be8a2db4","5b69e278-93ea-5815-85dd-ab520169f46d","5b932ba6-91ef-5078-a486-8e304d58ced1","610c651f-2f90-549e-a3f4-057f09a70211","65bef410-c979-58cf-bce5-45921bc7cfde","661abc60-daf4-521d-9020-77f8ad0943ac","68dba4df-79fc-5f66-b3d4-e36383821697","6efb9036-94f5-598c-b70b-7514c9e07d3b","78bef715-47dc-57fd-a0da-fdf5029f96f6","92bb465c-82c9-5902-9ca9-d7bd31321ba8","959ce5d7-c5c0-58ce-a816-baf8516d6481","96df0abc-8bd2-5a5a-91d9-4709c15eaae4","9c45a6eb-612e-515d-abde-e4f337203eee","9cc19ae6-6dc4-5bf0-82fc-030ddc2f3cc7","a28f473a-1a2a-5b0e-acbc-812b8930f639","a790d29b-9b11-5703-9d6b-83ef411a4abc","a8157204-91e9-5ba5-8fd3-4c728d76074e","ac7ee15c-2dba-5138-a3e0-cc22d37e3e85","ae058139-295a-5e50-a8d4-9c75f9390d2c","b227429e-9bcd-562a-97cd-fdf935b19202","c2f641e3-b2a2-56a6-bff3-35b8354577d4","c91f6220-4c02-57d8-a223-03fa0d4efcfd","cdced68f-f1c9-583f-8b96-343e48e021d3","d208f9d0-d1c8-5f14-9d9e-45fd7189b90b","d97d2ac7-e426-5ed8-aecf-f40c4cacd645","dc59762b-d54f-530e-9190-c2e70e747710","e4dc0793-aae7-5fe9-9cc5-2f7de743dcf9","e684a00b-d5d5-51e4-945f-b73409560b16","ed3ab5e7-87d7-56e2-b2d5-568464192e53","f12ab39c-9b3f-5308-894e-60c6874338be","f8bca99c-d3e4-541d-b8fe-911d2b1ba94d","f905f6cf-29f1-5c4b-ad02-bc7fc193c9cd","fb8332e6-7478-51d2-a216-66b9533b94af","fdc80ce8-0084-5863-b50d-dd39992784f3"],"guarded_trigger_exception":["gaming_session_extensions.trg_gaming_session_extensions_immutable","order_lines.trg_order_lines_paid_source_integrity","orders.trg_orders_paid_source_integrity","payments.trg_payments_immutable"],"invoice_counter":{"id":"9f78c3b5-c5f4-425d-9def-4b34298a2931","last_seq":70,"rewound":false},"paid_total_minor":750000,"retained":["audit_log","idempotency_keys","google_sheets_deliveries","in_invoice_counters"],"retired_invoice_numbers":["D/MN/26-27/00024","D/MN/26-27/00025","D/MN/26-27/00026","D/MN/26-27/00027","D/MN/26-27/00028","D/MN/26-27/00029","D/MN/26-27/00030","D/MN/26-27/00031","D/MN/26-27/00032","D/MN/26-27/00033","D/MN/26-27/00034","D/MN/26-27/00035","D/MN/26-27/00036","D/MN/26-27/00037","D/MN/26-27/00038","D/MN/26-27/00039","D/MN/26-27/00040","D/MN/26-27/00041","D/MN/26-27/00042","D/MN/26-27/00043","D/MN/26-27/00044","D/MN/26-27/00045","D/MN/26-27/00046","D/MN/26-27/00047","D/MN/26-27/00048","D/MN/26-27/00049","D/MN/26-27/00050","D/MN/26-27/00051","D/MN/26-27/00052","D/MN/26-27/00053","D/MN/26-27/00054","D/MN/26-27/00055","D/MN/26-27/00056","D/MN/26-27/00057","D/MN/26-27/00058","D/MN/26-27/00059","D/MN/26-27/00060","D/MN/26-27/00061","D/MN/26-27/00062","D/MN/26-27/00063","D/MN/26-27/00064","D/MN/26-27/00065","D/MN/26-27/00066","D/MN/26-27/00067","D/MN/26-27/00068","D/MN/26-27/00069","D/MN/26-27/00070"]}'::jsonb || jsonb_build_object('pre_cleanup_audit_max_id', s.audit_max_id)
        ),
        NULL, 'cleanup-code30-3-trial-data/1', '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid, 'code30.3-trial-cleanup-20260923',
        NULL, NULL, NULL, NULL, NULL, NULL,
        'Owner-approved one-time removal of Code30.3 trial data'
    ) RETURNING id INTO receipt_id;
    INSERT INTO c3_receipt VALUES (receipt_id);
END
$$;

-- Post-state proof.
DO $$
DECLARE mismatch text; i c3_inputs; s c3_state; new_audit bigint; expected_new_audit bigint;
BEGIN
    SELECT * INTO i FROM c3_inputs;
    SELECT * INTO s FROM c3_state;
    IF EXISTS (SELECT 1 FROM c3_target t WHERE
            (t.table_name = 'shifts' AND EXISTS (SELECT 1 FROM shifts x WHERE x.id = t.id))
         OR (t.table_name = 'orders' AND EXISTS (SELECT 1 FROM orders x WHERE x.id = t.id))
         OR (t.table_name = 'order_lines' AND EXISTS (SELECT 1 FROM order_lines x WHERE x.id = t.id))
         OR (t.table_name = 'payments' AND EXISTS (SELECT 1 FROM payments x WHERE x.id = t.id))
         OR (t.table_name = 'gaming_sessions' AND EXISTS (SELECT 1 FROM gaming_sessions x WHERE x.id = t.id))
         OR (t.table_name = 'gaming_session_extensions' AND EXISTS (SELECT 1 FROM gaming_session_extensions x WHERE x.id = t.id)))
    THEN
        RAISE EXCEPTION 'a pinned trial row survived cleanup';
    END IF;

    SELECT string_agg(p.table_name, ', ') INTO mismatch
      FROM c3_retained_pre p CROSS JOIN LATERAL pg_temp.c3_retained_digest(p.table_name) d
     WHERE d.row_count <> p.row_count OR d.row_sha256 <> p.row_sha256;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'a retained row changed in a cleaned table: %', mismatch;
    END IF;

    SELECT string_agg(p.table_name, ', ') INTO mismatch
      FROM c3_all_pre p
      CROSS JOIN LATERAL pg_temp.c3_table_digest(format('public.%I', p.table_name)::regclass) d
     WHERE p.table_name NOT IN (SELECT table_name FROM c3_expected_target)
       AND p.table_name <> 'audit_log'
       AND (d.row_count <> p.row_count OR d.row_sha256 <> p.row_sha256);
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'an unrelated table changed during cleanup: %', mismatch;
    END IF;

    IF (SELECT count(*) FROM audit_log WHERE id <= s.audit_max_id)
         <> (SELECT row_count FROM c3_all_pre WHERE table_name = 'audit_log')
       OR (SELECT encode(sha256(convert_to(coalesce(string_agg(to_jsonb(a)::text, E'\n' ORDER BY to_jsonb(a)::text), ''), 'UTF8')), 'hex')
             FROM audit_log a WHERE a.id <= s.audit_max_id)
         <> (SELECT row_sha256 FROM c3_all_pre WHERE table_name = 'audit_log')
    THEN
        RAISE EXCEPTION 'retained audit history changed during cleanup';
    END IF;
    SELECT count(*) INTO new_audit FROM audit_log WHERE id > s.audit_max_id;
    expected_new_audit := CASE WHEN i.apply THEN 1 ELSE 0 END;
    IF new_audit <> expected_new_audit
       OR (i.apply AND NOT EXISTS (
            SELECT 1 FROM audit_log a JOIN c3_receipt r ON r.id = a.id
             WHERE a.id > 28202 AND a.action = 'verified_trial_cleanup'
               AND a.entity_type = 'TrialCleanupReceipt' AND a.entity_id = 'code30.3-trial-cleanup-20260923'))
    THEN
        RAISE EXCEPTION 'cleanup receipt is absent, duplicated or unexpected';
    END IF;
    IF (SELECT last_seq FROM in_invoice_counters WHERE id = '9f78c3b5-c5f4-425d-9def-4b34298a2931'::uuid) <> 70 THEN
        RAISE EXCEPTION 'invoice counter moved';
    END IF;
END
$$;

\set QUIET off
SELECT jsonb_pretty(jsonb_build_object(
    'mode', CASE WHEN (SELECT apply FROM c3_inputs) THEN 'apply' ELSE 'dry-run (rolled back)' END,
    'cleanup_id', 'code30.3-trial-cleanup-20260923',
    'schema_revision', (SELECT schema_revision FROM c3_state),
    'state_fingerprint', (SELECT state_fingerprint FROM c3_state),
    'pre_cleanup_audit_max_id', (SELECT audit_max_id FROM c3_state),
    'deleted_counts', (SELECT jsonb_object_agg(table_name, row_count) FROM c3_deleted),
    'receipt_audit_id', (SELECT id FROM c3_receipt)
)) AS cleanup_result;
\set QUIET on

\if :cleanup_apply
COMMIT;
\else
ROLLBACK;
\endif
