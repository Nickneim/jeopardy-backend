INSERT INTO game (title, game_date, season)
VALUES (
           'Clue Integration Test Game',
           '2025-01-01',
           '40'
       );

INSERT INTO category (
    name,
    column_number,
    comments,
    game_id
)
SELECT
    'HISTORY',
    1,
    'History integration test category',
    id
FROM game
WHERE title = 'Clue Integration Test Game';

INSERT INTO clue (
    question,
    answer,
    row_number,
    type,
    media_url,
    category_id
)
SELECT
    'Who was the first president of the United States?',
    'Who is George Washington?',
    3,
    'TEXT',
    NULL,
    id
FROM category
WHERE name = 'HISTORY'
  AND game_id = (
    SELECT id
    FROM game
    WHERE title = 'Clue Integration Test Game'
);