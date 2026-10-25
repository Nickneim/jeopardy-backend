-- The round is derived from the column (1-6 Jeopardy, 7-12 Double Jeopardy,
-- 13 Final Jeopardy) and the clue value from the row, so values outside
-- those ranges can't be played or shown.
ALTER TABLE category
    ADD CONSTRAINT check_category_column_number
        CHECK (column_number BETWEEN 1 AND 13);

ALTER TABLE clue
    ADD CONSTRAINT check_clue_row_number
        CHECK (row_number >= 1);
