-- Sessions only play rows 1 to 10 (BoardClue.MAX_ROW), so a clue further
-- down would show a value over REST in a game that can't be played.
ALTER TABLE clue
    DROP CONSTRAINT check_clue_row_number,
    ADD CONSTRAINT check_clue_row_number
        CHECK (row_number BETWEEN 1 AND 10);

-- A daily double's clue must be on its own game's board. A foreign key can't
-- say that (the clue's game is two tables away), so a trigger checks it.
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
