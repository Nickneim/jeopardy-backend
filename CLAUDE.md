# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Spring Boot 4 / Java 21 backend for a multiplayer Jeopardy game. Game content (games, categories, clues, daily doubles) lives in PostgreSQL. Live game sessions are held in memory and played over STOMP WebSockets.

The React frontend lives in a separate repository. **`docs/api.md` is the contract between them** (REST and STOMP endpoints, payloads, `GameSessionResponse` fields by state, rules, timings, error codes, TypeScript types, known limitations). Any change to an endpoint, payload, field, state, timing or error code must update it in the same commit.

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
- **Browser origins (CORS):** the frontend runs on another origin (Vite dev server, `http://localhost:5173`), so browsers check it on every REST call and the `/ws` handshake. Allowed origins come from `app.cors.allowed-origins` (`CorsProperties`), overridable with the comma-separated `ALLOWED_ORIGINS` environment variable. `WebCorsConfig` applies them to `/api/**` and `WebSocketConfig` to `/ws`. Test clients send no `Origin` header, so only `WebCorsConfigTest` and the WebSocket origin tests exercise this.
- Hibernate runs with `ddl-auto: validate`. All schema changes go through Flyway migrations in `src/main/resources/db/migration/` (`V<n>__description.sql`), and the JPA entities must match them exactly. `clue.type` is a Postgres enum, mapped with `@JdbcType(PostgreSQLEnumJdbcType.class)`.

## Architecture

There are two top-level feature packages under `com.nickneim.jeopardy`. Each has its own `controller` / `dto` / `service` / `exception` subpackages.

### `game`: persistent content (REST, read-only)

- `Game` → `Category` → `Clue` JPA entities. `GameDailyDouble` is a join entity with a composite key (`GameDailyDoubleId`).
- **The round is derived from `Category.columnNumber`, not stored:** columns 1–6 are Jeopardy, 7–12 are Double Jeopardy, and 13 is Final Jeopardy (`GameRound.fromColumn`). Clue value is also computed (`Clue.getValue()`): `200*row` in J! ($200–$1000), `400*row` in DJ! ($400–$2000), and a flat 2000 in Final.
- Endpoints: `GET /api/games` (paged, optional `season`, `title`, `from` and `to` filters, combined in `GameFilter` and turned into a JPA `Specification` by `GameSpecifications`), `GET /api/games/{id}`, `GET /api/games/seasons` and `GET /api/clues/{id}`. Seasons are strings and not all numeric (specials like `"jm"`): `GameService` sorts numbered seasons numerically, highest first, then the specials by their latest game. DTOs are records with static `from(entity)` factories. `GameResponse` splits categories into `jeopardyRound`, `doubleJeopardyRound` and `finalJeopardy`.
- REST errors are mapped to `ApiErrorResponse` in `exception/ApiExceptionHandler`.

### `session`: live gameplay (in memory, WebSocket)

