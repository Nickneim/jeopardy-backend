INSERT INTO game (title, game_date, season)
VALUES (
    'Game Integration Test Game',
    '2025-02-01',
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
WHERE title = 'Game Integration Test Game';

INSERT INTO category (
    name,
    column_number,
    comments,
    game_id
)
SELECT
    'SCIENCE',
    2,
    'Science integration test category',
    id
FROM game
WHERE title = 'Game Integration Test Game';

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
    1,
    'TEXT',
    NULL,
    id
FROM category
WHERE name = 'HISTORY'
  AND game_id = (
      SELECT id
      FROM game
      WHERE title = 'Game Integration Test Game'
  );

INSERT INTO clue (
    question,
    answer,
    row_number,
    type,
    media_url,
    category_id
)
SELECT
    'What is the chemical symbol for gold?',
    'What is Au?',
    2,
    'TEXT',
    NULL,
    id
FROM category
WHERE name = 'SCIENCE'
  AND game_id = (
      SELECT id
      FROM game
      WHERE title = 'Game Integration Test Game'
  );


-- Additional games for pagination tests

INSERT INTO game (title, game_date, season)
VALUES
    ('Game Integration Test Game 02', '2025-02-02', '40'),
    ('Game Integration Test Game 03', '2025-02-03', '40'),
    ('Game Integration Test Game 04', '2025-02-04', '40'),
    ('Game Integration Test Game 05', '2025-02-05', '40'),
    ('Game Integration Test Game 06', '2025-02-06', '40'),
    ('Game Integration Test Game 07', '2025-02-07', '40'),
    ('Game Integration Test Game 08', '2025-02-08', '40'),
    ('Game Integration Test Game 09', '2025-02-09', '40'),
    ('Game Integration Test Game 10', '2025-02-10', '40'),
    ('Game Integration Test Game 11', '2025-02-11', '40'),
    ('Game Integration Test Game 12', '2025-02-11', '40'),
    ('Game Integration Test Game 13', '2025-02-12', '40'),
    ('Game Integration Test Game 14', '2025-02-13', '40'),
    ('Game Integration Test Game 15', '2025-02-14', '40'),
    ('Game Integration Test Game 16', '2025-02-15', '40'),
    ('Game Integration Test Game 17', '2025-02-16', '40'),
    ('Game Integration Test Game 18', '2025-02-17', '40'),
    ('Game Integration Test Game 19', '2025-02-18', '40'),
    ('Game Integration Test Game 20', '2025-02-19', '40'),
    ('Game Integration Test Game 21', '2025-02-20', '40'),
    ('Game Integration Test Game 22', '2025-02-21', '40'),
    ('Game Integration Test Game 23', '2025-02-22', '40'),
    ('Game Integration Test Game 24', '2025-02-23', '40'),
    ('Game Integration Test Game 25', '2025-02-24', '40');
