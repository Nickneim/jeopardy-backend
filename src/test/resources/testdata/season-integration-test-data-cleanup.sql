DELETE FROM game
WHERE title LIKE 'Season Integration Test Game%';

ALTER TABLE game ALTER COLUMN id RESTART WITH 1;
