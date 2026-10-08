-- Numbered seasons whose lexical order differs from their numeric order,
-- plus special seasons that aren't numbers.
INSERT INTO game (title, game_date, season)
VALUES
    ('Season Integration Test Game 01', '1993-05-01', '9'),
    ('Season Integration Test Game 02', '1993-06-04', '9'),
    ('Season Integration Test Game 03', '1994-06-03', '10'),
    ('Season Integration Test Game 04', '2024-07-26', '40'),
    ('Season Integration Test Game 05', '2024-07-25', '40'),
    ('Season Integration Test Game 06', '2024-07-24', '40'),
    ('Season Integration Test Game 07', '2026-05-15', 'pcj'),
    ('Season Integration Test Game 08', '2023-05-08', 'jm'),
    ('Season Integration Test Game 09', '2026-09-04', 'jm');
