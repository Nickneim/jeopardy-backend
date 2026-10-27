-- Games inserted out of every order the tests sort by, with ties on date
-- (c and D) and on clue count (b and D; A and e), titles in mixed case, and
-- numbered seasons whose lexical order differs from their numeric order.
-- Ids follow insertion: b = 1, A = 2, c = 3, D = 4, e = 5.
INSERT INTO game (title, game_date, season)
VALUES
    ('Game Sort Test b', '2024-01-03', '10'),
    ('Game Sort Test A', '2024-01-01', '9'),
    ('Game Sort Test c', '2024-01-02', 'jm'),
    ('Game Sort Test D', '2024-01-02', '40'),
    ('Game Sort Test e', '2024-01-04', 'pcj');

INSERT INTO category (name, column_number, game_id)
SELECT 'CATEGORY', 1, id
FROM game
WHERE title IN ('Game Sort Test b', 'Game Sort Test c', 'Game Sort Test D');

-- b and D get two clues, c one.
INSERT INTO clue (question, answer, row_number, category_id)
SELECT 'Question', 'Answer', row_number, category.id
FROM category
JOIN game ON game.id = category.game_id
CROSS JOIN (VALUES (1), (2)) AS rows (row_number)
WHERE game.title IN ('Game Sort Test b', 'Game Sort Test D')
   OR (game.title = 'Game Sort Test c' AND row_number = 1);
