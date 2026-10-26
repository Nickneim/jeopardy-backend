-- Sessions only play rows 1 to 10 (BoardClue.MAX_ROW), so a clue further
-- down would show a value over REST in a game that can't be played.
-- GameIntegrationTest checks that the two limits match.
ALTER TABLE clue
    DROP CONSTRAINT check_clue_row_number,
    ADD CONSTRAINT check_clue_row_number
        CHECK (row_number BETWEEN 1 AND 10);

-- A blank media URL is no media at all, so a media clue needs a URL with
-- something in it (written so a NULL URL fails instead of passing as unknown).
ALTER TABLE clue
    DROP CONSTRAINT check_media_url,
    ADD CONSTRAINT check_media_url
        CHECK (
            (type = 'TEXT' AND media_url IS NULL)
            OR
            (type <> 'TEXT' AND media_url IS NOT NULL AND media_url ~ '[^[:space:]]')
        );

-- A daily double's clue must be on its own game's board. A foreign key can't
-- say that (the clue's game is two tables away), so triggers check it
-- whenever a daily double is written and whenever a clue or category that a
-- daily double depends on moves to another category or game.
CREATE FUNCTION check_daily_double_game() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM clue
        JOIN category ON category.id = clue.category_id
        WHERE clue.id = NEW.clue_id
          AND category.game_id = NEW.game_id
    ) THEN
        RAISE EXCEPTION 'clue % is not in game %', NEW.clue_id, NEW.game_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_daily_double_game
    BEFORE INSERT OR UPDATE ON game_daily_double
    FOR EACH ROW EXECUTE FUNCTION check_daily_double_game();

CREATE FUNCTION check_moved_daily_double_clue() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_daily_double
        JOIN category ON category.id = NEW.category_id
        WHERE game_daily_double.clue_id = NEW.id
          AND game_daily_double.game_id <> category.game_id
    ) THEN
        RAISE EXCEPTION 'clue % is a daily double of another game', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_moved_daily_double_clue
    AFTER UPDATE OF category_id ON clue
    FOR EACH ROW EXECUTE FUNCTION check_moved_daily_double_clue();

CREATE FUNCTION check_moved_daily_double_category() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_daily_double
        JOIN clue ON clue.id = game_daily_double.clue_id
        WHERE clue.category_id = NEW.id
          AND game_daily_double.game_id <> NEW.game_id
    ) THEN
        RAISE EXCEPTION 'category % has a daily double of another game', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_moved_daily_double_category
    AFTER UPDATE OF game_id ON category
    FOR EACH ROW EXECUTE FUNCTION check_moved_daily_double_category();

-- The season filter compares lower(season), so it needs an index on that
-- expression; a plain index on season wouldn't be used.
CREATE INDEX idx_game_lower_season ON game (lower(season));
