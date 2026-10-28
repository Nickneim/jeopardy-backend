# Jeopardy backend API

The contract between this backend and the React frontend. It covers the REST API (browsing games, creating and joining sessions) and the STOMP WebSocket protocol used to play.

**Keep this file in sync with the code.** Any change to an endpoint, payload, field, state, timing or error code should update it in the same commit.

---

## 1. Running the backend locally

- Base URL: `http://localhost:8080`. The WebSocket endpoint is `ws://localhost:8080/ws`.
- Required environment variables: `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`. They live in the backend's git-ignored `.env`, which the backend reads by itself when started from the backend's root folder: from IntelliJ (any run configuration) or with `./gradlew bootRun`. Exported environment variables override it.
- **Browser origins (CORS):** the backend accepts browser requests and WebSocket connections from `http://localhost:5173` (the Vite dev server) by default. To allow other origins, set `ALLOWED_ORIGINS` to a comma-separated list, for example `ALLOWED_ORIGINS=https://jeopardy.example,http://localhost:5173`.
- **Debugging server errors:** error bodies never include stack traces or exception messages. To see them while developing the backend, run it with the `dev` profile (set the environment variable `SPRING_PROFILES_ACTIVE=dev`, for example in the IntelliJ run configuration; putting it in `.env` only works with the saved run configuration, which loads `.env` as environment variables). Never use it anywhere else.

---

## 2. How a multiplayer game works

```
0. GET  /api/game-sessions                                     → open sessions      (lobby list)
1. PUT  /api/game-sessions/{id}?session={id}            { gameId }       → { id }               (create; you pick the 5-character code)
2. POST /api/game-sessions/{id}/players?session={id}    { playerName }   → { playerId, token }  (join; the first player is the host)
3. STOMP CONNECT ws://localhost:8080/ws?session={id}    header  player-token: <token>
4. SUBSCRIBE /topic/sessions/{id}             ← full game state after every change
   SUBSCRIBE /user/queue/errors               ← errors caused by your own actions
5. GET  /api/game-sessions/{id}?session={id}                     → current state      (catch up)
6. SEND /app/sessions/{id}/start | choose-clue | buzz | answer | ... | leave
```

The `?session={id}` on every request about a session is for routing when several backend instances run (§3.2). A single instance ignores it, but always send it.

Key ideas:

