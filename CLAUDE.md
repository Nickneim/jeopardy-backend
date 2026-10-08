# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Spring Boot 4 / Java 21 backend for a multiplayer Jeopardy game. Game content (games, categories, clues, daily doubles) lives in PostgreSQL. Live game sessions are held in memory and played over STOMP WebSockets.

## Commands

Use the Gradle wrapper (`./gradlew` in bash, `.\gradlew.bat` in PowerShell).

```bash
./gradlew build                     # compile + run all tests
./gradlew bootRun                   # run the app
./gradlew test                      # all tests
./gradlew test --tests "com.nickneim.jeopardy.session.model.GameSessionTest"
./gradlew test --tests "*GameSessionTest.chooseClue*"   # single method / pattern
```

There is no linter or formatter configured.

### Environment / database

- The app reads `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` (`application.yaml`). Locally they come from the git-ignored `.env`, which the IntelliJ run config `.run/JeopardyApplication.run.xml` loads. `bootRun` does **not** load `.env` on its own.
- Tests annotated `@ActiveProfiles("test")` use `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD` (`src/test/resources/application-test.yml`). They need a real, reachable Postgres. There is no H2 or Testcontainers.
- `JeopardyApplicationTests` has no test profile, so it needs the main `DATABASE_*` variables.
- Hibernate runs with `ddl-auto: validate`. All schema changes go through Flyway migrations in `src/main/resources/db/migration/` (`V<n>__description.sql`), and the JPA entities must match them exactly. `clue.type` is a Postgres enum, mapped with `@JdbcType(PostgreSQLEnumJdbcType.class)`.

## Architecture

There are two top-level feature packages under `com.nickneim.jeopardy`. Each has its own `controller` / `dto` / `service` / `exception` subpackages.

### `game`: persistent content (REST, read-only)

- `Game` → `Category` → `Clue` JPA entities. `GameDailyDouble` is a join entity with a composite key (`GameDailyDoubleId`).
- **The round is derived from `Category.columnNumber`, not stored:** columns 1–6 are Jeopardy, 7–12 are Double Jeopardy, and 13 is Final Jeopardy (`GameRound.fromColumn`). Clue value is also computed (`Clue.getValue()`): `200*row` in J! ($200–$1000), `400*row` in DJ! ($400–$2000), and a flat 2000 in Final.
- Endpoints: `GET /api/games` (paged, optional `season` filter), `GET /api/games/{id}`, and `GET /api/clues/{id}`. DTOs are records with static `from(entity)` factories. `GameResponse` splits categories into `jeopardyRound`, `doubleJeopardyRound` and `finalJeopardy`.
- REST errors are mapped to `ApiErrorResponse` in `exception/ApiExceptionHandler`.

### `session`: live gameplay (in memory, WebSocket)

- `POST /api/game-sessions` with `{gameId}` creates a session and returns its UUID. The rest of the game runs over STOMP.
- **Sessions play from a `GameBoard`, never from JPA entities.** `session.board` holds immutable records (`GameBoard` → `BoardRound` → `BoardCategory` → `BoardClue`); each `BoardClue` stores its own row (1-based, gaps allowed, unique per category), value and Daily Double flag. Game progress is not on the board: `GameSession` keeps the set of played clue ids for the whole game, and the available clues are derived (`board.getClueIds(round)` minus played). `GameBoardService.loadBoard(gameId)` copies a database `Game` into a board inside a read-only transaction (the only session code that touches entities). Custom games can build a `GameBoard` directly. Rounds are played in `GameRound` order and rounds without clues are skipped.
- STOMP setup (`config/WebSocketConfig`): the endpoint is `/ws`, client sends go to `/app/...`, and the simple broker serves `/topic` and `/queue`.
  - Client → server: `/app/sessions/{id}/join | start | choose-clue | buzz | answer | place-daily-double-wager` (`GameSessionWebSocketController`).
  - Server → clients: after every state change, the full `GameSessionResponse` is broadcast to `/topic/sessions/{id}` (`GameSessionBroadcaster`). It includes `board`: the current round as a rectangular grid of categories × rows, where each `ClueSlotResponse` has a clue id, value and `ClueSlotStatus` (`AVAILABLE`, `PLAYED`, `EMPTY`). Everyone receives it, so it must never contain questions, answers or Daily Double flags (`GameSessionResponseTest` checks the serialized JSON). The clue in play is sent separately as `currentClue` (`CurrentClueResponse`, null when no clue is in play): a Daily Double hides its question until the wager is placed, and the answer is only included in `REVEALING_ANSWER`. Turn and buzzer info is sent as `playerChoosingClue`, `playerAnswering` and `playersLockedOut`, and a placed Daily Double wager as `currentClue.wager`. These come from `GameSession` getters that only return a value while it is meaningful, because the underlying fields (`playerWhoBuzzed`, `playersAlreadyBuzzed`, a player's `wager`) are not cleared between clues. Don't expose those fields directly.
  - Errors: `InvalidGameActionException` and `GameSessionNotFoundException` go only to the sender, at `/user/queue/errors`, as `GameSessionErrorResponse(code, message)` with a `GameErrorCode`.
- **`GameSession` (model) is a plain, non-Spring state machine** that holds all the game rules. Its states are in `GameSessionState`: `WAITING_FOR_START → CHOOSING_CLUE → [WAITING_FOR_DAILY_DOUBLE_WAGER] → REVEALING_CLUE → WAITING_FOR_BUZZER ⇄ WAITING_FOR_ANSWER → REVEALING_ANSWER → CHOOSING_CLUE …`. Once every clue in a round is used, it moves to the next round and finally to `FINISHED_GAME`. Illegal actions throw `InvalidGameActionException`. On a daily double, the buzzer is skipped and the chooser answers using their wager; the maximum wager is the higher of their score and the highest clue value in the current round (`GameBoard.getHighestValue`).
- **`GameSessionService` is the orchestration layer.** It stores sessions in a `ConcurrentHashMap`. Every mutation follows the same pattern: `synchronized (session)` → call the model method → `scheduleTimeout(session)` → `broadcast`. Keep this pattern when adding actions. Concurrency control lives here, not in the model.
- **Timeouts:** `scheduleTimeout` switches on the new state and arms a single per-session timer (`GameSessionTimer`, a `ScheduledExecutorService`). Durations come from `GameSessionTimeouts.defaults()`. Timeout callbacks re-check the expected state before acting, so a stale timer is a no-op. A new state with a timed phase needs a case added there.
- Player IDs are generated server-side (`System.nanoTime()`), and the session is not persisted.

## Testing conventions

- **Unit tests:** `GameSessionTest` exercises the state machine directly, and the service and timer tests use Mockito. The Mockito Java agent is attached in `build.gradle`.
- **Controller slice tests:** `@WebMvcTest` with `@MockitoBean`.
- **Entity IDs in tests:** entities have no ID setters, so tests set them with `ReflectionTestUtils.setField(entity, "id", ...)`.
- **REST integration tests:** `@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureRestTestClient` and `@ActiveProfiles("test")`. Before/after each test they load and clean up SQL fixtures from `src/test/resources/testdata/` with `@Sql`.
- **Session tests** build `GameBoard`s from records (no entities or `ReflectionTestUtils`). `GameSessionEndToEndIntegrationTest` is the one that loads a real game from the database.
- **WebSocket integration test:** builds `GameBoard`s in memory, creates sessions directly through `GameSessionService`, and drives them with a `WebSocketStompClient` that uses `JacksonJsonMessageConverter`.
