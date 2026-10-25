ALTER TABLE processors
    ADD COLUMN circuit_open_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE processors
    ADD COLUMN half_open_probe_in_flight BOOLEAN NOT NULL DEFAULT FALSE;
