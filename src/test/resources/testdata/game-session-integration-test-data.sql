INSERT INTO game (title, game_date, season)
VALUES (
    'Game Session Integration Test Game',
    '2025-03-01',
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
    NULL,
    id
FROM game
WHERE title = 'Game Session Integration Test Game';

INSERT INTO category (
    name,
    column_number,
    comments,
    game_id
)
SELECT
    'LITERATURE',
    7,
    NULL,
    id
FROM game
WHERE title = 'Game Session Integration Test Game';

INSERT INTO category (
    name,
    column_number,
    comments,
    game_id
)
SELECT
    'FINAL HISTORY',
    13,
    NULL,
    id
FROM game
WHERE title = 'Game Session Integration Test Game';

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
    'George Washington',
    1,
    'TEXT',
    NULL,
    category.id
FROM category
JOIN game ON game.id = category.game_id
WHERE game.title = 'Game Session Integration Test Game'
  AND category.column_number = 1;

INSERT INTO clue (
    question,
    answer,
    row_number,
    type,
    media_url,
    category_id
)
SELECT
    'Who was the second president of the United States?',
    'John Adams',
    2,
    'TEXT',
    NULL,
    category.id
FROM category
JOIN game ON game.id = category.game_id
WHERE game.title = 'Game Session Integration Test Game'
  AND category.column_number = 1;

INSERT INTO clue (
    question,
    answer,
    row_number,
    type,
    media_url,
    category_id
)
SELECT
    'He wrote "Hamlet".',
    'William Shakespeare',
    1,
    'TEXT',
    NULL,
    category.id
FROM category
JOIN game ON game.id = category.game_id
WHERE game.title = 'Game Session Integration Test Game'
  AND category.column_number = 7;

INSERT INTO clue (
    question,
    answer,
    row_number,
    type,
    media_url,
    category_id
)
SELECT
    'This war began in 1775.',
    'The American Revolution',
    1,
    'TEXT',
    NULL,
    category.id
FROM category
JOIN game ON game.id = category.game_id
WHERE game.title = 'Game Session Integration Test Game'
  AND category.column_number = 13;

-- The second HISTORY clue is the Daily Double.
INSERT INTO game_daily_double (game_id, clue_id)
SELECT category.game_id, clue.id
FROM clue
JOIN category ON category.id = clue.category_id
JOIN game ON game.id = category.game_id
WHERE game.title = 'Game Session Integration Test Game'
  AND category.column_number = 1
  AND clue.row_number = 2;