- **The server is authoritative and sends full snapshots.** After every state change (a player's action, a timer expiring, someone joining), every subscriber gets the complete `GameSessionResponse`. Render from the latest message; don't try to apply deltas.
- **Catching up after connecting.** Subscribe first, then fetch `GET /api/game-sessions/{id}`. Every response carries a `version` that goes up with each change, so whether the fetch result or a broadcast arrives first, **keep whichever has the higher `version`** and ignore the other. Use the same approach after a refresh or reconnect.
- **Who you are comes from your connection, not your messages.** You prove your identity once, with your token, when you connect. Action payloads never contain a player id, so a player can't act as someone else.
- **Anyone can watch.** A connection without a token can subscribe to a session's topic but can't act.
- **Timers run on the server.** Phases such as revealing a clue or waiting for buzzes end automatically. The client just renders whatever state arrives. Every response says how long the current phase lasts and how much of it is left (`phaseDurationMs`, `phaseRemainingMs`), so countdowns are right even after a refresh. Each session has its own phase durations, which the host can set in the lobby (`timers`, §6.3).
- **The host runs the lobby.** The first player to join is the host (`hostPlayerId`). Only the host can start the game, change the timers and kick players. If the host leaves, the next player takes over (§6.4).
- **The host can run the show as Trebek** instead of playing (`hostMode: "TREBEK"`, §6.8): they show each clue when they're ready, open and close the buzzers, rule on every answer (the answer check only suggests), change any score and can pause the clock. Their secrets (the hidden clue, the answer waiting for a ruling, the Final Jeopardy wagers and answers) only reach Trebek's own connection, on `/user/queue/trebek`. A session in the default `CONTESTANT` mode plays exactly as described everywhere else.
- **Anyone can join at any time**, unless the host blocked joining after the start (`allowLateJoin`). Everyone who joins, including someone who left and comes back, starts with the session's `joinScore`, which is never better than the lowest score of anyone who stayed (§6.6).
- **Answers are checked leniently.** Spelling mistakes, word order and punctuation are forgiven, numbers aren't, and Double and Final Jeopardy answers must be phrased as a question ("What is ...?"). Correct responses use `(N of) A | B` for lists of different answers and `[A | B]` for one answer worded differently (§6.7).
- **Leaving is explicit.** Closing the tab or losing the connection is *not* leaving: the player stays in the game, marked `away`, and can reconnect with their token. Only the `leave` action (or being kicked) removes a player, and it makes their token stop working (§6.4).
- **Sessions don't live forever.** Idle lobbies, finished games and sessions everyone has left are removed from memory (§6.5).

---

## 3. REST API

All bodies are JSON. Errors use `ApiErrorResponse` (`{ "status": 404, "code": "GAME_NOT_FOUND", "message": "..." }`). `status` is always the HTTP status. React to `code`, not to the message, since several errors share a status (two different `409`s on a join or create, say):

| `code` | Status | When |
|---|---|---|
| `INVALID_REQUEST` | `400` | A query parameter, path session code or body that isn't valid (every `400` below) |
| `GAME_NOT_FOUND` | `404` | Unknown game id (`GET /api/games/{id}`, or creating a session from one) |
| `CLUE_NOT_FOUND` | `404` | Unknown clue id (`GET /api/clues/{id}`) |
| `SESSION_NOT_FOUND` | `404` | Unknown or removed session |
| `SESSION_ALREADY_EXISTS` | `409` | Creating a session under a code in use: pick another code |
| `SESSION_NOT_JOINABLE` | `409` | Joining a session that doesn't take new players right now |
| `UNPLAYABLE_GAME` | `422` | Creating a session from a game that can't be played |
| `INVALID_CUSTOM_GAME` | `422` | Creating a session from a custom show that can't be played. The body also has `problems` (§3.2). |

`SESSION_NOT_FOUND` is a `GameErrorCode` (§7): a game error that reaches REST keeps its STOMP code, so `code` is typed `ApiErrorCode | GameErrorCode` (§8). No other `GameErrorCode` is sent over REST today.

That includes request bodies that fail validation (a missing or blank field): `400` with a message naming each invalid field, for example `"playerName must not be blank"` (several are joined with `", "`). Messages are always in English, whatever the `Accept-Language` A body that isn't JSON, is missing, or has a field of the wrong type (`{ "gameId": "abc" }`, or a number too large for its field) gets `400` with `"Invalid request: the body isn't valid JSON for this request"`.

Any other error (an unexpected server error, or a malformed path such as a game id that isn't a number) uses Spring's default body instead: `{ "timestamp", "status", "error", "path" }`. It has no stack trace and no exception message, so show a generic error for it.

### 3.1 Browsing games

#### `GET /api/games`

A paged list of games, sortable and filterable. By default newest first (by `gameDate`, then `id`, both descending).

| Query param | Default | Rules |
|---|---|---|
| `page` | `0` | ≥ 0 (0-based) |
| `size` | `20` | 1–100 |
| `season` | (none) | Optional exact, case-insensitive filter, for example `"40"` (values from `GET /api/games/seasons`). Leading and trailing spaces are ignored; empty or blank means no filter. |
| `title` | (none) | Optional case-insensitive substring of the title. `%` and `_` are plain characters, not wildcards. Leading and trailing spaces are ignored; empty or blank means no filter. |
| `from` | (none) | Optional earliest `gameDate`, inclusive, `YYYY-MM-DD` |
| `to` | (none) | Optional latest `gameDate`, inclusive, `YYYY-MM-DD` |
| `playable` | (none) | Optional. `true`: only games with at least one clue (a session can only be created from one of these, §3.2; it can still be rejected with `UNPLAYABLE_GAME` for another reason, such as an answer no response could match). `false`: only games without clues. Omit it to list both. |
| `finalCategory` | (none) | Optional case-insensitive substring of the game's Final Jeopardy category name (`finalCategory` below), matched like `title`: `%` and `_` are plain characters, leading and trailing spaces are ignored, and empty or blank means no filter. Games without a Final Jeopardy clue never match. |
| `sort` | `gameDate` | One of `title`, `season`, `gameDate`, `clueCount` (see below) |
| `direction` | `desc` for `gameDate`, `asc` for any other `sort` | `asc` or `desc` |

All filters are optional and combine with AND. `totalElements` / `totalPages` count only the matching games. A page past the last one (however far) returns an empty `content` with the real totals.

**Sorting** applies to every matching game before paging, so page 2 continues page 1's order. Ties are broken by `id`, in the same direction. `sort` and `direction` values are case-insensitive, and an empty one means its default. The sorts:

- `title`: case-insensitive, character by character (spaces and punctuation count), the same as the lobby's `gameTitle` sort. It's text order, not numeric: `Show #10 - …`, `Show #100 - …`, `Show #11 - …`.
- `season`: like `GET /api/games/seasons`: numbered seasons as numbers (`"9"` before `"10"`), then the special seasons (`"jm"`, `"pcj"`) by name, case-insensitive. The direction applies to both, but **special seasons come after the numbered ones in both directions**: `asc` is `9, 10, 40, jm, pcj`, `desc` is `40, 10, 9, pcj, jm`.
- `gameDate`: the air date.
- `clueCount`: the number of clues (so games without clues come first with `asc`).

Response: `PageResponse<GameSummaryResponse>`. Each game's `clueCount` is how many clues it has. Some imported games, such as the newest ones, have none: they can be browsed but not played, so show them differently or hide them with `playable=true`. `finalCategory` is the name of the game's Final Jeopardy category, or `null` if it has no Final Jeopardy clue (a category without its clue doesn't count, as in a session), for example to list games by their Final Jeopardy when importing one into a custom show (§3.2).

Errors (all `400` with an `ApiErrorResponse` whose `code` is `INVALID_REQUEST`):

| Cause | `message` |
|---|---|
| Invalid `page` or `size` (out of range or not a whole number) | `"Invalid request parameters"` |
| `from` or `to` isn't a valid `YYYY-MM-DD` date (for example `2025-02-30` or `02/01/2025`) | `"Invalid date for 'from': expected YYYY-MM-DD"` (or `'to'`) |
| `from` is after `to` | `"'from' must not be after 'to'"` |
| `playable` isn't a boolean | `"Invalid value for 'playable': expected true or false"` |
| `sort` isn't one of the four | `"Invalid value for 'sort': expected one of title, season, gameDate, clueCount"` |
| `direction` isn't `asc` or `desc` | `"Invalid value for 'direction': expected one of asc, desc"` |

#### `GET /api/games/seasons`

Every season that has at least one game, with its game count, for example to fill a season filter. Not paged. Seasons that differ only in case count as one season and are shown in lowercase, since the `season` filter is case-insensitive. Response: `SeasonResponse[]`, for example `[{ "season": "42", "gameCount": 230 }, …]`.

Order, newest first:

1. Numbered seasons (`season` is all digits), by number from highest to lowest. They're compared as numbers, so `"10"` comes before `"9"`.
2. Then special seasons whose `season` isn't a number (such as `"jm"` or `"pcj"`: tournaments and other specials), by their most recent `gameDate`, newest first, then by name.

Pass a `season` value unchanged as the `season` filter of `GET /api/games`.

#### `GET /api/games/{id}`

A full game with every category and clue. **It includes answers and Daily Double locations**, which is fine for browsing and single-player use, but never send it to other players in a multiplayer game. Response: `GameResponse`. Unknown id: `404 { "status": 404, "code": "GAME_NOT_FOUND", "message": "Game not found: 999" }`.

#### `GET /api/clues/{id}`

One clue. Response: `ClueResponse`, whose `gameId` is the game it belongs to (for `GET /api/games/{id}`). Unknown id: 404.

### 3.2 Game sessions

#### Session codes

A session's id is a 5-character **session code**, such as `7KQ2M`, everywhere: in paths, in `session` parameters, in STOMP destinations and in every response's `id`. Responses always give it in uppercase. When reading a code from the path or a destination, the backend is lenient about how someone typed it: lowercase is accepted, and `O`, `I` and `L` are read as `0`, `1` and `1`, so `7kq2m` and `7KQ2M` are the same session. Anything else that isn't 5 characters of the alphabet (see the create below) gets `400 { "status": 400, "code": "INVALID_REQUEST", "message": "Invalid session code: expected 5 letters and digits" }` over REST, and `INVALID_REQUEST` with the same message over STOMP (§7). Normalize a typed code the same way before using it as the `session` parameter, so that it's routed like the server's own.

#### Routing: the `session` parameter

A session lives in the memory of one backend instance. When several instances run behind a load balancer (for example in Kubernetes, see the backend's `k8s/README.md`), the load balancer picks the instance for each request from the **`session` query parameter**, so that everything about one session reaches the instance that holds it. A path segment can't be used, because load balancers can't read part of a path, and neither can a header, because browsers can't add headers to a WebSocket connection.

**Every request about one session must carry `?session=<its id>`:** creating it (`PUT /api/game-sessions/{id}`), fetching it, joining it, and the `/ws` connection used to play or watch it (§4.1). The value is the session id, the same as in the path. A request without it may reach an instance that doesn't have the session and get a 404 (or, on the WebSocket, a rejected token), so add it even when running a single instance, where it's simply ignored.

Requests that aren't about one session (the lobby list and §3.1) need no parameter and can reach any instance. The lobby list still covers every instance: the one that answers collects the others' sessions. If an instance doesn't answer it within a second, that instance's sessions are missing from that response, and from later ones until it answers again (it's asked again in the background).

#### `GET /api/game-sessions`

The sessions in memory, for a lobby page where players pick a game to join or watch. No token is needed: entries never contain tokens or anything else secret.

| Query param | Default | Rules |
|---|---|---|
| `page` | `0` | ≥ 0 (0-based) |
| `size` | `20` | 1–100 |
| `includeFinished` | `false` | `true` also lists sessions in `FINISHED_GAME` (they stay in memory for 10 minutes, §6.5) |
| `search` | (none) | Optional case-insensitive substring of `gameTitle` or `hostName` (either matches). Leading and trailing spaces are ignored; empty or blank means no filter. |
| `stage` | (none) | Optional, one of the stages below |
| `joinable` | `false` | `true`: only sessions whose `joinable` is `true`. `false` doesn't filter. |
| `sort` | `createdAt` | One of `id`, `gameTitle`, `gameSeason`, `gameDate`, `hostName`, `playerCount`, `stage`, `createdAt` |
| `direction` | `desc` for `createdAt`, `asc` for any other `sort` | `asc` or `desc` |

`stage` values (they're not in the response: derive a session's stage from `state` and `round` the same way):

| `stage` | Sessions |
|---|---|
| `LOBBY` | `WAITING_FOR_START` |
| `JEOPARDY` | A game in progress (any state but `WAITING_FOR_START`, `FINISHED_GAME` and `CLOSED`) whose `round` is `JEOPARDY` |
| `DOUBLE_JEOPARDY` | The same, in `DOUBLE_JEOPARDY` |
| `FINAL_JEOPARDY` | The same, in `FINAL_JEOPARDY` (the `FINAL_JEOPARDY_*` states) |
| `FINISHED` | `FINISHED_GAME`. This implies `includeFinished=true`. |

- **Filtering:** by default every session that hasn't finished: lobbies (`WAITING_FOR_START`) and games in progress. Closed sessions are never listed, because they're already gone. All filters combine with AND, and apply to every instance's sessions before paging, so `totalElements` / `totalPages` count only the matches.
- **Order:** by default newest first by `createdAt`. Like the filters, sorting covers every instance's sessions together, before paging. Ties are broken by `id`, in the same direction. Values that are `null` (`gameTitle`, `gameSeason`, `gameDate`, `hostName`) sort **last in both directions**. `gameTitle` and `hostName` are case-insensitive, `gameSeason` sorts like `season` in `GET /api/games` (numbered seasons as numbers, then specials by name, specials after numbered ones in both directions), and `stage` sorts in the order `LOBBY`, `JEOPARDY`, `DOUBLE_JEOPARDY`, `FINAL_JEOPARDY`, `FINISHED`. `stage`, `sort` and `direction` values are case-insensitive, and an empty one means its default.
- Response: `PageResponse<GameSessionSummaryResponse>` (the same page shape as `GET /api/games`):

| Field | Meaning |
|---|---|
| `id` | Session id |
| `gameId` | The stored game it was created from (`null` for a custom show) |
| `gameTitle` | That game's title (`null` if it has none), or a custom show's name |
| `gameSeason` | That game's season, as in `GameSummaryResponse.season` (`null` when `gameId` is) |
| `gameDate` | That game's air date, `YYYY-MM-DD` (`null` when `gameId` is) |
| `custom` | `true` for a custom show (§3.2, create), `false` for a stored game |
| `clueCount` | How many clues the board has, in every round and Final Jeopardy |
| `state` | The session's `GameSessionState` (§6). Use it to show "waiting for players" vs "in progress". |
| `round` | The round being played, or about to be |
| `playerCount` | Players who haven't left. Players waiting to be removed (`leaving`, §6.4) aren't counted, and neither is Trebek (§6.8). |
| `hostName` | The host's name (Trebek's in a Trebek session), or `null` before anyone has joined |
| `hostMode` | `CONTESTANT`, or `TREBEK` when the host runs the show (§6.8), so the list can mark a Trebek session before anyone joins it |
| `createdAt` | When the session was created, an ISO-8601 UTC timestamp such as `"2026-10-09T12:30:00Z"` |
| `joinable` | Whether a join would be accepted right now: always `true` in the lobby, and during the game only if `allowLateJoin`, except never in `FINAL_JEOPARDY_ANSWERING`, `FINAL_JEOPARDY_REVEALING` or `FINISHED_GAME` (§6.6) |
| `allowLateJoin` | Whether the host allows joining after the start (§6.6) |
| `joinScore` | The score a player who joins now starts with (§6.6) |

Custom shows are listed like any other session: `search` finds them by name (their `gameTitle`), and sorting by `gameSeason` or `gameDate` puts them last in both directions, since theirs are `null`.

The list is a snapshot: a session can start, fill up or close right after. Use `joinable` to decide whether to offer "Join" or only "Watch"; anyone can watch any listed session.

Errors (all `400` with an `ApiErrorResponse` whose `code` is `INVALID_REQUEST`):

| Cause | `message` |
|---|---|
| Invalid `page` or `size` (out of range or not a whole number) | `"Invalid request parameters"` |
| `includeFinished` or `joinable` isn't a boolean | `"Invalid value for 'includeFinished': expected true or false"` (or `'joinable'`) |
| Unknown `stage` | `"Invalid value for 'stage': expected one of LOBBY, JEOPARDY, DOUBLE_JEOPARDY, FINAL_JEOPARDY, FINISHED"` |
| Unknown `sort` | `"Invalid value for 'sort': expected one of id, gameTitle, gameSeason, gameDate, hostName, playerCount, stage, createdAt"` |
| `direction` isn't `asc` or `desc` | `"Invalid value for 'direction': expected one of asc, desc"` |

#### `PUT /api/game-sessions/{sessionId}?session={sessionId}`

Creates a live session from a stored game or a custom show, **under a code the client chooses**. Nobody is in it yet: the creator should join next, which makes them the host. The client picks the code so that this request can already be routed by it (see "Routing" above).

- `sessionId`: a new **session code**, 5 random characters from `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (Crockford's base32: digits and uppercase letters without I, L, O and U), for example `7KQ2M`. Pick each character with `crypto.getRandomValues()`. Codes are short so that people can read them out and type them; they aren't secret (the lobby lists every session, and only a player's token proves who they are).
- Request: **either** `{ "gameId": 123 }` for a stored game **or** `{ "customGame": CustomGameRequest }` for a custom show (see "Custom shows" below). Exactly one of the two must be set: both or neither is `400 { "status": 400, "code": "INVALID_REQUEST", "message": "Exactly one of gameId and customGame must be set" }`. The session starts with the default timers and settings; the host changes them in the lobby (`update-timers`, `update-settings`, §4.3). Any other field in the body (such as an old `timers` object) is ignored.
- `201 { "id": "7KQ2M" }`: the same code.
- Code already in use: `409 { "status": 409, "code": "SESSION_ALREADY_EXISTS", "message": "Game session already exists: <code>" }`, checked before the game or custom show (so a code in use gets a 409 even with an unknown game or a custom show that can't be played). The code of a closed session counts as in use while anyone (a player or a spectator) is still subscribed to its topic, who would otherwise receive the new session's broadcasts as the closed one's (§6.5). A closed session's players who are still connected can't act in a new session under its code, even though player ids start at 1 again: their actions get `PLAYER_NOT_IN_SESSION`. Only a code that had a session is held this way, so unsubscribe from a closed session's topic once you're done with it; a subscription to a code that never had one doesn't reserve it. There are about 33.5 million codes, so another session can occasionally have it: **pick a new code and send the create again** (with its new code in the path and in `session`). A retry after a lost response may also get a 409; it then creates a second, empty session under the new code, which is removed after 30 minutes like any lobby nobody joins.
- A code that isn't valid (see below): `400 { "status": 400, "code": "INVALID_REQUEST", "message": "Invalid session code: expected 5 letters and digits" }`.
- Unknown game: 404.
- Game that can't be played: `422 { "status": 422, "code": "UNPLAYABLE_GAME", "message": "Game 725 can't be played: the board has no clues" }`. Usually a game without clues (some imported games, such as the newest ones, have none); the message gives any other reason, such as a clue whose stored answer no response could match. The game itself is valid and can still be browsed.
- Custom show that can't be played: `422 INVALID_CUSTOM_GAME`, with every problem (below).
- A session nobody joins is removed after 30 minutes (§6.5).

##### Custom shows

A custom show is a board the host built in the browser: categories imported from stored games (`GET /api/games/{id}` has everything an import needs) and clues of their own. Nothing is stored: the board lives in the session's memory and goes away with it. Everything about building it (the draft, undo, exporting to a file, checking that media links load) happens in the frontend; the backend only sees it when it's hosted.

`CustomGameRequest`:

| Field | Rules |
|---|---|
| `name` | The show's name. Listed as the session's `gameTitle`. |
| `jeopardyRound` | Up to 6 `CustomCategoryRequest` (required, may be empty) |
| `doubleJeopardyRound` | Up to 6 `CustomCategoryRequest` (required, may be empty) |
| `finalJeopardy` | A `CustomFinalJeopardyRequest`, or `null` for a show without Final Jeopardy |

`CustomCategoryRequest`:

| Field | Rules |
|---|---|
| `column` | 1–6, unique within its round. Categories are shown in column order. |
| `name` | The category's name, or `null` / blank for one the host never named. Send `null` for a default name ("Category #4"), so the backend never has to recognize one. An unnamed category without clues is left off the board. |
| `comments` | The imported category's comments (`"(Alex: …)"`), or `null`. Shown as `BoardCategoryResponse.comments`. |
| `sourceCategoryId` | The stored category it was imported from, or `null`. Information only: the backend never reads it. |
| `clues` | Up to 5 `CustomClueRequest`, required (may be empty), rows unique. A row without a clue is simply left out, and becomes an `EMPTY` slot. |

`CustomClueRequest`:

| Field | Rules |
|---|---|
| `row` | 1–5. The value follows the row, as for stored games: Jeopardy $200–$1000, Double Jeopardy $400–$2000. |
| `question` | The clue, 1–1000 characters, stored and sent **exactly as written**. It may contain the same HTML tags as stored clues (`<i>`, `<br>`); the backend doesn't strip, escape or check them. |
| `answer` | The correct response, 1–500 characters, in the §6.7 format |
| `clueType` | `TEXT`, `IMAGE`, `SOUND` or `VIDEO` (required) |
| `mediaUrl` | `null` for `TEXT`; for the others an `http` or `https` URL, at most 2000 characters, on any host |
| `dailyDouble` | `true` to make it a Daily Double (`false` if left out) |

`CustomFinalJeopardyRequest`: `{ name, comments, question, answer }`, with `name`, `question` and `answer` as above. No media and no Daily Double; the clue is worth 2000 like a stored one (only the board shows it).

"Characters" are code points, as for player names (`[...text].length`). Names are trimmed; questions and answers are kept as they are, but one that is blank counts as missing.

**A request that isn't shaped right is a plain `400 INVALID_REQUEST`**, like any other invalid body: a missing round or `clues` list, a `column` or `row` out of range, more than 6 categories or 5 clues, a missing or unknown `clueType`, a field of the wrong type. The message names the field, for example `"customGame.jeopardyRound[0].column must be between 1 and 6"`, `"customGame.doubleJeopardyRound has two categories in column 2"` or `"customGame.jeopardyRound[1].clues has two clues on row 4"`, except for a field of the wrong type or an unknown `clueType`, which get the unreadable-body message of §3. These are frontend bugs, not something the host can fix.

**What makes a custom show playable.** The backend decides; the frontend doesn't need to check anything before hosting. A show is accepted when:

1. **It has at least one clue**, in any round (Final Jeopardy counts). Rounds without clues are skipped as for stored games (§6.2), and a show without Final Jeopardy ends after its last round.
2. **The show has a name**: not blank after trimming, at most 100 characters.
3. **Every category with at least one clue has a name**, at most 100 characters. A named category without clues is fine: its column is all `EMPTY`. A category name over 100 is a problem even without clues.
4. **Final Jeopardy is all or nothing**: `null`, one whose name, question and answer are all blank (the same as `null`), or one with a name, a question and an answer.
5. **Every clue has a question and an answer**, within the lengths above.
6. **Every answer can be read**: it follows the §6.7 format, and some response could match it (the check that makes a stored game `UNPLAYABLE_GAME`). Stored answers are read leniently (an unclosed `[` is just text), but a custom answer must have its brackets and parentheses paired, no `[` inside another, no empty wording in `[ ]` or empty option in a list, `|` outside `[ ]` only in a `(N of)` list, and an N between 1 and the number of options. How a correct answer then matches is the same as for stored games.
7. **Media matches the type**: an `IMAGE`, `SOUND` or `VIDEO` clue has a `mediaUrl` that is an `http(s)` URL with a host; a `TEXT` clue has none (a blank one counts as none).
8. **At most 1 Daily Double in Jeopardy and 2 in Double Jeopardy.** Fewer is fine, including none: there are exactly as many Daily Doubles as are pinned, and none are added at random.

When a show breaks any of these, the create fails with **every** problem found, so the host can fix them all at once:

```json
{
  "status": 422,
  "code": "INVALID_CUSTOM_GAME",
  "message": "This custom show can't be played: 4 problems",
  "problems": [
    { "code": "CATEGORY_NAME_MISSING", "round": "JEOPARDY", "column": 3, "row": null,
      "message": "Category 3 has clues but no name" },
    { "code": "ANSWER_UNREADABLE", "round": "JEOPARDY", "column": 3, "row": 2,
      "message": "The correct response can't be read: a [ is never closed" },
    { "code": "MEDIA_URL_MISSING", "round": "JEOPARDY", "column": 3, "row": 2,
      "message": "This video clue has no media link" },
    { "code": "FINAL_CLUE_MISSING", "round": "FINAL_JEOPARDY", "column": null, "row": null,
      "message": "Final Jeopardy has a category but no clue" }
  ]
}
```

Problems come in board order: the show's own first, then each round (its Daily Double count, then each category by column, followed by its clues by row), then Final Jeopardy. Each one says where it is, using the request's columns and rows:

| `round` | `column` | `row` | Where |
|---|---|---|---|
| `null` | `null` | `null` | The whole show (its name, or no clues at all) |
| A round | `null` | `null` | The whole round (too many Daily Doubles), or Final Jeopardy |
| A round | 1–6 | `null` | A category |
| A round | 1–6 | 1–5 | A clue |

| `code` | Where | When |
|---|---|---|
| `NO_CLUES` | show | Rule 1 |
| `SHOW_NAME_MISSING` | show | Rule 2: no name, or only spaces |
| `SHOW_NAME_TOO_LONG` | show | Rule 2: over 100 characters |
| `CATEGORY_NAME_MISSING` | category, or Final | Rule 3, or a Final Jeopardy question or answer without a name (rule 4) |
| `CATEGORY_NAME_TOO_LONG` | category, or Final | Over 100 characters |
| `FINAL_CLUE_MISSING` | Final | Rule 4: a name, but neither a question nor an answer |
| `QUESTION_MISSING` | clue, or Final | Rule 5 |
| `QUESTION_TOO_LONG` | clue, or Final | Over 1000 characters |
| `ANSWER_MISSING` | clue, or Final | Rule 5 |
| `ANSWER_TOO_LONG` | clue, or Final | Over 500 characters (its format isn't checked then) |
| `ANSWER_UNREADABLE` | clue, or Final | Rule 6. The message says why, for example `"a [ is never closed"`. |
| `MEDIA_URL_MISSING` | clue | Rule 7: a non-`TEXT` clue without a link |
| `MEDIA_URL_INVALID` | clue | Rule 7: not an `http(s)` URL with a host, or over 2000 characters |
| `MEDIA_URL_NOT_ALLOWED` | clue | Rule 7: a `TEXT` clue with a link |
| `TOO_MANY_DAILY_DOUBLES` | round | Rule 8 |

Mark each problem on the board by its position. Every `message` is in English and can be shown as it is. A stored clue that breaks a limit can still be imported; hosting then reports it (`QUESTION_TOO_LONG`, say) so the host can fix it, and the same goes for an imported answer that can't be read.

**Playing a custom show** is exactly like playing a stored game, and `GameSessionResponse` doesn't change. The backend numbers the clues 1, 2, 3… in board order (Jeopardy by column and row, then Double Jeopardy, then Final Jeopardy); those ids are only used in `choose-clue` and `currentClue.clueId`, like any other. `currentClue.question` and `finalJeopardy.question` contain exactly what the board had, tags included, the same as a stored game's: render them the same way.

#### `GET /api/game-sessions/{sessionId}?session={sessionId}`

The session's current state: the same `GameSessionResponse` as the latest broadcast, including its `version`. Use it to catch up after connecting, refreshing or reconnecting (§2). No token is needed, because the state never contains secrets. Unknown session: 404. That includes a session that has been removed (§6.5): treat a 404 as "this session no longer exists".

#### `POST /api/game-sessions/{sessionId}/players?session={sessionId}`

Joins a session.

- Request: `{ "playerName": "Nico" }` (must not be blank, at most 30 characters). The name is cleaned first, and validated as cleaned:
  - Line breaks, tabs and other whitespace (including the non-breaking and ideographic spaces) become an ordinary space: `"Mary\nJane"` joins as `"Mary Jane"`.
  - Other control characters and the direction controls (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) are removed, since they break how the name is laid out (U+202E reverses everything after it).
  - Combining marks stacked past 4 on one character are removed, so they can't pile up over other lines.
  - Spaces and zero-width spaces (U+200B, U+2060, U+FEFF) at either end are removed: `" Nico "` joins as `"Nico"`. A name with nothing left counts as blank.
  - **Everything else is kept, invisible or not**: zero-width spaces inside a name, soft hyphens, joiners, tag characters, Hangul fillers. Names are display text only, and since duplicate names are allowed (§9), a name that looks like another gains nothing. Treat a name as nothing but text to show.
  - "Characters" are Unicode code points, what JavaScript's `[...name].length` counts: 😀 is 1, 👩‍💻 is 3 (woman, joiner, laptop).
- `201 { "playerId": 1, "token": "Xk3…43 chars" }`. Players are numbered 1, 2, 3… within each session, so ids are only unique inside a session. **Ids are never reused**: after player 3 leaves, the next player to join is 4, not 3.
- The first player to join becomes the host (§6.4). Nobody joins as Trebek: only the host switches to it, in the lobby (§6.8).
- Unknown (or removed) session: `404 { "status": 404, "code": "SESSION_NOT_FOUND", "message": "Game session not found: <id>" }`
- Missing or blank name: `400 { "status": 400, "code": "INVALID_REQUEST", "message": "playerName must not be blank" }`
- Name longer than 30 characters: `400 { "status": 400, "code": "INVALID_REQUEST", "message": "playerName must be at most 30 characters" }`

**The token is a secret.** Only the joining player receives it. Store it (for example in `sessionStorage` or `localStorage`, keyed by session id) so the player can reconnect after a refresh, and never display it or send it anywhere except the CONNECT header. Joining also broadcasts the updated player list to the session's topic.

Players can join in the lobby and mid-game, unless the host has blocked joining after the start (`allowLateJoin: false`, §6.6). Then a join after `WAITING_FOR_START` returns `409 { "status": 409, "code": "SESSION_NOT_JOINABLE", "message": "This game has already started and doesn't accept new players" }`. Joining the lobby is always allowed. Whatever `allowLateJoin` says, nobody can join once Final Jeopardy's wagering is over, `409 { "status": 409, "code": "SESSION_NOT_JOINABLE", "message": "Final Jeopardy is underway and doesn't accept new players" }`, or once the game has finished, `409 { "status": 409, "code": "SESSION_NOT_JOINABLE", "message": "This game has finished and doesn't accept new players" }` (§6.6). Watching (§4.1) never needs a join.

Every new player starts with the session's current `joinScore` (0 until someone's score drops below 0, §6.6), shown in their `PlayerResponse.score`. Someone who left can join again the same way, under any name (even the same one): they become a new player with a new id and token and the current `joinScore`, and nothing else from their old self carries over.

---

## 4. WebSocket (STOMP)

### 4.1 Connecting

- URL: `ws://localhost:8080/ws?session=<sessionId>`. It's a **plain WebSocket, not SockJS**, so use a client such as [`@stomp/stompjs`](https://github.com/stomp-js/stompjs).
- **One connection per session.** The `session` parameter routes the connection to the instance holding that session (§3.2), so use the connection only for that session: subscribe to its topic and send its actions. To play or watch another session, open another connection with that session's id.
- To play, send the token in the CONNECT frame header `player-token` (a STOMP 1.2 `STOMP` frame works the same way).
- With no token you get an anonymous connection that can only watch.
- An unknown or invalid token **rejects the connection** with a STOMP ERROR frame (message `"Unknown player token"`) before CONNECT completes. A token stops working when its player leaves or is kicked (§6.4) and when its session is removed (§6.5). In every case, treat the rejection as "this token is gone": forget it and show the join form.
- Reconnecting with the same token restores the same player. Dropping the connection doesn't make a player leave.
- **Heartbeats.** The server offers STOMP heartbeats every 10 seconds in each direction (`heart-beat: 10000,10000` on CONNECTED); the actual interval each way is the larger of the two sides' values, and a side that sends 0 gets none that way. Keep them on (`@stomp/stompjs` sends `10000,10000` by default). A connection that promised heartbeats and sends nothing at all, not even a heartbeat, for three intervals is closed by the server, which happens to a browser tab in the background that throttles its timers. That close comes **without an ERROR frame**: the WebSocket just closes (code 1000, reason `"Missed heartbeats"`), the player becomes `away` like after a closed tab, and the client reconnects with the same token. A DISCONNECT without a `receipt` header is answered the same way (the WebSocket closes with code 1000 and no ERROR frame). Heartbeats also keep an idle connection open through proxies that close quiet ones.
- **An ERROR frame always means the token no longer works.** The server sends one only when it rejects a token on CONNECT (above) and when it closes a player's connections because they left or were kicked (below). Every other close, whether the server dropped the connection or the network did, comes without one: reconnect with the same token. (A malformed frame also gets an ERROR, but a STOMP library never sends one.)
- **When a player leaves or is kicked, the server closes their open connections** with a STOMP ERROR frame, *after* CONNECT completed. Its `message` header is `"You left the session"` or `"You were removed from the session by the host"`. A client that then reconnects with the old token is rejected as above, so the usual "token rejected" handling takes them back to the join form. On each of their connections, everything sent to it before they left arrives first, including the broadcast that removed them; then the ERROR frame arrives, then the WebSocket closes (code 1002). Nothing is sent to it after the ERROR frame. Only a connection that drops on its own at the same moment misses the frame.
- When a session is removed, connections are **not** closed: everyone receives a last broadcast with `state: "CLOSED"` (§6.5). A reconnect after that is rejected, and `GET /api/game-sessions/{id}` returns 404.

```ts
import { Client } from "@stomp/stompjs";

const client = new Client({
  brokerURL: `ws://localhost:8080/ws?session=${encodeURIComponent(sessionId)}`,
  connectHeaders: { "player-token": token },   // omit to watch anonymously
  onConnect: () => {
    client.subscribe(`/topic/sessions/${sessionId}`, (frame) => {
      const state: GameSessionResponse = JSON.parse(frame.body);
      // render state
    });
    client.subscribe("/user/queue/errors", (frame) => {
      const error: GameSessionErrorResponse = JSON.parse(frame.body);
      // show error.message, react to error.code
    });
  },
  onStompError: (frame) => {
    // The token no longer works (rejected, or the player left or was
    // kicked): forget it and send the user back to join.
  },
  // Any other close (including missed heartbeats) has no ERROR frame:
  // @stomp/stompjs reconnects with the same connectHeaders on its own.
});
client.activate();

// Sending an action:
client.publish({
  destination: `/app/sessions/${sessionId}/choose-clue`,
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ clueId: 1234 }),
});
```

### 4.2 Subscriptions

| Destination | What arrives |
|---|---|
| `/topic/sessions/{sessionId}` | `GameSessionResponse` after every state change, to everyone in the session |
| `/user/queue/errors` | `GameSessionErrorResponse`, only for errors caused by **your** messages |
| `/user/queue/trebek` | `TrebekViewResponse`, Trebek's private view (§6.8). Only Trebek's connections receive anything here: once as soon as they subscribe (so a reconnect or refresh catches up), then with every broadcast of a Trebek session. Anyone else's subscription receives nothing. |

Subscribe to the first two **before** sending actions, otherwise you can miss the response to your own action. Trebek also subscribes to the third.

### 4.3 Actions (client → server)

All destinations are prefixed with `/app/sessions/{sessionId}/`. None of them includes a player id.

| Destination | Body | Allowed when |
|---|---|---|
| `start` | none (an empty body or `{}` is fine) | `WAITING_FOR_START`; **host only** |
| `update-timers` | `Partial<SessionTimers>`, for example `{ "buzzerMs": 8000 }` (send `{}` at least) | `WAITING_FOR_START`; **host only**. Durations left out keep their current value. Each must be 1000–300000 ms (§6.3). |
| `update-settings` | `{ "allowLateJoin"?: boolean, "hostMode"?: HostMode, "timerModes"?: Partial<SessionTimerModes> }`, at least one of them | `WAITING_FOR_START`; **host only**. `allowLateJoin` blocks or allows joining after the start (§6.6); `hostMode` and `timerModes` choose whether the host plays or runs the show as Trebek, and which phases wait for Trebek (§6.8). A setting left out keeps its value; `{}` gets `INVALID_REQUEST`, `"At least one setting must be set"`. |
| `kick` | `{ "playerId": number }` | Any state except `CLOSED`; **host only**; not yourself (use `leave`). The kicked player is treated exactly like a player who leaves. |
| `leave` | none | Any state except `CLOSED` (§6.4) |
| `choose-clue` | `{ "clueId": number }` | `CHOOSING_CLUE`, if you are `playerChoosingClue`, or Trebek choosing for them (§6.8) |
| `buzz` | none | `WAITING_FOR_BUZZER`, unless you're in `playersLockedOut` |
| `answer` | `{ "answer": string }` (at most 200 characters, counted like names, §3.2) | `WAITING_FOR_ANSWER`, if you are `playerAnswering` |
| `place-daily-double-wager` | `{ "wager": number }` | `WAITING_FOR_DAILY_DOUBLE_WAGER`, if you chose the clue, or Trebek wagering for the chooser (§6.8) |
| `place-final-jeopardy-wager` | `{ "wager": number }` | `FINAL_JEOPARDY_WAGERING` (can be changed until the phase ends) |
| `answer-final-jeopardy` | `{ "answer": string }` (at most 200 characters, counted like names) | `FINAL_JEOPARDY_ANSWERING` (can be changed until the phase ends) |

Trebek's own actions (`advance`, `return-clue`, `rule`, `rule-final`, `adjust-score`, `pause`, `resume`) are in §6.8. Trebek doesn't play: a `buzz`, `answer`, `place-final-jeopardy-wager` or `answer-final-jeopardy` from Trebek gets `NOT_YOUR_TURN`, `"Trebek doesn't play!"`.

Every action requires a player who hasn't left. A player who has left can't act at all, even while they still hold a role (§6.4).

A successful action produces a broadcast. A failed one produces an error on `/user/queue/errors` and nothing else. Every action that fails gets an error, including one with an unreadable body and one that fails because of a server bug (`INTERNAL_ERROR`, §7).

**Invalid bodies** get `INVALID_REQUEST` (§7). A missing required field or a too-long answer gets a message naming each invalid field, for example `"clueId must not be null"` or `"answer must be at most 200 characters"` (several are joined with `", "`); messages are always in English. An empty body on an action that takes one (`{}` counts as a body) gets `"Payload value must not be empty"`. A body that isn't JSON, or has a field of the wrong type (`{ "clueId": "abc" }`, or a wager too large for a 32-bit integer), gets `"Invalid request: the body isn't valid JSON for this action"`.

**Messages from one connection are handled in the order they were sent.** For example, two `place-final-jeopardy-wager` messages sent back to back always leave the second wager in place, so there's no need to wait for a broadcast before sending the next message. There's no ordering between different connections: when two players act at the same moment, either can be first (see §7).

**Frames to one connection arrive in the order the server sent them**: broadcasts in `version` order, and an error after the broadcasts sent before it. Keeping the highest `version` (§2) is still needed when you mix them with a `GET` of the state.

---

## 5. The game state (`GameSessionResponse`)

| Field | Meaning |
|---|---|
| `id` | Session code, 5 uppercase characters such as `"7KQ2M"` (§3.2) |
| `version` | Goes up by one with every change (0 for a new session). Keep the response with the highest version (§2). |
| `round` | `JEOPARDY` \| `DOUBLE_JEOPARDY` \| `FINAL_JEOPARDY`, the round being played (or about to be, before the start) |
| `state` | See §6 |
| `closeReason` | Why the session was removed: `ABANDONED` \| `IDLE` \| `FINISHED` \| `TREBEK_LEFT` (§6.5). Only set when `state` is `CLOSED`; otherwise `null`. |
| `phaseDurationMs` | Full length of the current phase in milliseconds, or `null` if the state has no timer (§6.3), including a phase that waits for Trebek (§6.8) |
| `phaseRemainingMs` | Milliseconds left in the current phase when the server built this response (never negative), or `null` if the state has no timer. While `paused`, the time left when the clock stopped. |
| `paused` | `true` while Trebek has stopped the clock (§6.8): don't count down. Always `false` in a `CONTESTANT` session. |
| `timers` | The session's configured phase durations in milliseconds (`SessionTimers`, §6.3). Always present, so the lobby can show them. |
| `timerModes` | For each timer, `AUTOMATIC` or `TREBEK`: whether its phase runs on its timer or waits for Trebek (`SessionTimerModes`, §6.8). Always present; it only matters in a `TREBEK` session. |
| `hostMode` | `CONTESTANT` (the host plays, the default) \| `TREBEK` (the host runs the show, §6.8) |
| `trebek` | In a `TREBEK` session, the host running it: `{ id, name, away }`, with `id` equal to `hostPlayerId`. Trebek is never in `players`. `null` in a `CONTESTANT` session. |
| `players` | `{ id, name, score, leaving, away }` in join order. `leaving` is `true` for a player who left (or was kicked) but still holds a role and is waiting to be removed, and, from `FINAL_JEOPARDY_REVEALING` on, for everyone who left, since they stay in the results (§6.4); show them as leaving. Removed players aren't listed at all. `away` is `true` while a player has no open connection: from joining until they first connect, and whenever they lose it (a closed tab, a lost network); it turns `false` when they (re)connect (§6.4). |
| `hostPlayerId` | The host's player id (§6.4), or `null` while nobody has joined. Always a player with `leaving: false`, or in a `TREBEK` session `trebek.id`. |
| `allowLateJoin` | Whether players can join after the start. `true` by default; only the host can change it, before the start (§6.6). |
| `joinScore` | The score a player who joins now starts with. Never above 0 (§6.6). |
| `playerChoosingClue` | Whose turn it is to pick. `null` before the game starts; already set during a clue round's `ROUND_INTRO`. Correct answers take control of the board, and Double Jeopardy starts with the lowest score in control (§6.2). Always `null` from Final Jeopardy on, where nobody chooses, and once the game has finished. |
| `playerAnswering` | Who must answer right now. Only set in `WAITING_FOR_ANSWER` (the buzzer, or the chooser on a Daily Double), and in `WAITING_FOR_RULING` for the player whose answer Trebek is ruling on; otherwise `null`. |
| `playersLockedOut` | Players who already buzzed on this clue and can't buzz again. Only filled in `WAITING_FOR_BUZZER`, `WAITING_FOR_ANSWER` and `WAITING_FOR_RULING`; otherwise `[]`. |
| `board` | The current round's grid (§5.1). Never contains questions, answers or Daily Double locations. |
| `currentClue` | The clue in play (§5.2), or `null` |
| `clueAnswers` | Every answer given to the clue in play, or to the last clue played (§5.4). Never `null`; `[]` when there are none. |
| `finalJeopardy` | Final Jeopardy status (§5.3), or `null` |

**Every player id in a response belongs to someone in `players`, or is `trebek.id`** (`hostPlayerId`, `playerChoosingClue`, `playerAnswering`, `playersLockedOut`, `clueAnswers[].playerId`, `finalJeopardy.players[].playerId`, `finalJeopardy.revealOrder`). Only `hostPlayerId` can be `trebek.id`. When a player is removed, every trace of them goes with them (§6.4).

**The snapshot never holds a secret**, in a Trebek session too: a clue Trebek hasn't shown, an answer waiting for Trebek's ruling and Final Jeopardy wagers and answers before their reveal step only go to Trebek, on `/user/queue/trebek` (§6.8).

### 5.1 `board`

A list of categories in column order. Each category has a `name`, its `comments` (the host's note about the category, such as `"(Alex: Each answer is a palindrome.)"`, or `null`), and one slot per row, from 1 up to the highest row used anywhere in the round, so the grid is always rectangular.

| Slot field | Meaning |
|---|---|
| `row` | 1-based row |
| `clueId` | `null` when `EMPTY` |
| `value` | Dollar value (`null` when `EMPTY`). Standard values: Jeopardy $200–$1000, Double Jeopardy $400–$2000. |
| `status` | `AVAILABLE` (can be chosen) \| `PLAYED` (already chosen) \| `EMPTY` (no clue on this row) |

A clue becomes `PLAYED` as soon as it's chosen. Before the game starts, `board` shows the first round. During Final Jeopardy, it shows the Final category's single slot (ignore its value).

### 5.2 `currentClue`

Non-null while an ordinary clue (or Daily Double) is in play, from the moment it's chosen until its answer reveal ends.

| Field | Meaning |
|---|---|
| `clueId`, `value` | The chosen clue |
| `dailyDouble` | `true` for a Daily Double. It's public at this point: `REVEALING_DAILY_DOUBLE` announces it. `null` in `CLUE_PICKED` (§6.8), so the board doesn't give a Daily Double away before Trebek shows it. |
| `wager` | The Daily Double wager once it's placed; otherwise `null` |
| `minWager`, `maxWager` | The lowest and highest wager the chooser can place (§6.2), only in `WAITING_FOR_DAILY_DOUBLE_WAGER`; otherwise `null` (also during the announcement) |
| `question` | Hidden (`null`) in `CLUE_PICKED`, `REVEALING_DAILY_DOUBLE` and while waiting for a Daily Double wager; shown from `REVEALING_CLUE` on (`WAITING_FOR_RULING` included) |
| `clueType` | `TEXT` \| `IMAGE` \| `SOUND` \| `VIDEO`. Hidden exactly like `question`. |
| `mediaUrl` | The image, sound or video to play with the question, `null` for a `TEXT` clue. Hidden exactly like `question`. |
| `numberExpected` | `true` when only an exact number is accepted (§6.7), so you can warn the player that typos won't be forgiven. Hidden exactly like `question`, since it says something about the answer. |
| `answer` | Only in `REVEALING_ANSWER`. Alternatives and lists are written `(N of) A \| B` (§6.7). |
| `acceptedAnswers` | Only in `REVEALING_ANSWER`, with `answer`: the answer ready to show, `{ required, options }`, each option in a single wording (§6.7). |

### 5.3 `finalJeopardy`

Non-null during the Final Jeopardy states, and in `FINISHED_GAME` if the game had a Final round (so the results stay visible).

| Field | Shown from |
|---|---|
| `category` | `FINAL_JEOPARDY_WAGERING` |
| `categoryComments` (the category's comments, or `null` if it has none) | `FINAL_JEOPARDY_WAGERING` |
| `question` | `FINAL_JEOPARDY_ANSWERING` |
| `numberExpected` (only an exact number is accepted, §6.7) | `FINAL_JEOPARDY_ANSWERING` |
| `answer` (the correct response, written as in §6.7) and `acceptedAnswers` (the same, ready to show, as in §5.2) | `FINAL_JEOPARDY_REVEALING` |
| `players[]` → `playerId`, `hasWagered`, `hasAnswered` (only `true` for a player who actually wagered or answered; a missing wager still counts as 0) | always |
| `players[]` → `maxWager` (the most that player can wager: their score, or 0) | only in `FINAL_JEOPARDY_WAGERING`; `null` otherwise |
| `players[]` → `wager`, `answer`, `correct`, `phrasedAsQuestion`, `matchedAnswers`, `alsoAccepted` | During `FINAL_JEOPARDY_REVEALING`, per player as their reveal steps are reached (`revealStep`, below); before the reveal they're all `null`, so nobody can see anyone else's. `phrasedAsQuestion` (§6.7) stays `null` for a player who didn't answer; `matchedAnswers` and `alsoAccepted` (as in §5.4) are only set for a correct answer. |
| `revealOrder` | The player ids in the order they're revealed (§6.2), from `FINAL_JEOPARDY_REVEALING` on (and in `FINISHED_GAME`); `null` before. |
| `players[]` → `revealStep` | From `FINAL_JEOPARDY_REVEALING` on: `HIDDEN` \| `WAGER` \| `ANSWER` \| `AWARDED`, how far this player's reveal has got. From `WAGER` their `wager` is shown, from `ANSWER` also their `answer` and `phrasedAsQuestion`, and once `AWARDED` also `correct` (in a Trebek session, Trebek's ruling), `matchedAnswers` and `alsoAccepted`. `null` before the reveal. |

### 5.4 `clueAnswers`

What players answered on the current ordinary clue or Daily Double, oldest first, so everyone can see each answer and its result. Each entry is `{ playerId, answer, correct, phrasedAsQuestion, matchedAnswers, alsoAccepted, ruledByTrebek }`:

- `answer` is the text the player submitted, or `null` if they ran out of time (which counts as wrong). In a Trebek session it's also `null` when Trebek ruled before an answer came.
- `correct` is the result of the answer check (§6.2), or in a Trebek session Trebek's ruling.
- `ruledByTrebek` is `true` when `correct` is Trebek's call (§6.8), `false` in a `CONTESTANT` session. In a Trebek session an entry only appears once Trebek has ruled, never while the answer waits for the ruling.
- `phrasedAsQuestion` says whether the answer was phrased as a question (§6.7), in every round, also where that isn't required. It's `null` when the player ran out of time.
- `matchedAnswers` (only for a correct answer, otherwise `null`; in a Trebek session only when Trebek ruled it correct **and** the check matched it, so fall back to `acceptedAnswers`): what the answer named, in the order the player named it, each in the wording they matched. For `a linen [closet | cupboard]`, "linen cupbord" gives `["a linen cupboard"]`; for `(2 of) gold | frankincense | myrrh`, "myrrh and gold" gives `["myrrh", "gold"]`. Optional parts stay in, said or not (`["(Abraham) Lincoln"]`).
- `alsoAccepted` (only for a correct answer, otherwise `null`): the options of a list the player didn't name, each in its first wording, for "We would also have accepted ...". It's empty for an answer that isn't a list, since other wordings of the same thing are never offered. A correct answer ends the clue, so neither field shows anything before the reveal.

An entry appears in the broadcast that follows the answer. After a wrong answer, the list stays visible while others buzz and answer (`WAITING_FOR_BUZZER`, `WAITING_FOR_ANSWER`). After the last answer it stays visible in `REVEALING_ANSWER` and then in `CHOOSING_CLUE` (or the next round's `ROUND_INTRO`), until the next clue is chosen. It's cleared when a clue is chosen and when Final Jeopardy starts (Final Jeopardy has its own reveal in §5.3).

The one exception: when a player is removed after leaving (§6.4), their entries are deleted with them.

**Every answer to the clue is kept, not just the latest one.** Clients render from the latest snapshot (§2), so one that missed a broadcast or joined mid-clue still sees that, for example, player 1 answered wrong before player 2 answered right. For "the latest answer", use the last element.

It never contains the stored correct response, which is only sent as `currentClue.answer` in `REVEALING_ANSWER`. A correct answer ends the clue, so it's shown as the response is revealed. A wrong answer, however, is shown to everyone while other players can still buzz, as on the TV show.

---

## 6. States, rules and timings

### 6.1 State machine

```
WAITING_FOR_START
   │ start
   ▼
ROUND_INTRO (6s: the round's title card)
   ▼
CHOOSING_CLUE (30s) ◄────────────────────────────────────────┐
   │ choose-clue (or 30s: a clue is chosen for the chooser)  │
   ├── Daily Double ─► REVEALING_DAILY_DOUBLE (6s)           │
   │                     ▼                                   │
   │                   WAITING_FOR_DAILY_DOUBLE_WAGER        │
   │                     │ place-daily-double-wager (or 30s) │
   ▼                     ▼                                   │
REVEALING_CLUE (8s)                                          │
   ├── Daily Double ─► WAITING_FOR_ANSWER (chooser only)     │
   ▼                                                         │
WAITING_FOR_BUZZER (10s) ──── buzz ───► WAITING_FOR_ANSWER (15s)
   │ nobody buzzes                         │ correct, wrong with nobody left,
   ▼                                       │ Daily Double, or timeout
REVEALING_ANSWER (8s) ◄────────────────────┘
   │                (wrong with players left → back to WAITING_FOR_BUZZER)
   └─► CHOOSING_CLUE, or the next round's ROUND_INTRO (Final Jeopardy's
       included), or FINISHED_GAME ─┘

Final Jeopardy (after its ROUND_INTRO):
FINAL_JEOPARDY_WAGERING (30s) → FINAL_JEOPARDY_ANSWERING (30s)
  → FINAL_JEOPARDY_REVEALING (10s per player, one at a time; each scored as revealed)
  → FINISHED_GAME

Any state ──(session removed, §6.5)──► CLOSED   (only in the very last broadcast)
```

The durations shown are the defaults; each session can change them (§6.3). A Trebek session adds two states, `CLUE_PICKED` and `WAITING_FOR_RULING`, and phases that wait for Trebek (§6.8).

### 6.2 Rules

These are a `CONTESTANT` session's rules. A Trebek session keeps them except where §6.8 says otherwise: nobody is scored by the answer check, the question form is Trebek's call, and each Final Jeopardy award waits for Trebek's ruling.


- **Start:** only the host can start, and the host picks the first clue.
- **Title cards:** every round opens with `ROUND_INTRO` (6 s by default, `roundIntroMs`): the game's first round when the game starts, then each later round, Final Jeopardy included. `round` is already the round being introduced, and so are `board` and, in a clue round, `playerChoosingClue` (`null` before Final Jeopardy). Show a title card for that round ("Jeopardy!", "Double Jeopardy!", "Final Jeopardy!"). Nothing can be played until it ends; then a clue round goes to `CHOOSING_CLUE` and Final Jeopardy to `FINAL_JEOPARDY_WAGERING`. Rounds without clues are skipped, title card included, and a game without Final Jeopardy goes straight to `FINISHED_GAME`. If the chooser left during the title card, control passes when the round begins, as in §6.4.
- **Choosing a clue:** the player in control has 30 s (by default) to choose. If they don't, the server chooses for them: the top-most available clue (lowest row), leftmost category first. It's played exactly as if they had chosen it, so it can be a Daily Double, and they keep control. This keeps the game moving if the chooser is `away`. A chooser who has *left* loses control instead (§6.4).
- **Scoring:** a correct answer adds the clue's value and gives that player control of the board. A wrong answer, or running out of time to answer, subtracts it. Not buzzing costs nothing.
- **Wrong answers:** the other players who haven't buzzed yet can buzz. When everyone has buzzed, the answer is revealed. "Everyone" means every player who hasn't left and isn't `away`: players marked `leaving` can't buzz, and neither they nor `away` players are waited for (an `away` player who reconnects in time can still buzz). If a player leaves while everyone else still in the game has already buzzed, the answer is revealed at once. A player going `away` never ends a phase by itself; it counts from the next answer on.
- **Rounds:** when every clue in a round has been played, the next round starts. Rounds with no clues are skipped. After Double Jeopardy comes Final Jeopardy if the game has one; otherwise the game finishes. As on the show, **Double Jeopardy starts with the lowest score in control** (the lowest id on a tie, among players who haven't left; an `away` player only gets control if everyone is `away`).
- **Daily Double:** choosing one (or the timer choosing it) first announces it: `REVEALING_DAILY_DOUBLE` (6 s by default, `dailyDoubleRevealMs`), with `currentClue.dailyDouble` `true` and the question still hidden. Then the wager starts. Only the player who chose it plays. Their wager must be between 5 and the larger of their score and the round's highest clue value (normally 1000 in Jeopardy and 2000 in Double Jeopardy). On a board whose values are so low that this maximum is under 5, the minimum is the maximum instead. While they wager, `currentClue.minWager` and `currentClue.maxWager` give the range; a wager outside it gets `INVALID_WAGER` with the range in the message, for example `"Your wager must be between 5 and 2000!"`. Not wagering in time means the minimum wager (5). Right or wrong, the wager is added or subtracted, and the chooser keeps control.
- **Final Jeopardy:**
  - Every player wagers between 0 and their score (`finalJeopardy.players[].maxWager` while wagering). A score of 0 or less can only wager 0, but that player still plays. A wager outside the range gets `INVALID_WAGER`, for example `"Your wager must be between 0 and 3400!"`.
  - Wagers and answers can be changed until their phase ends. Phases always run their full time.
  - A missing wager counts as 0, and a missing answer counts as wrong.
  - **The reveal goes one player at a time**, in `finalJeopardy.revealOrder`: lowest score when answering closed first (the lowest id on a tie). The correct response is shown as the reveal starts, with every player `HIDDEN`. Then each player takes three steps, shown in `players[].revealStep`: `WAGER` (everyone sees their wager), `ANSWER` (their answer and `phrasedAsQuestion` too), and `AWARDED` (their wager is added or subtracted, and `correct`, `matchedAnswers` and `alsoAccepted` are shown). After the last player's award, one more step ends the game (`FINISHED_GAME`).
  - The reveal's clock gives each player `finalJeopardyRevealMs` (§6.3), split evenly over their three steps, and the opening step (everyone hidden) and the closing one (before `FINISHED_GAME`) get one share each. With the default 10 s, every step is a phase of 3333 ms with its own `phaseDurationMs`, so two players take 7 × 3333 ms ≈ 23.3 s.
  - **Scores change at each player's award**, not before.
- **Answer checking** is lenient about spelling, wording and order but strict about numbers, and Double and Final Jeopardy need the question form ("What is ...?"). See §6.7.

### 6.3 Timings

Each session has its own phase durations, sent in every response as `timers`. They start at these defaults:

| Phase | `timers` field | Default |
|---|---|---|
| A round's title card (`ROUND_INTRO`) | `roundIntroMs` | 6 s |
| Choosing a clue (`CHOOSING_CLUE`) | `clueChoiceMs` | 30 s |
| Clue reveal (`REVEALING_CLUE`) | `clueRevealMs` | 8 s |
| Daily Double announcement (`REVEALING_DAILY_DOUBLE`) | `dailyDoubleRevealMs` | 6 s |
| Daily Double wager (`WAITING_FOR_DAILY_DOUBLE_WAGER`) | `dailyDoubleWagerMs` | 30 s |
| Buzzer (`WAITING_FOR_BUZZER`) | `buzzerMs` | 10 s |
| Answer (`WAITING_FOR_ANSWER`) | `answerMs` | 15 s |
| Answer reveal (`REVEALING_ANSWER`) | `answerRevealMs` | 8 s |
| Final Jeopardy wagering | `finalJeopardyWagerMs` | 30 s |
| Final Jeopardy answering | `finalJeopardyAnswerMs` | 30 s |
| Final Jeopardy reveal, **per player** (§6.2) | `finalJeopardyRevealMs` | 10 s |

`WAITING_FOR_START`, `FINISHED_GAME` and `CLOSED` have no timer, and neither do a Trebek session's `CLUE_PICKED`, `WAITING_FOR_RULING` and phases whose timer mode is `TREBEK` (§6.8).

**Changing them:**

- Only in the lobby, with the host's `update-timers` action (§4.3), in `WAITING_FOR_START`. It broadcasts the new `timers`. Once the game has started they're fixed (`INVALID_STATE`); anyone but the host gets `NOT_HOST`. Every session starts with the defaults.
- Every field is optional, and a field left out keeps its current value.
- **Bounds:** every duration is a whole number of milliseconds from **1000 (1 s) to 300000 (5 min)**, inclusive. Out of range: `INVALID_REQUEST` with `"<field> must be between 1000 and 300000"` (several invalid fields are joined with `", "`). Nothing changes when any field is invalid.

Every response (broadcast and `GET /api/game-sessions/{id}`) carries the current phase's timing:

- `phaseDurationMs`: the full length of the phase, for example `10000` in `WAITING_FOR_BUZZER`.
- `phaseRemainingMs`: how much of it was left when the server built the response, from `0` up to `phaseDurationMs`. While `paused` (§6.8) it's the time left when the clock stopped, and doesn't go down.
- Both are `null` in a state without a timer.

They're durations, not timestamps, so the client's clock doesn't need to match the server's. To show a countdown, note your own time when the response arrives (`performance.now()`) and count down from `phaseRemainingMs`. It will end late by the network delay of that response, which is usually small. Final Jeopardy wagers and answers don't restart their phase: their broadcasts show the same deadline with less time left. The phase can end a few milliseconds after the countdown reaches 0, when the next broadcast arrives.

### 6.4 Host, kicking and leaving

**Host**

- The first player to join is the host (`hostPlayerId`). Only the host can `start`, `update-timers`, `update-settings` and `kick`; anyone else gets `NOT_HOST`.
- When the host leaves or is kicked, the remaining player with the **lowest id** who hasn't left becomes host at once, in the same broadcast, skipping `away` players unless everyone is `away`, so the players who are there can still start and kick. A host who goes `away` stays host.
- When nobody is left, the session closes (§6.5).
- In a Trebek session the host is Trebek, who isn't in `players`. Trebek leaving works differently (§6.8): during the game it ends the game.

**Kicking.** The host sends `kick` with another player's id. The kicked player is treated **exactly** like a player who leaves (below). Kicking yourself is rejected with `INVALID_REQUEST` (use `leave`). Kicking an id that isn't an active player in the session, including one who has already left, is rejected with `PLAYER_NOT_FOUND`.

**Away.** A closed tab or a dropped connection is not leaving: the player keeps their place, score and role, the timers act for them, and they can reconnect with their token. While a player has no open connection, they're marked `away: true` (in a broadcast of its own), and it turns `false` when they reconnect. A new player is `away` from joining until their first connection, so a player whose tab closed before it connected is never waited for. The buzzer doesn't wait for `away` players (§6.2), and when every player who hasn't left is `away` for 2 minutes during a game, the session is closed (§6.5). An `away` chooser keeps control, but control that passes to someone new (the start of Double Jeopardy, or a chooser leaving) skips `away` players unless everyone is `away`.

**Leaving.** A player leaves only by sending `leave` (or being kicked).

As soon as a player leaves:

- **Their token stops working** and their open connections are closed (§4.1). They can't act any more, even in a role they still hold; an action from a stale connection gets `PLAYER_NOT_IN_SESSION`.
- **The host passes on** if they were the host.
- **Then they're either removed at once or marked `leaving`:**

| When they leave | What happens |
|---|---|
| `WAITING_FOR_START` | Removed at once |
| During the clue rounds, without a role | Removed at once |
| While they're `playerChoosingClue` in a clue round's `ROUND_INTRO` | Stay with `leaving: true` until the title card ends; then control passes as in the next row and they're removed. |
| Final Jeopardy's `ROUND_INTRO` | Removed at once (nobody chooses any more). |
| While they're `playerChoosingClue` in `CHOOSING_CLUE` | Control passes at once to the player with the lowest score (the lowest id on a tie) among those who haven't left, skipping `away` players unless everyone is `away`, with a fresh clue-choice timer, and they're removed. |
| While they're `playerChoosingClue` during a clue (one they chose, a Daily Double and its announcement, or the reveal) | Stay with `leaving: true` until the clue ends. If someone answers correctly, that player takes control as usual; otherwise control passes as in the row above when the reveal ends (or, at the end of a round, to the next round's lowest score, §6.2). Until then the timers wager for them on a Daily Double. |
| While they're `playerAnswering` (the buzzer, or the chooser on a Daily Double) | Stay with `leaving: true` until their answer time runs out, which counts as a wrong answer (or a wrong Daily Double). If they're also the chooser, they stay until control passes, as above. |
| `FINAL_JEOPARDY_WAGERING`, `FINAL_JEOPARDY_ANSWERING` | Removed at once. There are no roles in Final Jeopardy. |
| `FINAL_JEOPARDY_REVEALING`, `FINISHED_GAME` | Stay in `players` with `leaving: true`, so the results still show them. A player who leaves during the reveal is still scored. |

A chooser marked `leaving` when Final Jeopardy starts is removed then; `playerChoosingClue` is `null` from Final Jeopardy on anyway. One still marked `leaving` when a game without Final Jeopardy finishes stays in the results, like everyone else who leaves then.

**Score and answers.** While a player is marked `leaving` they stay in `players` and their score still changes: running out of time on an answer subtracts the clue's value (or their Daily Double wager, which the timer sets to 5). When a player is removed, they disappear from the snapshot entirely: from `players` (their score is gone), from `clueAnswers` (their answers to the current clue are deleted), from `playersLockedOut`, and from `finalJeopardy.players` (any Final Jeopardy wager or answer is discarded; they aren't scored). Nobody is removed from `FINAL_JEOPARDY_REVEALING` on, so the final standings always show everyone who was still playing when Final Jeopardy's answers closed.

**Buzzing.** `playersLockedOut` only lists players who are still in `players`. A player marked `leaving` can't buzz, and "everyone has buzzed" (§6.2) only counts players who haven't left and aren't `away`.

**Rejoining.** Someone who left can join again with `POST /api/game-sessions/{id}/players`, under any name, as a new player (new id, new token, starting at the current `joinScore`, §3.2 and §6.6), unless the host blocked joining after the start.

**The last player.** When the last player who hasn't left leaves (or every remaining player is marked `leaving`), the session is closed at once, even in the lobby (§6.5). The exception is `FINISHED_GAME`: leaving is how players get back to the lobby, so the results stay until the finished-game timeout.

### 6.5 Session cleanup

Sessions live in memory and are removed automatically:

| Session | Removed | `closeReason` |
|---|---|---|
| Every player has left, or every remaining player is marked `leaving`, except in `FINISHED_GAME` (see its row). In a Trebek session's lobby, Trebek counts too: Trebek alone can wait there for players. During its game Trebek doesn't: with no player left, nobody can play. | At once, by the `leave` or `kick` that caused it | `ABANDONED` |
| Trebek leaves during the game (between `start` and `FINISHED_GAME`, §6.8) | At once | `TREBEK_LEFT` |
| Every player who hasn't left is `away` (§6.4), and so is Trebek in a Trebek session, during a game (not in `WAITING_FOR_START` or `FINISHED_GAME`, which have their own timeouts below) | After **2 minutes** with everyone away (counted from when the last one went away; anyone reconnecting, or a new player connecting, stops it). Pausing (§6.8) doesn't stop it. | `ABANDONED` |
| `WAITING_FOR_START` with no change for **30 minutes** (a change is anything that's broadcast: a join, a leave, a kick, new timers). A session nobody joins counts from its creation. | After 30 minutes without a change | `IDLE` |
| `FINISHED_GAME` with no change for **10 minutes** (counted from the end of the game, or from the last player leaving after it) | After 10 minutes without a change | `FINISHED` |

A game in progress is only removed by a timer when everyone is `away`: otherwise every phase is timed and it moves on by itself. A Trebek session's phases that wait for Trebek, or a paused clock, can wait as long as anyone is connected, like a lobby. All sessions are also lost when the backend restarts.

**What clients see:**

1. One last broadcast on `/topic/sessions/{id}`: a normal `GameSessionResponse` with the next `version`, `state: "CLOSED"`, `closeReason` set, `phaseDurationMs`/`phaseRemainingMs` `null`, and `currentClue`/`finalJeopardy` `null`. `players`, their scores and `board` are as they were, so a final scoreboard can still be shown. Show something like "This game has ended" (or "Everyone left").
2. The session is gone: `GET /api/game-sessions/{id}` returns 404, it disappears from `GET /api/game-sessions`, joining returns 404, and any action gets `SESSION_NOT_FOUND`.
3. Every token in it stops working. Open connections stay open (they just receive nothing more), but a reconnect is rejected on CONNECT exactly as after a backend restart.

### 6.6 Joining after the start and the join score

**Late joining.** By default anyone can join in the lobby and during the game, until Final Jeopardy's wagering ends. Before the start, the host can block joining after the start with `update-settings` `{ "allowLateJoin": false }` (and allow it again with `true`). Once the game starts the setting is fixed (`INVALID_STATE`); anyone but the host gets `NOT_HOST`. With late joining blocked, every join after `WAITING_FOR_START` returns `409`, including from someone who left and wants to come back. A player who joined mid-game plays like anyone else, starting with the next clue they can buzz on; in Final Jeopardy they can join while wagering is open, and then wager (0 up to their score) and answer like everyone else. **Nobody can join in `FINAL_JEOPARDY_ANSWERING`, `FINAL_JEOPARDY_REVEALING` or `FINISHED_GAME`**, whatever `allowLateJoin` says: a new player couldn't wager, and then there's nothing left to play. A join returns `409` with the messages in §3.2, and `joinable` is `false`. Anyone can still watch (an anonymous connection, §4.1).

**Join score.** Every player who joins starts with `joinScore`, not with 0. It's there so that leaving and joining again never gives anyone a better score than staying would have. It starts at 0 and follows these rules after every score change:

1. **It goes down with the lowest score.** If any player's score drops below `joinScore`, `joinScore` becomes that score.
2. **It only goes up when the bottom is pushed up.** If a player whose score was at or below `joinScore` scores, and afterwards nobody's score is at or below `joinScore`, it becomes the lowest current score or 0, whichever is lower.
3. **Nothing else changes it.** In particular, when the lowest-scoring player leaves, `joinScore` stays where it was, so they rejoin with the same score. Players marked `leaving` still count until they're removed.

So `joinScore` is never above 0, and never above the lowest score of the players who were there when it last moved. Examples, starting at 0:

| What happens | Scores after | `joinScore` |
|---|---|---|
| Ben answers a $400 clue wrong | Ana 0, Ben −400 | −400 |
| Ben leaves and joins again as "Ben" | Ana 0, Ben −400 | −400 |
| Ben answers a $200 clue right | Ana 0, Ben −200 | −200 (Ben was at the bottom and nobody is at −400 any more) |
| Ana answers a $600 clue right | Ana 600, Ben −200 | −200 (Ana wasn't at the bottom) |
| Ben answers a $1000 clue right | Ana 600, Ben 800 | 0 (lowest is 600, but never above 0) |

With two players tied at the bottom, `joinScore` only rises once both have moved up. Final Jeopardy can't change it: a player at or below `joinScore` (never above 0) can only wager 0.

### 6.7 Answers: format and checking

**The correct response's format** (`currentClue.answer`, `finalJeopardy.answer`). Answers come from J-Archive, cleaned up so that alternatives have a single form:

| Form | Meaning | Example |
|---|---|---|
| `(N of) A \| B \| C` | **Different answers.** A correct response names at least N of the options. `(1 of)` means any one; N equal to the number of options means all of them. | `(1 of) Chile \| Uruguay \| Argentina`, `(2 of) gold \| frankincense \| myrrh` |
| `[A \| B]` | **One thing worded differently** (another spelling, name or synonym). Any wording counts, and they all count as the same option. It can appear anywhere: as a whole answer, inside an option, or around a few words. Brackets don't nest, but can hold optional parts. | `[Myanmar \| Burma]`, `a linen [closet \| cupboard]`, `(2 of) coroner \| [coronet \| coronel]` |
| `(...)` | An optional part. Parentheses can nest. | `(Abraham) Lincoln`, `(duck-bill(ed)) platypus` |
| `A/B` | Either side, except in a fraction like `2/3` | `bear/bare` |

`|` appears nowhere else. **To show the answer, you don't need to parse this:**

- When someone answered correctly, show what they matched (`matchedAnswers`, §5.4), so the player sees their own wording ("a linen cupboard"), and for a list the options they didn't name (`alsoAccepted`: "We would also have accepted Uruguay or Argentina").
- Otherwise show `acceptedAnswers` (`currentClue`, `finalJeopardy`): `{ required, options }`, each option in its first wording (`a linen [closet | cupboard]` is `{ required: 1, options: ["a linen closet"] }`). An answer that isn't a list has one option. For a list the options are different answers, so show them: `(1 of) A | B` as "A or B", and `(2 of) A | B | C` as something like "Any 2 of: A, B, C".
- Never show the other wordings of one thing (`[A | B]`): they're the same answer.

**What counts as correct:**

- **Spelling and wording:** case, accents, punctuation, the articles a/an/the and word order don't matter. An "a" at the end after another word is the letter, not the article, so it counts (`Vitamin A` doesn't accept "Vitamin" or "Vitamin C"). An answer that is only a symbol (`@`) ignores quotes and `?`, `!` or `.` around it. A few typos are allowed per word, by the length of the word in the answer: none up to 3 letters, 1 up to 6, then 2 (swapping two letters counts as 1).
- **Numbers must be exact.** `1,001` and `1001` are the same, and `$` and `%` are optional, but number words aren't numbers (`nine` isn't `9`). Roman numerals are exact too (`Henry VIII` doesn't accept "Henry VII"). Decades accept the short form: `the 1980s` accepts `1980s`, `80s` and `'80s`. When the response is just a number (`1970`, `$25,000`, `2/3`, `50th`), `numberExpected` is `true` on `currentClue` and `finalJeopardy`, so the player can be told it must be exact. Decades don't set it.
- **Optional parts** can be said or left out, word by word. Everything outside them is needed, so the optional part alone isn't enough: `(Abraham) Lincoln` accepts "Lincoln" and "Abraham Lincoln", not "Abraham".
- **Nothing extra:** words that aren't in the answer make a response wrong, even true ones ("the Wright brothers" for `(Orville) Wright`) and short ones ("Washington DC" for `Washington`).
- **Lists:** items can come in any order, separated by commas, "and", "&", "or", or nothing at all. Naming more options than needed is fine; naming anything that isn't one is wrong. Two wordings of one option (`[A | B]`) count as one. For `(2 of) gold | frankincense | myrrh`, "gold and myrrh" and "gold, frankincense, myrrh" are right; "gold" and "gold and silver" are wrong.
- **The question form** is optional in the Jeopardy round and required in Double Jeopardy and Final Jeopardy. It's any of what, who, where, when, which, why or how, followed by is, are, was, were, 's or 're ("What is", "Who's", "whats", "Where were"), whatever kind of answer it is. A few typos of the question word count too ("waht is", "hwo is"). A response without it is wrong there, unless it starts with a wording of the correct response that is itself a question ("Who's the Boss?"), which can then be said as it is; the other options of a list still need the question form. Every answer reports `phrasedAsQuestion` (in `clueAnswers` and, from the reveal, in `finalJeopardy.players`), in every round: `true` when it has the question form, or is a correct response that is itself a question said as it is. In the Jeopardy round, use it to warn a player who left it out that this would be wrong from Double Jeopardy on; in Double and Final Jeopardy, `false` explains a wrong answer. It doesn't say the rest was right: an answer can be wrong both ways.

### 6.8 Hosting as Trebek

In the lobby the host can choose to run the show as **Trebek** instead of playing. Trebek isn't a player: they show each picked clue when they're ready, open and close the buzzers, rule on every answer (the answer check only suggests), change any score at any time, choose which timers run by themselves, and can pause the clock. Everything here applies only when `hostMode` is `TREBEK`; a `CONTESTANT` session (the default) plays exactly as the rest of this document says.

Three principles:

1. **The checker suggests, Trebek decides.** No answer is scored by the answer check (§6.7). Its result goes to Trebek as a suggestion.
2. **A clock never rules.** An automatic timer moves the game on only where no ruling is needed (choosing for the chooser, opening the buzzers, closing typing, locking wagers). Where a ruling is needed, the game waits for Trebek.
3. **Trebek isn't a player.** They have no score, can't buzz, answer or play Final Jeopardy (`NOT_YOUR_TURN`, `"Trebek doesn't play!"`), and aren't counted for "everyone has buzzed", control of the board, the join score, `playerCount` or the final standings.

#### Choosing the mode (lobby)

`update-settings` (§4.3) with `hostMode` and `timerModes`, in `WAITING_FOR_START`, host only:

```json
{ "hostMode": "TREBEK", "timerModes": { "clueRevealMs": "AUTOMATIC" } }
```

- `hostMode: "TREBEK"` **moves the host out of `players`** into `trebek` (`{ id, name, away }`). They keep their player id and token, and `hostPlayerId` stays their id. `hostMode: "CONTESTANT"` puts them back in `players`, in join order.
- `timerModes` is a `Partial<SessionTimerModes>`: for each timer (keyed like `timers`), `AUTOMATIC` (the phase ends when its timer runs out, or sooner with Trebek's `advance`) or `TREBEK` (the phase has no timer and waits for `advance`). Modes left out keep their value. They can be set in either mode but only matter in a Trebek session. Durations (`timers`) are kept whatever the mode.

Defaults:

| Timer | Mode |
|---|---|
| `roundIntroMs`, `dailyDoubleRevealMs`, `clueChoiceMs`, `buzzerMs`, `answerMs`, `finalJeopardyWagerMs`, `finalJeopardyAnswerMs` | `AUTOMATIC` |
| `clueRevealMs`, `dailyDoubleWagerMs`, `answerRevealMs`, `finalJeopardyRevealMs` | `TREBEK` |

**Starting** needs at least one player besides Trebek: otherwise `INVALID_STATE`, `"Nobody is playing yet!"`. The first chooser is the player with the lowest id in `players` (the first to join after Trebek).

#### Trebek's actions

All destinations are prefixed with `/app/sessions/{sessionId}/`. They're **Trebek only**: in a `CONTESTANT` session they get `INVALID_STATE` (`"Only a Trebek host can do that!"`), and from anyone else `NOT_HOST`. Each one that changes something produces a broadcast, like any action.

| Destination | Body | Allowed when | Effect |
|---|---|---|---|
| `advance` | none | See the table below | Ends the current phase now, exactly as if its timer had run out, whether its mode is `AUTOMATIC` or `TREBEK`. It's how Trebek ends a title card or a Daily Double announcement, shows a clue, opens and closes the buzzers, locks wagers and answers, goes back to the board and steps through the Final Jeopardy reveal. |
| `return-clue` | none | `CLUE_PICKED` (otherwise `INVALID_STATE`) | Puts the picked clue back: it's `AVAILABLE` again, the same player keeps control, and `CHOOSING_CLUE` starts again with a fresh timer. |
| `rule` | `{ "correct": boolean }` | `WAITING_FOR_ANSWER`, `WAITING_FOR_RULING` (otherwise `INVALID_STATE`) | Rules on the answering player's answer (below). |
| `rule-final` | `{ "playerId": number, "correct": boolean }` | `FINAL_JEOPARDY_REVEALING`, until that player is `AWARDED` | Rules on one player's Final Jeopardy answer (below). It can be changed until their points are awarded; after that `INVALID_STATE`, `"<name>'s points were already awarded!"`. An id that isn't in the reveal gets `PLAYER_NOT_FOUND`. Earlier, `INVALID_STATE`: answers are only ruled on once answering is over. |
| `adjust-score` | `{ "playerId": number, "amount": number }` | Any state from `start` on (`FINISHED_GAME` included); `INVALID_STATE` in the lobby | Adds `amount` to that player's score (negative takes away). `amount` must be between −100000 and 100000 (`INVALID_REQUEST`, `"amount must be between -100000 and 100000"`) and not 0 (`INVALID_REQUEST`). A player marked `leaving` can be adjusted; an id that isn't in `players` (Trebek's included) gets `PLAYER_NOT_FOUND`. It counts as a score change for `joinScore` (§6.6). |
| `pause` / `resume` | none | Between `start` and `FINISHED_GAME` (otherwise `INVALID_STATE`) | Stops or restarts the clock (below). Pausing when paused, or resuming when not, does nothing: no error and no broadcast. |
| `choose-clue` | `{ "clueId": number }` | `CHOOSING_CLUE` | Trebek chooses **for** `playerChoosingClue` (a clue they named out loud); they keep control, as when the timer chooses. If the chooser's own choice arrives after it, it gets `TOO_LATE`, `"Trebek chose a clue for you!"`. |
| `place-daily-double-wager` | `{ "wager": number }` | `WAITING_FOR_DAILY_DOUBLE_WAGER` | Trebek places the chooser's wager (one said out loud), with the chooser's range and `INVALID_WAGER`. |

Trebek's `kick` and `leave` work as for any host (leaving: below).

#### The state machine

Two more states: **`CLUE_PICKED`** (a clue was chosen, and Trebek hasn't shown it) and **`WAITING_FOR_RULING`** (the answer is in, or typing closed, and Trebek hasn't ruled). Neither ever has a timer.

```
CHOOSING_CLUE ◄──────────────────────────────────────────────────────┐
   │ choose-clue (the chooser, or Trebek for them), advance or timer │
   ▼                                                                 │
CLUE_PICKED (waits for Trebek) ── return-clue ──► CHOOSING_CLUE      │
   │ advance                                                         │
   ├── Daily Double ─► REVEALING_DAILY_DOUBLE ─► WAITING_FOR_DAILY_DOUBLE_WAGER
   │                     │ wager (the chooser or Trebek), advance or timer (minimum wager)
   ▼                     ▼                                           │
REVEALING_CLUE                                                       │
   ├── Daily Double ─► WAITING_FOR_ANSWER (chooser only)             │
   │ advance or timer                                                │
   ▼                                                                 │
WAITING_FOR_BUZZER ── buzz ──► WAITING_FOR_ANSWER                    │
   │ advance or timer:            │ answer, advance or timer (typing closes)
   │ nobody buzzed                ▼                                  │
   │                         WAITING_FOR_RULING (waits for Trebek)   │
   │                              │ rule (also allowed in WAITING_FOR_ANSWER)
   ▼                              ▼                                  │
REVEALING_ANSWER ◄──── correct, wrong with nobody left, Daily Double │
   │ advance or timer     (wrong with players left → WAITING_FOR_BUZZER)
   └─► CHOOSING_CLUE, next round, Final Jeopardy or FINISHED_GAME ───┘
```

A phase whose timer mode is `TREBEK` waits for `advance`, and its `phaseDurationMs` and `phaseRemainingMs` are `null`. One whose mode is `AUTOMATIC` runs on its timer as in §6.3.

`advance` in each state:

| State | `advance` does |
|---|---|
| `ROUND_INTRO` | Ends the title card: the round begins |
| `CHOOSING_CLUE` | Chooses for the chooser, as the timer does (§6.2) |
| `CLUE_PICKED` | Shows the clue (`REVEALING_CLUE`), or announces a Daily Double (`REVEALING_DAILY_DOUBLE`) |
| `REVEALING_DAILY_DOUBLE` | Starts the wager |
| `WAITING_FOR_DAILY_DOUBLE_WAGER` | Locks the minimum wager, as the timer does |
| `REVEALING_CLUE` | Opens the buzzers (on a Daily Double, the chooser answers) |
| `WAITING_FOR_BUZZER` | Closes them: nobody else buzzed, the answer is revealed |
| `WAITING_FOR_ANSWER` | Closes typing: `WAITING_FOR_RULING` with no answer |
| `WAITING_FOR_RULING` | Nothing: `INVALID_STATE`, `"Rule on the answer first!"` |
| `REVEALING_ANSWER` | Back to the board, or on to the next round, Final Jeopardy or the end |
| `FINAL_JEOPARDY_WAGERING`, `FINAL_JEOPARDY_ANSWERING` | Locks the wagers / the answers |
| `FINAL_JEOPARDY_REVEALING` | The next reveal step (below); before the award of a player Trebek hasn't ruled on, `INVALID_STATE`, `"Rule on <name>'s answer first!"` |
| `WAITING_FOR_START`, `FINISHED_GAME`, `CLOSED` | Nothing: `INVALID_STATE` |

#### A picked clue

In `CLUE_PICKED` everyone sees which clue was picked (`currentClue.clueId` and `value`, and its slot is `PLAYED`), but nothing else: `question`, `clueType`, `mediaUrl` and `numberExpected` are hidden as while a Daily Double wager is placed, and `currentClue.dailyDouble` is `null`, so the board doesn't give a Daily Double away. Trebek sees the whole clue on `/user/queue/trebek`.

#### Answers and rulings

- **Buzzing doesn't change**: a buzz goes straight to `WAITING_FOR_ANSWER`.
- **An answer waits for the ruling, unscored and private.** The player's `answer` moves the game to `WAITING_FOR_RULING` (`playerAnswering` stays set) and goes only to Trebek, so Trebek sees it once it's submitted, never while it's typed. The same happens with no answer when the answer time runs out (`AUTOMATIC`) or Trebek closes typing with `advance`; an `answer` sent after that gets `TOO_LATE`.
- **Trebek can rule early**, in `WAITING_FOR_ANSWER`, before anything is submitted (an answer given out loud). The player's typing then closes: an `answer` sent after it gets `TOO_LATE`, `"Trebek already ruled on your answer!"`.
- **A ruling scores like the check does in a `CONTESTANT` session** (§6.2): correct adds the value (or the Daily Double wager) and gives control; wrong subtracts it and lets the others buzz, or reveals the answer when nobody is left (or on a Daily Double).
- **`clueAnswers` gets the entry when Trebek rules** (§5.4): `correct` is the ruling and `ruledByTrebek` is `true`; `answer` is what was submitted, or `null` when there was none; `phrasedAsQuestion` is the check's, or `null` without an answer; `matchedAnswers` and `alsoAccepted` are set only when the ruling is correct **and** the check matched the answer (without the question form, which is Trebek's call), otherwise `null`: show `acceptedAnswers` then.
- **Phrasing is up to Trebek too.** Double Jeopardy's question form isn't applied. The suggestion Trebek sees (`suggestedCorrect`) does apply it, so it's what a `CONTESTANT` session would have scored.
- A ruling can't be taken back: Trebek fixes a mistake with `adjust-score`.

#### Final Jeopardy

**Wagering and answering** work as in §6.2, each on its own timer mode; a `TREBEK` phase stays open until Trebek's `advance`. Trebek sees every wager and answer as they come in. When wagering closes, a wager above its player's maximum (only possible after `adjust-score` lowered their score) is lowered to it.

**Rulings.** Once answering is over (`FINAL_JEOPARDY_REVEALING`), Trebek rules on each player with `rule-final`, and can change a ruling until that player's points are awarded. Nothing is decided automatically: a missing answer is only a suggestion of wrong.

**The reveal goes one player at a time**, with the same order and steps as in §6.2 (`WAGER`, `ANSWER`, `AWARDED`), except that the award applies Trebek's ruling and needs one.

Each `advance` takes one step; after the last player's award, the next `advance` ends the game (`FINISHED_GAME`). With `finalJeopardyRevealMs` set to `AUTOMATIC`, the clock takes the steps instead, timed as in §6.2, but **at an award step it waits** (`phaseDurationMs` `null`) until Trebek rules on that player; the award then happens at once and the clock goes on.

#### Pausing the clock

- `pause` stops the clock of the current phase; `resume` restarts it with the time that was left.
- **A pause holds across phases**: a phase that starts while paused starts with its full time and doesn't count down until `resume`.
- **Actions still work while paused**: players can choose, buzz, answer and wager, and Trebek can `advance` and rule. Only the clock stops.
- `paused` is `true` meanwhile, and `phaseRemainingMs` is the frozen time left, so a countdown shows where it stopped. Don't count down while `paused`.
- The "everyone is away for 2 minutes" cleanup (§6.5) isn't paused. When the game finishes, `paused` goes back to `false`.

#### Trebek away or leaving

- **Away.** The game goes on: automatic phases run on their clocks, and Trebek's phases wait. `trebek.away` shows it. Trebek counts in "every player is `away`" (§6.5): a session where Trebek and every player are away is closed after 2 minutes.
- **Leaving in the lobby.** The host passes on as in §6.4, and the session goes back to `CONTESTANT` mode (`trebek` `null`): the new host is a contestant, and can choose Trebek again. If nobody is left, the session closes.
- **Leaving during the game** (from `start` until `FINISHED_GAME`) **ends the game**: the session closes at once with `closeReason: "TREBEK_LEFT"` (§6.5). The last broadcast still shows Trebek and the scores.
- **Leaving after the game** (`FINISHED_GAME`): the results stay; the host passes to a player as in §6.4 and the session is a `CONTESTANT` one again.
- **The last player leaving** while Trebek stays: in the lobby the session stays open for Trebek to wait for players; during the game it closes (`ABANDONED`), since nobody can play.

#### Trebek's private view: `/user/queue/trebek`

The snapshot is public and never holds Trebek's secrets. They go to `/user/queue/trebek`, only to Trebek's connections: once as soon as Trebek subscribes (so a reconnect or refresh catches up), and then with every broadcast of the session. Match it to the snapshot by `version`. Anyone else who subscribes receives nothing, and `GET /api/game-sessions/{id}` never includes it.

```json
{
  "version": 412,
  "clue": {
    "clueId": 1088, "value": 800, "dailyDouble": false,
    "categoryName": "World capitals",
    "question": "This capital sits on the Danube between Vienna and Budapest",
    "clueType": "TEXT", "mediaUrl": null, "numberExpected": false,
    "answer": "[Bratislava | Pressburg | Pozsony]",
    "acceptedAnswers": { "required": 1, "options": ["Bratislava"] }
  },
  "pendingAnswer": {
    "playerId": 2, "answer": "What is Bratislava?",
    "suggestedCorrect": true, "phrasedAsQuestion": true, "matchedAnswers": ["Bratislava"]
  },
  "finalJeopardy": null
}
```

| Field | Set |
|---|---|
| `version` | The snapshot version this view goes with |
| `clue` | From `CLUE_PICKED` until the clue's answer reveal ends; `null` otherwise. The whole clue, including the correct response (written as in §6.7, and ready to show as `acceptedAnswers`). |
| `pendingAnswer` | In `WAITING_FOR_RULING`: what the answering player submitted (`answer` `null` if nothing), with the check's result: `suggestedCorrect` is what a `CONTESTANT` session would have scored (Double Jeopardy's question form included), `phrasedAsQuestion` (`null` without an answer) and `matchedAnswers` (only when `suggestedCorrect`). `null` in every other state, also while the player is still typing. |
| `finalJeopardy` | From `FINAL_JEOPARDY_WAGERING` until the reveal ends (`null` otherwise): `{ question, answer, acceptedAnswers, numberExpected, players }`. Each player has `playerId`; `wager` (`null` while wagering until they wager; once wagering closes a missing wager is `0`); `answer` (`null` if none); `suggestedCorrect` (`null` until they answer or answering closes; no answer suggests wrong); `phrasedAsQuestion`; `matchedAnswers`; and `ruling` (`true`, `false`, or `null` before Trebek rules). |

---

## 7. Errors (`/user/queue/errors`)

`{ "code": GameErrorCode, "message": string }`. The message is human-readable and can be shown to the player.

| Code | Typical cause |
|---|---|
| `UNAUTHENTICATED` | Acting on a connection without a player token |
| `PLAYER_NOT_IN_SESSION` | Your token belongs to a different session (also an earlier, closed session that had the same code), or you have left the session (or were kicked) and are acting from a connection that hasn't been closed yet |
| `SESSION_NOT_FOUND` | The session no longer exists (§6.5) |
| `INVALID_STATE` | The action isn't allowed in the current state (for example, buzzing before the clue is revealed, or changing timers after the start), or a Trebek action in a `CONTESTANT` session (§6.8) |
| `TOO_LATE` | You were a moment too late: someone else buzzed first (`"Someone else buzzed first!"`), your answer had already timed out (`"Time's up!"`), the clue-choice timer had already chosen a clue for you (`"Time's up: a clue was chosen for you!"`), or in a Trebek session Trebek had already chosen for you (`"Trebek chose a clue for you!"`) or ruled on your answer (`"Trebek already ruled on your answer!"`). Nothing went wrong; the next broadcast shows what happened |
| `NOT_YOUR_TURN` | Another player is choosing or answering, or you already buzzed on this clue; also Trebek trying to play (§6.8) |
| `NOT_HOST` | Only the host can do that: `start`, `update-timers`, `update-settings` or `kick` (§6.4); in a Trebek session also Trebek's actions: `advance`, `return-clue`, `rule`, `rule-final`, `adjust-score`, `pause` and `resume` (§6.8) |
| `PLAYER_NOT_FOUND` | The player you tried to kick isn't in the session or has already left; the player of an `adjust-score` or `rule-final` isn't in the session (§6.8) |
| `CLUE_NOT_FOUND` | That clue isn't on this round's board |
| `CLUE_ALREADY_PLAYED` | That clue was already chosen |
| `INVALID_WAGER` | The wager is outside the allowed range (§6.2) |
| `INVALID_REQUEST` | The payload is missing a field, has a field of the wrong type, isn't JSON, or has a too-long answer (§4.3), or a timer is out of bounds (§6.3); also kicking yourself, an `update-settings` without any setting, an `adjust-score` amount of 0 or out of range (§6.8), and a destination whose session code isn't valid (§3.2) |
| `INTERNAL_ERROR` | A bug on the server. The message is always `"Something went wrong on the server. Please try again."`, and the action may or may not have taken effect: rely on the next broadcast or a `GET` of the state |

Timing races are normal: two players buzzing at the same moment means one wins and the other gets `TOO_LATE`, and so does an answer or a clue choice sent just after its timer ran out. Show `TOO_LATE` gently (or not at all); it's never a client bug. Other late actions, and `INVALID_STATE` in general, are non-fatal too.

---

## 8. TypeScript types

```ts
// ---------- shared enums ----------
type GameRound = "JEOPARDY" | "DOUBLE_JEOPARDY" | "FINAL_JEOPARDY";
type ClueType = "TEXT" | "IMAGE" | "SOUND" | "VIDEO";

type GameSessionState =
  | "WAITING_FOR_START"
  | "ROUND_INTRO"          // the title card of `round`, before each round (§6.2)
  | "CHOOSING_CLUE"
  | "CLUE_PICKED"          // Trebek sessions: a clue was chosen, Trebek hasn't shown it (§6.8)
  | "REVEALING_DAILY_DOUBLE" // a Daily Double is announced, before the wager (§6.2)
  | "WAITING_FOR_DAILY_DOUBLE_WAGER"
  | "REVEALING_CLUE"
  | "WAITING_FOR_BUZZER"
  | "WAITING_FOR_ANSWER"
  | "WAITING_FOR_RULING"   // Trebek sessions: the answer is in, Trebek hasn't ruled (§6.8)
  | "REVEALING_ANSWER"
  | "FINAL_JEOPARDY_WAGERING"
  | "FINAL_JEOPARDY_ANSWERING"
  | "FINAL_JEOPARDY_REVEALING"
  | "FINISHED_GAME"
  | "CLOSED";              // only in a removed session's last broadcast (§6.5)

type SessionCloseReason = "ABANDONED" | "IDLE" | "FINISHED" | "TREBEK_LEFT";

type HostMode = "CONTESTANT" | "TREBEK";  // TREBEK: the host runs the show (§6.8)
type TimerMode = "AUTOMATIC" | "TREBEK";  // TREBEK: the phase waits for Trebek's advance

type GameErrorCode =
  | "SESSION_NOT_FOUND"
  | "NOT_YOUR_TURN"
  | "INVALID_STATE"
  | "TOO_LATE"
  | "CLUE_NOT_FOUND"
  | "CLUE_ALREADY_PLAYED"
  | "PLAYER_NOT_IN_SESSION"
  | "INVALID_WAGER"
  | "INVALID_REQUEST"
  | "UNAUTHENTICATED"
  | "NOT_HOST"
  | "PLAYER_NOT_FOUND"
  | "INTERNAL_ERROR";

// ---------- REST: browsing ----------
type ApiErrorCode =
  | "INVALID_REQUEST"
  | "GAME_NOT_FOUND"
  | "CLUE_NOT_FOUND"
  | "UNPLAYABLE_GAME"
  | "INVALID_CUSTOM_GAME"
  | "SESSION_ALREADY_EXISTS"
  | "SESSION_NOT_JOINABLE";

interface ApiErrorResponse {
  status: number;        // the HTTP status
  code: ApiErrorCode | GameErrorCode;   // only SESSION_NOT_FOUND of GameErrorCode today (§3)
  message: string;
}

// 422 INVALID_CUSTOM_GAME (§3.2)
interface InvalidCustomGameResponse extends ApiErrorResponse {
  code: "INVALID_CUSTOM_GAME";
  problems: CustomGameProblem[];   // every problem, in board order
}

interface CustomGameProblem {
  code: CustomGameProblemCode;
  round: GameRound | null;   // null: the whole show
  column: number | null;     // 1–6; null: the whole round, or Final Jeopardy
  row: number | null;        // 1–5; null: the whole category
  message: string;           // English, ready to show
}

type CustomGameProblemCode =
  | "NO_CLUES"
  | "SHOW_NAME_MISSING"
  | "SHOW_NAME_TOO_LONG"
  | "CATEGORY_NAME_MISSING"
  | "CATEGORY_NAME_TOO_LONG"
  | "FINAL_CLUE_MISSING"
  | "QUESTION_MISSING"
  | "QUESTION_TOO_LONG"
  | "ANSWER_MISSING"
  | "ANSWER_TOO_LONG"
  | "ANSWER_UNREADABLE"
  | "MEDIA_URL_MISSING"
  | "MEDIA_URL_INVALID"
  | "MEDIA_URL_NOT_ALLOWED"
  | "TOO_MANY_DAILY_DOUBLES";

// Query params of GET /api/games (all optional)
interface GameListParams {
  page?: number;         // default 0
  size?: number;         // default 20, max 100
  season?: string;
  title?: string;        // case-insensitive substring
  from?: string;         // "YYYY-MM-DD", inclusive
  to?: string;           // "YYYY-MM-DD", inclusive
  playable?: boolean;    // true: only games with clues (one can still be UNPLAYABLE_GAME); false: only games without
  finalCategory?: string; // case-insensitive substring of the Final Jeopardy category name
  sort?: GameSort;       // default "gameDate"
  direction?: SortDirection;  // default "desc" for "gameDate", otherwise "asc"
}

type SortDirection = "asc" | "desc";
// "season": numbered seasons as numbers, then specials by name (specials last in both directions)
type GameSort = "title" | "season" | "gameDate" | "clueCount";

interface PageResponse<T> {
  content: T[];
  page: number;          // 0-based
  size: number;
  totalElements: number;
  totalPages: number;
}

interface GameSummaryResponse {
  id: number;
  title: string;
  gameDate: string;      // "YYYY-MM-DD"
  season: string;
  clueCount: number;     // 0: no clues, can't be played
  finalCategory: string | null;  // the Final Jeopardy category's name; null without a Final Jeopardy clue
}

interface SeasonResponse {
  season: string;        // usually a number ("42"), sometimes a special ("jm")
  gameCount: number;
}

interface GameClueResponse {
  id: number;
  question: string;
  answer: string;
  clueType: ClueType;
  mediaUrl: string | null;
  rowNumber: number;
}

interface GameCategoryResponse {
  id: number;
  name: string;
  comments: string | null;
  columnNumber: number;  // 1–6 Jeopardy, 7–12 Double Jeopardy, 13 Final
  clues: GameClueResponse[];
}

interface GameResponse {
  id: number;
  title: string;
  gameDate: string;
  season: string;
  jeopardyRound: GameCategoryResponse[];
  doubleJeopardyRound: GameCategoryResponse[];
  finalJeopardy: GameCategoryResponse | null;
  dailyDoubleClueIds: number[];  // sorted by clue id
}

interface ClueResponse {
  id: number;
  question: string;
  answer: string;
  clueType: ClueType;
  mediaUrl: string | null;
  value: number;
  isFinalClue: boolean;
  gameId: number;
  categoryId: number;
  categoryName: string;
  categoryComments: string | null;
}

// ---------- REST: sessions ----------
// Phase durations in milliseconds, each 1000–300000 (§6.3).
interface SessionTimers {
  clueChoiceMs: number;
  clueRevealMs: number;
  dailyDoubleWagerMs: number;
  buzzerMs: number;
  answerMs: number;
  answerRevealMs: number;
  finalJeopardyWagerMs: number;
  finalJeopardyAnswerMs: number;
  finalJeopardyRevealMs: number;  // per player (§6.2)
  roundIntroMs: number;           // each round's title card
  dailyDoubleRevealMs: number;    // a Daily Double's announcement
}

// Which phases wait for Trebek, keyed like SessionTimers (§6.8)
type SessionTimerModes = { [K in keyof SessionTimers]: TimerMode };

// A session's id: 5 characters of "0123456789ABCDEFGHJKMNPQRSTVWXYZ", e.g. "7KQ2M" (§3.2)
type SessionCode = string;

// PUT /api/game-sessions/{code}?session={code}, with a code the client picked at random;
// on 409, pick another and try again (§3.2). Exactly one of the two; timers and settings
// are set in the lobby.
type CreateGameSessionRequest =
  | { gameId: number; customGame?: never }
  | { customGame: CustomGameRequest; gameId?: never };
interface CreateGameSessionResponse { id: SessionCode; }

// A custom show (§3.2). Lengths are code points ([...text].length).
interface CustomGameRequest {
  name: string;                                 // 1–100 characters after trimming
  jeopardyRound: CustomCategoryRequest[];       // up to 6
  doubleJeopardyRound: CustomCategoryRequest[]; // up to 6
  finalJeopardy: CustomFinalJeopardyRequest | null;
}

interface CustomCategoryRequest {
  column: number;                   // 1–6, unique in the round
  name: string | null;              // null for a category that was never named; 1–100 characters
  comments: string | null;          // kept from an imported category
  sourceCategoryId: number | null;  // the stored category it was imported from (information only)
  clues: CustomClueRequest[];       // up to 5; empty rows are left out
}

interface CustomClueRequest {
  row: number;                      // 1–5; the value follows the row
  question: string;                 // as written, tags included; 1–1000 characters
  answer: string;                   // §6.7 format, 1–500 characters
  clueType: ClueType;
  mediaUrl: string | null;          // null for TEXT; otherwise http(s), any host, at most 2000 characters
  dailyDouble: boolean;             // at most 1 in Jeopardy, 2 in Double Jeopardy
}

interface CustomFinalJeopardyRequest {  // all blank is the same as null
  name: string | null;
  comments: string | null;
  question: string | null;
  answer: string | null;
}

// Query params of GET /api/game-sessions (all optional)
interface GameSessionListParams {
  page?: number;              // default 0
  size?: number;              // default 20, max 100
  includeFinished?: boolean;  // default false; stage "FINISHED" implies true
  search?: string;            // case-insensitive substring of gameTitle or hostName
  stage?: SessionStage;
  joinable?: boolean;         // true: only joinable sessions; false doesn't filter
  sort?: GameSessionSort;     // default "createdAt"
  direction?: SortDirection;  // default "desc" for "createdAt", otherwise "asc"; nulls always last
}

// Sorts in this order. Not a response field: LOBBY is WAITING_FOR_START, FINISHED is
// FINISHED_GAME, and the others are a game in progress in that round.
type SessionStage = "LOBBY" | "JEOPARDY" | "DOUBLE_JEOPARDY" | "FINAL_JEOPARDY" | "FINISHED";

type GameSessionSort =
  | "id"
  | "gameTitle"     // case-insensitive
  | "gameSeason"    // like GameSort "season"
  | "gameDate"
  | "hostName"      // case-insensitive
  | "playerCount"
  | "stage"
  | "createdAt";

// GET /api/game-sessions returns PageResponse<GameSessionSummaryResponse>
interface GameSessionSummaryResponse {
  id: SessionCode;
  gameId: number | null;      // null for a custom show
  gameTitle: string | null;   // a custom show's name
  gameSeason: string | null;  // null when gameId is
  gameDate: string | null;    // "YYYY-MM-DD", null when gameId is
  custom: boolean;            // a custom show: gameId, gameSeason and gameDate are null
  clueCount: number;          // clues on the board, all rounds and Final Jeopardy
  state: GameSessionState;
  round: GameRound;
  playerCount: number;        // players who haven't left; never Trebek
  hostName: string | null;    // null before anyone joins; Trebek's name in a Trebek session
  hostMode: HostMode;         // TREBEK: mark it as a Trebek game
  createdAt: string;          // ISO-8601 UTC, e.g. "2026-10-09T12:30:00Z"
  joinable: boolean;          // a join would be accepted right now
  allowLateJoin: boolean;
  joinScore: number;          // a new player's starting score, never above 0
}

interface JoinSessionRequest { playerName: string; }  // 1–30 characters, not blank, after trimming spaces and invisible characters
interface JoinSessionResponse {
  playerId: number;      // 1, 2, 3... within the session
  token: string;         // secret: only for the CONNECT header
}

// ---------- WebSocket: actions ----------
interface ChooseClueRequest { clueId: number; }
interface AnswerClueRequest { answer: string; }  // at most 200 characters ([...answer].length)
interface PlaceDailyDoubleWagerRequest { wager: number; }
interface PlaceFinalJeopardyWagerRequest { wager: number; }
interface AnswerFinalJeopardyRequest { answer: string; }  // at most 200 characters ([...answer].length)
type UpdateTimersRequest = Partial<SessionTimers>;  // host only, WAITING_FOR_START
interface KickPlayerRequest { playerId: number; }  // host only
interface UpdateSettingsRequest {  // host only, WAITING_FOR_START; at least one field
  allowLateJoin?: boolean;
  hostMode?: HostMode;
  timerModes?: Partial<SessionTimerModes>;
}
// start, buzz and leave have no body

// Trebek only (§6.8); advance, return-clue, pause and resume have no body
interface RuleRequest { correct: boolean; }
interface RuleFinalRequest { playerId: number; correct: boolean; }
interface AdjustScoreRequest { playerId: number; amount: number; }  // non-zero, -100000 to 100000

// ---------- WebSocket: state ----------
interface PlayerResponse {
  id: number;
  name: string;
  score: number;
  leaving: boolean;      // left but still holds a role, or left once the results are shown (§6.4)
  away: boolean;         // no open connection now, including before the first (§6.4)
}

// The host running a Trebek session (§6.8); never in players
interface TrebekResponse {
  id: number;            // also hostPlayerId
  name: string;
  away: boolean;
}

type ClueSlotStatus = "AVAILABLE" | "PLAYED" | "EMPTY";

interface ClueSlotResponse {
  row: number;
  clueId: number | null;
  value: number | null;
  status: ClueSlotStatus;
}

interface BoardCategoryResponse {
  name: string;
  comments: string | null;
  slots: ClueSlotResponse[];
}

interface CurrentClueResponse {
  clueId: number;
  value: number;
  dailyDouble: boolean | null;   // null in CLUE_PICKED (§6.8)
  wager: number | null;
  minWager: number | null;     // the chooser's allowed range, only while wagering (§6.2)
  maxWager: number | null;
  question: string | null;
  clueType: ClueType | null;   // null while the question is hidden
  mediaUrl: string | null;     // null while hidden, and always for TEXT
  numberExpected: boolean | null;  // only an exact number is accepted; null while hidden
  answer: string | null;       // "(N of) A | B" lists, "[A | B]" wordings (§6.7)
  acceptedAnswers: AcceptedAnswersResponse | null;  // with answer: ready to show (§6.7)
}

interface AcceptedAnswersResponse {
  required: number;    // a correct answer names this many of the options
  options: string[];   // each in a single wording; one option unless it's a list
}

interface ClueAnswerResponse {
  playerId: number;
  answer: string | null;    // null: ran out of time (or Trebek ruled before an answer came)
  correct: boolean;         // the check's result, or Trebek's ruling
  phrasedAsQuestion: boolean | null;  // set in every round; null: ran out of time
  matchedAnswers: string[] | null;    // correct only: what it named, in the wording matched
  alsoAccepted: string[] | null;      // correct only: a list's other options; [] if not a list
  ruledByTrebek: boolean;   // correct is Trebek's call (§6.8); false in a CONTESTANT session
}

type FinalRevealStep = "HIDDEN" | "WAGER" | "ANSWER" | "AWARDED";

interface FinalJeopardyPlayerResponse {
  playerId: number;
  hasWagered: boolean;     // placed a wager; a missing one counts as 0
  hasAnswered: boolean;
  maxWager: number | null;  // the most they can wager, only in FINAL_JEOPARDY_WAGERING
  wager: number | null;     // revealed from revealStep WAGER
  answer: string | null;    // revealed from revealStep ANSWER
  correct: boolean | null;  // revealed at revealStep AWARDED
  phrasedAsQuestion: boolean | null;  // revealed from revealStep ANSWER; null without an answer
  matchedAnswers: string[] | null;    // revealed at AWARDED, correct only (as in ClueAnswerResponse)
  alsoAccepted: string[] | null;      // revealed at AWARDED, correct only (as in ClueAnswerResponse)
  revealStep: FinalRevealStep | null; // from FINAL_JEOPARDY_REVEALING (§6.2)
}

interface FinalJeopardyResponse {
  category: string;
  categoryComments: string | null;
  question: string | null;
  numberExpected: boolean | null;  // only an exact number is accepted; null while wagering
  answer: string | null;           // "(N of) A | B" lists, "[A | B]" wordings (§6.7)
  acceptedAnswers: AcceptedAnswersResponse | null;  // with answer: ready to show (§6.7)
  players: FinalJeopardyPlayerResponse[];
  revealOrder: number[] | null;    // from FINAL_JEOPARDY_REVEALING (§6.2)
}

interface GameSessionResponse {
  id: SessionCode;
  version: number;       // keep the highest one seen
  round: GameRound;
  state: GameSessionState;
  closeReason: SessionCloseReason | null;  // set only when state is "CLOSED"
  phaseDurationMs: number | null;   // null: no timer in this state
  phaseRemainingMs: number | null;  // measured when the server built the response; frozen while paused
  paused: boolean;                  // Trebek stopped the clock: don't count down; false in CONTESTANT mode
  timers: SessionTimers;            // this session's configured durations
  timerModes: SessionTimerModes;    // which phases wait for Trebek (§6.8)
  players: PlayerResponse[];        // never Trebek
  hostPlayerId: number | null;      // null only before anyone joins; trebek.id in TREBEK mode
  hostMode: HostMode;
  trebek: TrebekResponse | null;    // null in CONTESTANT mode
  allowLateJoin: boolean;           // joining after the start is allowed
  joinScore: number;                // a new player's starting score, never above 0
  playerChoosingClue: number | null;
  playerAnswering: number | null;
  playersLockedOut: number[];
  board: BoardCategoryResponse[];
  currentClue: CurrentClueResponse | null;
  clueAnswers: ClueAnswerResponse[];  // [] when nobody has answered (§5.4)
  finalJeopardy: FinalJeopardyResponse | null;
}

interface GameSessionErrorResponse {
  code: GameErrorCode;
  message: string;
}

// ---------- WebSocket: Trebek's private view (/user/queue/trebek, §6.8) ----------
interface TrebekViewResponse {
  version: number;                 // the snapshot version it goes with
  clue: TrebekClueResponse | null;
  pendingAnswer: TrebekPendingAnswerResponse | null;
  finalJeopardy: TrebekFinalJeopardyResponse | null;
}

interface TrebekClueResponse {
  clueId: number;
  value: number;
  dailyDouble: boolean;
  categoryName: string;
  question: string;
  clueType: ClueType;
  mediaUrl: string | null;
  numberExpected: boolean;
  answer: string;                  // "(N of) A | B" lists, "[A | B]" wordings (§6.7)
  acceptedAnswers: AcceptedAnswersResponse;
}

interface TrebekPendingAnswerResponse {
  playerId: number;
  answer: string | null;           // null: nothing was submitted
  suggestedCorrect: boolean;       // what a CONTESTANT session would have scored
  phrasedAsQuestion: boolean | null;
  matchedAnswers: string[] | null; // only when suggestedCorrect
}

interface TrebekFinalJeopardyResponse {
  question: string;
  answer: string;
  acceptedAnswers: AcceptedAnswersResponse;
  numberExpected: boolean;
  players: TrebekFinalPlayerResponse[];
}

interface TrebekFinalPlayerResponse {
  playerId: number;
  wager: number | null;               // null while wagering until they wager; then 0 if they didn't
  answer: string | null;
  suggestedCorrect: boolean | null;   // null until there's an answer or answering closes
  phrasedAsQuestion: boolean | null;
  matchedAnswers: string[] | null;
  ruling: boolean | null;             // null: not ruled yet
}
```

---

## 9. Known limitations

These are tracked on the backend roadmap. Work around them in the UI only where noted.

- **No winner or tie field.** At `FINISHED_GAME`, work out the winner from the final `players[].score`.
- **Answer checking doesn't know synonyms** (§6.7): abbreviations aren't expanded ("Avenue" isn't `Ave.`), and number words aren't numbers.
- **Sessions are lost when the backend restarts**, along with every token. Clients see this the same way as a removed session (§6.5), but without the `CLOSED` broadcast. With several instances, only the sessions on the instance that stopped are lost.
- **Custom shows aren't saved** (§3.2). The board lives only in its session, so it's gone once the session is removed or the backend restarts; keep the draft or an exported file to host it again.
- **Changing the number of instances moves some sessions' routing.** When an instance is added or removed, the load balancer sends some session ids to a different instance than before. Open connections stay where they are and keep working, but new requests for those sessions (a refresh, a join, a reconnect) reach an instance without them and get a 404. Keep the number of instances fixed while games are running.
- **Disconnected players never time out.** Only `leave` and `kick` remove a player (§6.4). An `away` player stays in the game, and an `away` chooser keeps control, with the timers choosing for them; the session only closes once everyone is `away` (§6.5).
- **No limits** on the number of players or duplicate names. Names are capped at 30 characters (§3.2) and answers at 200 (§4.3).
