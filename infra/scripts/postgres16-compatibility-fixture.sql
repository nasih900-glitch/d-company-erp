-- Disposable compatibility database only: no production records or migrations.
BEGIN;
CREATE EXTENSION pgcrypto;
CREATE EXTENSION btree_gist;
CREATE TABLE compatibility_probe(id integer PRIMARY KEY, note text NOT NULL);
INSERT INTO compatibility_probe VALUES (1, 'original');
CREATE TYPE compatibility_payload AS (id integer, note text);
CREATE TABLE compatibility_typed(id integer PRIMARY KEY, payload compatibility_payload NOT NULL);
CREATE FUNCTION compatibility_typed_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> (NEW.payload).id OR (NEW.payload).note IS NULL THEN
        RAISE EXCEPTION 'Invalid disposable composite fixture';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER compatibility_typed_guard BEFORE INSERT OR UPDATE ON compatibility_typed
FOR EACH ROW EXECUTE FUNCTION compatibility_typed_guard();
INSERT INTO compatibility_typed VALUES (1, ROW(1, 'typed-original'));
CREATE TABLE compatibility_exclusion(
    station integer NOT NULL,
    period tstzrange NOT NULL,
    EXCLUDE USING gist(station WITH =, period WITH &&)
);
INSERT INTO compatibility_exclusion VALUES
(1, tstzrange('2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z', '[)'));
COMMIT;
