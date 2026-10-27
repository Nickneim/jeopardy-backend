-- The season's number, so GET /api/games can sort numbered seasons
-- numerically ("9" before "10"). Special seasons ("jm", "pcj") have none.
-- Seasons.number in the backend reads seasons the same way.
ALTER TABLE game
    ADD COLUMN season_number NUMERIC
        GENERATED ALWAYS AS (
            CASE WHEN season ~ '^[0-9]+$' THEN season::NUMERIC END
        ) STORED;