- `POST /api/game-sessions` with `{gameId}` creates a session and returns its UUID. `GET /api/game-sessions/{id}` returns the current `GameSessionResponse` (for clients catching up). `POST /api/game-sessions/{id}/players` with `{playerName}` joins it and returns `{playerId, token}` to the joining player only. The rest of the game runs over STOMP.
- **Player identity comes from the connection, never from a message.** Each player has a secret token (`PlayerTokens`, 256-bit random). The client sends it as the `player-token` header on STOMP CONNECT; `PlayerAuthenticationInterceptor` looks it up in `PlayerTokenRegistry` and attaches a `PlayerPrincipal(sessionId, playerId)` to the connection. No token means an anonymous connection (it can subscribe and watch, but actions get `UNAUTHENTICATED`); an unknown token rejects the connection. `GameSessionWebSocketController.playerId(...)` resolves the acting player and rejects a token from another session (`PLAYER_NOT_IN_SESSION`). Reconnecting with the same token restores the identity. **Tokens must never be broadcast or logged**: `PlayerResponse` excludes them and `GameSessionResponseTest` checks the JSON. The interceptor depends on `PlayerTokenRegistry`, not `GameSessionService`, to avoid a circular dependency through the broadcaster's messaging template.
- **Sessions play from a `GameBoard`, never from JPA entities.** `session.board` holds immutable records (`GameBoard` → `BoardRound` → `BoardCategory` → `BoardClue`); each `BoardClue` stores its own row (1-based, gaps allowed, unique per category), value and Daily Double flag. Game progress is not on the board: `GameSession` keeps the set of played clue ids for the whole game, and the available clues are derived (`board.getClueIds(round)` minus played). `GameBoard.from(game)` copies a database `Game` into a board, following the `from(entity)` factory convention. It reads lazy collections, so it must run inside a transaction: `GameBoardService.loadBoard(gameId)` wraps it in a read-only one. These two are the only session code that touches entities. Custom games can build a `GameBoard` directly. Rounds are played in `GameRound` order and rounds without clues are skipped.
- STOMP setup (`config/WebSocketConfig`): the endpoint is `/ws`, client sends go to `/app/...`, and the simple broker serves `/topic` and `/queue`.
  - Client → server: `/app/sessions/{id}/start | choose-clue | buzz | answer | place-daily-double-wager | place-final-jeopardy-wager | answer-final-jeopardy` (`GameSessionWebSocketController`). Request bodies never contain a player id; `start` and `buzz` take no body.
  - Server → clients: after every state change, the full `GameSessionResponse` is broadcast to `/topic/sessions/{id}` (`GameSessionBroadcaster`). It includes `board`: the current round as a rectangular grid of categories × rows, where each `ClueSlotResponse` has a clue id, value and `ClueSlotStatus` (`AVAILABLE`, `PLAYED`, `EMPTY`). Everyone receives it, so it must never contain questions, answers or Daily Double flags (`GameSessionResponseTest` checks the serialized JSON). The clue in play is sent separately as `currentClue` (`CurrentClueResponse`, null when no clue is in play): a Daily Double hides its question until the wager is placed, and the answer is only included in `REVEALING_ANSWER`. Turn and buzzer info is sent as `playerChoosingClue`, `playerAnswering` and `playersLockedOut`, and a placed Daily Double wager as `currentClue.wager`. `clueAnswers` lists every answer to the current clue (submitted text, null on timeout, and whether it was correct, never the stored answer); `GameSession.clueAnswers` is cleared when a clue is chosen and when Final Jeopardy starts. These come from `GameSession` getters that only return a value while it is meaningful, because the underlying fields (`playerWhoBuzzed`, `playersAlreadyBuzzed`, a player's `wager`) are not cleared between clues. Don't expose those fields directly.
  - Errors: `InvalidGameActionException`, `GameSessionNotFoundException` and invalid payloads (`INVALID_REQUEST`) go only to the sender, at `/user/queue/errors`, as `GameSessionErrorResponse(code, message)` with a `GameErrorCode`.
- **`GameSession` (model) is a plain, non-Spring state machine** that holds all the game rules. Its states are in `GameSessionState`: `WAITING_FOR_START → CHOOSING_CLUE → [WAITING_FOR_DAILY_DOUBLE_WAGER] → REVEALING_CLUE → WAITING_FOR_BUZZER ⇄ WAITING_FOR_ANSWER → REVEALING_ANSWER → CHOOSING_CLUE …`. Once every clue in a round is used, it moves to the next round (`enterRound`). Entering the Final Jeopardy round starts Final Jeopardy instead of `CHOOSING_CLUE`; a board without one goes to `FINISHED_GAME`. Illegal actions throw `InvalidGameActionException`. On a daily double, the buzzer is skipped and the chooser answers using their wager; the maximum wager is the higher of their score and the highest clue value in the current round (`GameBoard.getHighestValue`).
- **Final Jeopardy** (one clue per board, enforced by `GameBoard`): `FINAL_JEOPARDY_WAGERING` (category shown; every player wagers 0 up to their score, so a score of 0 or less can only wager 0) → `FINAL_JEOPARDY_ANSWERING` (clue shown; every player answers) → `FINAL_JEOPARDY_REVEALING` (correct response, wagers and answers shown) → `finishFinalJeopardyReveal` applies every wager (no answer counts as wrong) → `FINISHED_GAME`. Each phase ends only on its timer. Players can change their wager or answer until then, a missing wager counts as 0, and scores only change after the reveal. The broadcast's `finalJeopardy` (`FinalJeopardyResponse`) shows per player only whether they've wagered/answered until the reveal; `FinalJeopardyResponseTest` checks the JSON.
- **`GameSessionService` is the orchestration layer.** It stores sessions in a `ConcurrentHashMap`. Every mutation follows the same pattern: `synchronized (session)` → call the model method → `scheduleTimeout(session)` → `broadcast`. Keep this pattern when adding actions. The exception is an action that doesn't change the state (the Final Jeopardy wager and answer): it must skip `scheduleTimeout`, because re-arming the timer would restart the phase. Concurrency control lives here, not in the model.
- **Timeouts:** `scheduleTimeout` switches on the new state and arms a single per-session timer (`GameSessionTimer`, a `ScheduledExecutorService`). Durations come from `GameSessionTimeouts.defaults()`. Timeout callbacks re-check the expected state before acting, so a stale timer is a no-op. A new state with a timed phase needs a case added there.
- Player IDs are numbered per session (1, 2, 3…, `GameSession.addPlayer(String)`), so they stay safe as JavaScript numbers but are **not unique across sessions**. They are public (every broadcast lists them); only the token proves identity, and the controller's session check is what stops a player from another session with the same id from acting. Sessions and tokens live in memory and are not persisted.
- **State version:** every broadcast goes through `GameSessionService.broadcast(session)`, which increments `GameSession.version` first; `GameSessionResponse.version` lets clients that both fetch and receive broadcasts keep the newest. Never call `broadcaster.broadcast` directly from the service.

## Testing conventions

- **Unit tests:** `GameSessionTest` exercises the state machine directly, and the service and timer tests use Mockito. The Mockito Java agent is attached in `build.gradle`.
- **Controller slice tests:** `@WebMvcTest` with `@MockitoBean`.
- **Entity IDs in tests:** entities have no ID setters, so tests set them with `ReflectionTestUtils.setField(entity, "id", ...)`.
- **REST integration tests:** `@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureRestTestClient` and `@ActiveProfiles("test")`. Before/after each test they load and clean up SQL fixtures from `src/test/resources/testdata/` with `@Sql`.
- **Session tests** build `GameBoard`s from records (no entities or `ReflectionTestUtils`). `GameSessionEndToEndIntegrationTest` is the one that loads a real game from the database.
- **WebSocket integration test:** builds `GameBoard`s in memory, creates sessions and joins players directly through `GameSessionService`, and drives them with a `WebSocketStompClient` that uses `JacksonJsonMessageConverter`. Acting clients connect with `connect(tokenOf(session))` (or a second player's token); plain `connect()` is anonymous. "Wrong player" tests must use a real second player's token, since bodies have no player id.
