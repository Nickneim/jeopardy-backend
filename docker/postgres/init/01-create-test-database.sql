-- Runs once, when the container starts with an empty volume. The main
-- database (jeopardy) comes from POSTGRES_DB; the tests use their own, so
-- their fixtures and cleanup never touch the real games. Flyway creates both
-- schemas when the app or the tests first connect.
CREATE DATABASE jeopardy_test;
