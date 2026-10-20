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
1. PUT  /api/game-sessions/{id}?session={id}            { gameId }       → { id }               (create; you pick the id)
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
- **Anyone can join at any time**, unless the host blocked joining after the start (`allowLateJoin`). Everyone who joins, including someone who left and comes back, starts with the session's `joinScore`, which is never better than the lowest score of anyone who stayed (§6.6).
- **Answers are checked leniently.** Spelling mistakes, word order and punctuation are forgiven, numbers aren't, and Double and Final Jeopardy answers must be phrased as a question ("What is ...?"). Correct responses use `(N of) A | B` for alternatives and lists (§6.7).
- **Leaving is explicit.** Closing the tab or losing the connection is *not* leaving: the player stays in the game and can reconnect with their token. Only the `leave` action (or being kicked) removes a player, and it makes their token stop working (§6.4).
- **Sessions don't live forever.** Idle lobbies, finished games and sessions everyone has left are removed from memory (§6.5).

---

## 3. REST API

All bodies are JSON. Errors use `ApiErrorResponse` (`{ "status": 404, "message": "..." }`). That includes request bodies that fail validation (a missing or blank field): `400` with a message naming each invalid field, for example `"playerName must not be blank"` (several are joined with `", "`). Messages are always in English, whatever the `Accept-Language`.

Any other error (an unexpected server error, or a malformed path such as a session id that isn't a UUID) uses Spring's default body instead: `{ "timestamp", "status", "error", "path" }`. It has no stack trace and no exception message, so show a generic error for it.

### 3.1 Browsing games

#### `GET /api/games`

A paged list of games, newest first (by `gameDate`, then `id`).

| Query param | Default | Rules |
|---|---|---|
| `page` | `0` | ≥ 0 (0-based) |
| `size` | `20` | 1–100 |
| `season` | (none) | Optional exact filter, for example `"40"` (values from `GET /api/games/seasons`) |
| `title` | (none) | Optional case-insensitive substring of the title. `%` and `_` are plain characters, not wildcards. Empty or blank means no filter. |
| `from` | (none) | Optional earliest `gameDate`, inclusive, `YYYY-MM-DD` |
| `to` | (none) | Optional latest `gameDate`, inclusive, `YYYY-MM-DD` |
| `playable` | (none) | Optional. `true`: only games with at least one clue (the only ones a session can be created from, §3.2). `false`: only games without clues. Omit it to list both. |

All filters are optional and combine with AND. Ordering and paging stay the same when filtering, and `totalElements` / `totalPages` count only the matching games.

Response: `PageResponse<GameSummaryResponse>`. Each game's `clueCount` is how many clues it has. Some imported games, such as the newest ones, have none: they can be browsed but not played, so show them differently or hide them with `playable=true`.

Errors (all `400` with an `ApiErrorResponse`):

| Cause | `message` |
|---|---|
| Invalid `page` or `size` | `"Invalid request parameters"` |
| `from` or `to` isn't a valid `YYYY-MM-DD` date (for example `2025-02-30` or `02/01/2025`) | `"Invalid date for 'from': expected YYYY-MM-DD"` (or `'to'`) |
| `from` is after `to` | `"'from' must not be after 'to'"` |
| `playable` isn't a boolean | `"Invalid value for 'playable': expected true or false"` |

#### `GET /api/games/seasons`

Every season that has at least one game, with its game count, for example to fill a season filter. Not paged. Response: `SeasonResponse[]`, for example `[{ "season": "42", "gameCount": 230 }, …]`.

Order, newest first:

1. Numbered seasons (`season` is all digits), by number from highest to lowest. They're compared as numbers, so `"10"` comes before `"9"`.
2. Then special seasons whose `season` isn't a number (such as `"jm"` or `"pcj"`: tournaments and other specials), by their most recent `gameDate`, newest first, then by name.

Pass a `season` value unchanged as the `season` filter of `GET /api/games`.

#### `GET /api/games/{id}`

A full game with every category and clue. **It includes answers and Daily Double locations**, which is fine for browsing and single-player use, but never send it to other players in a multiplayer game. Response: `GameResponse`. Unknown id: `404 { "status": 404, "message": "Game not found: 999" }`.

#### `GET /api/clues/{id}`

One clue. Response: `ClueResponse`, whose `gameId` is the game it belongs to (for `GET /api/games/{id}`). Unknown id: 404.

### 3.2 Game sessions

#### Routing: the `session` parameter

A session lives in the memory of one backend instance. When several instances run behind a load balancer (for example in Kubernetes, see the backend's `k8s/README.md`), the load balancer picks the instance for each request from the **`session` query parameter**, so that everything about one session reaches the instance that holds it. A path segment can't be used, because load balancers can't read part of a path, and neither can a header, because browsers can't add headers to a WebSocket connection.

**Every request about one session must carry `?session=<its id>`:** creating it (`PUT /api/game-sessions/{id}`), fetching it, joining it, and the `/ws` connection used to play or watch it (§4.1). The value is the session id, the same as in the path. A request without it may reach an instance that doesn't have the session and get a 404 (or, on the WebSocket, a rejected token), so add it even when running a single instance, where it's simply ignored.

Requests that aren't about one session (the lobby list and §3.1) need no parameter and can reach any instance. The lobby list still covers every instance: the one that answers collects the others' sessions. If an instance doesn't answer it within a second, that instance's sessions are missing from that response.

#### `GET /api/game-sessions`

The sessions in memory, for a lobby page where players pick a game to join or watch. No token is needed: entries never contain tokens or anything else secret.

| Query param | Default | Rules |
|---|---|---|
| `page` | `0` | ≥ 0 (0-based) |
| `size` | `20` | 1–100 |
| `includeFinished` | `false` | `true` also lists sessions in `FINISHED_GAME` (they stay in memory for 10 minutes, §6.5) |

- **Filtering:** by default every session that hasn't finished: lobbies (`WAITING_FOR_START`) and games in progress. Closed sessions are never listed, because they're already gone.
- **Order:** newest first by `createdAt`, then by `id`.
- Response: `PageResponse<GameSessionSummaryResponse>` (the same page shape as `GET /api/games`):

| Field | Meaning |
|---|---|
| `id` | Session id |
| `gameId` | The stored game it was created from (`null` for a board that isn't in the database; there are none yet) |
| `gameTitle` | That game's title (`null` if it has none) |
| `state` | The session's `GameSessionState` (§6). Use it to show "waiting for players" vs "in progress". |
| `round` | The round being played, or about to be |
| `playerCount` | Players who haven't left. Players waiting to be removed (`leaving`, §6.4) aren't counted. |
| `hostName` | The host's name, or `null` before anyone has joined |
| `createdAt` | When the session was created, an ISO-8601 UTC timestamp such as `"2026-10-09T12:30:00Z"` |
| `joinable` | Whether a join would be accepted right now: always `true` in the lobby, and after the start only if `allowLateJoin` |
| `allowLateJoin` | Whether the host allows joining after the start (§6.6) |
| `joinScore` | The score a player who joins now starts with (§6.6) |

The list is a snapshot: a session can start, fill up or close right after. Use `joinable` to decide whether to offer "Join" or only "Watch"; anyone can watch any listed session.

Errors (all `400` with an `ApiErrorResponse`): invalid `page` or `size` → `"Invalid request parameters"`; `includeFinished` isn't a boolean → `"Invalid value for 'includeFinished': expected true or false"`.

#### `PUT /api/game-sessions/{sessionId}?session={sessionId}`

Creates a live session from a stored game, **under an id the client chooses**. Nobody is in it yet: the creator should join next, which makes them the host. The client picks the id so that this request can already be routed by it (see "Routing" above).

- `sessionId`: a new random UUID (version 4), for example from `crypto.randomUUID()`. That function only exists on secure pages (HTTPS or `localhost`); on a plain-HTTP address such as a LAN IP, build one from `crypto.getRandomValues()`. Never reuse an id.
- Request: `{ "gameId": 123 }`. The session starts with the default timers and settings; the host changes them in the lobby (`update-timers`, `update-settings`, §4.3). Any other field in the body (such as an old `timers` object) is ignored.
- `201 { "id": "8f6c…-uuid" }`: the same id.
- Id already in use: `409 { "status": 409, "message": "Game session already exists: <id>" }`. With random UUIDs this only happens when the same create is sent twice (for example a retry after a lost response): the session from the first one exists, so treat it as created.
- An id that isn't a UUID: `400` with Spring's default body (see the start of §3).
- Unknown game: 404.
- Game without clues (some imported games, such as the newest ones, have none): `422 { "status": 422, "message": "Game 725 has no clues and can't be played" }`. The game itself is valid and can still be browsed.
- Missing `gameId`: `400 { "status": 400, "message": "gameId must not be null" }`
- A session nobody joins is removed after 30 minutes (§6.5).

#### `GET /api/game-sessions/{sessionId}?session={sessionId}`

The session's current state: the same `GameSessionResponse` as the latest broadcast, including its `version`. Use it to catch up after connecting, refreshing or reconnecting (§2). No token is needed, because the state never contains secrets. Unknown session: 404. That includes a session that has been removed (§6.5): treat a 404 as "this session no longer exists".

#### `POST /api/game-sessions/{sessionId}/players?session={sessionId}`

Joins a session.

- Request: `{ "playerName": "Nico" }` (must not be blank)
- `201 { "playerId": 1, "token": "Xk3…43 chars" }`. Players are numbered 1, 2, 3… within each session, so ids are only unique inside a session. **Ids are never reused**: after player 3 leaves, the next player to join is 4, not 3.
- The first player to join becomes the host (§6.4).
- Unknown (or removed) session: `404 { "status": 404, "message": "Game session not found: <id>" }`
- Missing or blank name: `400 { "status": 400, "message": "playerName must not be blank" }`

**The token is a secret.** Only the joining player receives it. Store it (for example in `sessionStorage` or `localStorage`, keyed by session id) so the player can reconnect after a refresh, and never display it or send it anywhere except the CONNECT header. Joining also broadcasts the updated player list to the session's topic.

Players can join at any time, including mid-game and after the game has finished, unless the host has blocked joining after the start (`allowLateJoin: false`, §6.6). Then a join after `WAITING_FOR_START` returns `409 { "status": 409, "message": "This game has already started and doesn't accept new players" }`. Joining the lobby is always allowed.

Every new player starts with the session's current `joinScore` (0 until someone's score drops below 0, §6.6), shown in their `PlayerResponse.score`. Someone who left can join again the same way, under any name (even the same one): they become a new player with a new id and token and the current `joinScore`, and nothing else from their old self carries over.

---

## 4. WebSocket (STOMP)

### 4.1 Connecting

- URL: `ws://localhost:8080/ws?session=<sessionId>`. It's a **plain WebSocket, not SockJS**, so use a client such as [`@stomp/stompjs`](https://github.com/stomp-js/stompjs).
- **One connection per session.** The `session` parameter routes the connection to the instance holding that session (§3.2), so use the connection only for that session: subscribe to its topic and send its actions. To play or watch another session, open another connection with that session's id.
- To play, send the token in the CONNECT frame header `player-token`.
- With no token you get an anonymous connection that can only watch.
- An unknown or invalid token **rejects the connection** with a STOMP ERROR frame (message `"Unknown player token"`) before CONNECT completes. A token stops working when its player leaves or is kicked (§6.4) and when its session is removed (§6.5). In every case, treat the rejection as "this token is gone": forget it and show the join form.
- Reconnecting with the same token restores the same player. Dropping the connection doesn't make a player leave.
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
    // e.g. the token was rejected: forget it and send the user back to join
  },
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

Subscribe to both **before** sending actions, otherwise you can miss the response to your own action.

### 4.3 Actions (client → server)

All destinations are prefixed with `/app/sessions/{sessionId}/`. None of them includes a player id.

| Destination | Body | Allowed when |
|---|---|---|
| `start` | none (an empty body or `{}` is fine) | `WAITING_FOR_START`; **host only** |
| `update-timers` | `Partial<SessionTimers>`, for example `{ "buzzerMs": 8000 }` (send `{}` at least) | `WAITING_FOR_START`; **host only**. Durations left out keep their current value. Each must be 1000–300000 ms (§6.3). |
| `update-settings` | `{ "allowLateJoin": boolean }` (required) | `WAITING_FOR_START`; **host only**. Blocks or allows joining after the start (§6.6). |
| `kick` | `{ "playerId": number }` | Any state except `CLOSED`; **host only**; not yourself (use `leave`). The kicked player is treated exactly like a player who leaves. |
| `leave` | none | Any state except `CLOSED` (§6.4) |
| `choose-clue` | `{ "clueId": number }` | `CHOOSING_CLUE`, if you are `playerChoosingClue` |
| `buzz` | none | `WAITING_FOR_BUZZER`, unless you're in `playersLockedOut` |
| `answer` | `{ "answer": string }` | `WAITING_FOR_ANSWER`, if you are `playerAnswering` |
| `place-daily-double-wager` | `{ "wager": number }` | `WAITING_FOR_DAILY_DOUBLE_WAGER`, if you chose the clue |
| `place-final-jeopardy-wager` | `{ "wager": number }` | `FINAL_JEOPARDY_WAGERING` (can be changed until the phase ends) |
| `answer-final-jeopardy` | `{ "answer": string }` | `FINAL_JEOPARDY_ANSWERING` (can be changed until the phase ends) |

Every action requires a player who hasn't left. A player who has left can't act at all, even while they still hold a role (§6.4).

A successful action produces a broadcast. A failed one produces an error on `/user/queue/errors` and nothing else.

**Messages from one connection are handled in the order they were sent.** For example, two `place-final-jeopardy-wager` messages sent back to back always leave the second wager in place, so there's no need to wait for a broadcast before sending the next message. There's no ordering between different connections: when two players act at the same moment, either can be first (see §7).

---

## 5. The game state (`GameSessionResponse`)

| Field | Meaning |
|---|---|
| `id` | Session id (UUID string) |
| `version` | Goes up by one with every change (0 for a new session). Keep the response with the highest version (§2). |
| `round` | `JEOPARDY` \| `DOUBLE_JEOPARDY` \| `FINAL_JEOPARDY`, the round being played (or about to be, before the start) |
| `state` | See §6 |
| `closeReason` | Why the session was removed: `ABANDONED` \| `IDLE` \| `FINISHED` (§6.5). Only set when `state` is `CLOSED`; otherwise `null`. |
| `phaseDurationMs` | Full length of the current phase in milliseconds, or `null` if the state has no timer (§6.3) |
| `phaseRemainingMs` | Milliseconds left in the current phase when the server built this response (never negative), or `null` if the state has no timer |
| `timers` | The session's configured phase durations in milliseconds (`SessionTimers`, §6.3). Always present, so the lobby can show them. |
| `players` | `{ id, name, score, leaving }` in join order. `leaving` is `true` for a player who left (or was kicked) but still holds a role and is waiting to be removed (§6.4); show them as leaving. Removed players aren't listed at all. |
| `hostPlayerId` | The host's player id (§6.4), or `null` while nobody has joined. Always a player with `leaving: false`. |
| `allowLateJoin` | Whether players can join after the start. `true` by default; only the host can change it, before the start (§6.6). |
| `joinScore` | The score a player who joins now starts with. Never above 0 (§6.6). |
| `playerChoosingClue` | Whose turn it is to pick. `null` before the game starts. Correct answers take control of the board. Can be `null` in Final Jeopardy and `FINISHED_GAME` if the last chooser left (§6.4). |
| `playerAnswering` | Who must answer right now. Only set in `WAITING_FOR_ANSWER` (the buzzer, or the chooser on a Daily Double); otherwise `null`. |
| `playersLockedOut` | Players who already buzzed on this clue and can't buzz again. Only filled in `WAITING_FOR_BUZZER` and `WAITING_FOR_ANSWER`; otherwise `[]`. |
| `board` | The current round's grid (§5.1). Never contains questions, answers or Daily Double locations. |
| `currentClue` | The clue in play (§5.2), or `null` |
| `clueAnswers` | Every answer given to the clue in play, or to the last clue played (§5.4). Never `null`; `[]` when there are none. |
| `finalJeopardy` | Final Jeopardy status (§5.3), or `null` |

**Every player id in a response belongs to someone in `players`** (`hostPlayerId`, `playerChoosingClue`, `playerAnswering`, `playersLockedOut`, `clueAnswers[].playerId`, `finalJeopardy.players[].playerId`). When a player is removed, every trace of them goes with them (§6.4).

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
| `dailyDouble` | `true` for a Daily Double. It's public at this point, since the wager phase reveals it. |
| `wager` | The Daily Double wager once it's placed; otherwise `null` |
| `question` | Hidden (`null`) while waiting for a Daily Double wager; shown from `REVEALING_CLUE` on |
| `clueType` | `TEXT` \| `IMAGE` \| `SOUND` \| `VIDEO`. Hidden exactly like `question`. |
| `mediaUrl` | The image, sound or video to play with the question, `null` for a `TEXT` clue. Hidden exactly like `question`. |
| `numberExpected` | `true` when only an exact number is accepted (§6.7), so you can warn the player that typos won't be forgiven. Hidden exactly like `question`, since it says something about the answer. |
| `answer` | Only in `REVEALING_ANSWER`. Alternatives and lists are written `(N of) A \| B` (§6.7). |

### 5.3 `finalJeopardy`

Non-null during the Final Jeopardy states, and in `FINISHED_GAME` if the game had a Final round (so the results stay visible).

| Field | Shown from |
|---|---|
| `category` | `FINAL_JEOPARDY_WAGERING` |
| `categoryComments` (the category's comments, or `null` if it has none) | `FINAL_JEOPARDY_WAGERING` |
| `question` | `FINAL_JEOPARDY_ANSWERING` |
| `numberExpected` (only an exact number is accepted, §6.7) | `FINAL_JEOPARDY_ANSWERING` |
| `answer` (the correct response, written as in §6.7) | `FINAL_JEOPARDY_REVEALING` |
| `players[]` → `playerId`, `hasWagered`, `hasAnswered` | always |
| `players[]` → `wager`, `answer`, `correct`, `phrasedAsQuestion` | `FINAL_JEOPARDY_REVEALING` (before that they're `null`, so nobody can see anyone else's). `phrasedAsQuestion` (§6.7) stays `null` for a player who didn't answer. |

### 5.4 `clueAnswers`

What players answered on the current ordinary clue or Daily Double, oldest first, so everyone can see each answer and its result. Each entry is `{ playerId, answer, correct, phrasedAsQuestion }`:

- `answer` is the text the player submitted, or `null` if they ran out of time (which counts as wrong).
- `correct` is the result of the answer check (§6.2).
- `phrasedAsQuestion` says whether the answer was phrased as a question (§6.7), in every round, also where that isn't required. It's `null` when the player ran out of time.

An entry appears in the broadcast that follows the answer. After a wrong answer, the list stays visible while others buzz and answer (`WAITING_FOR_BUZZER`, `WAITING_FOR_ANSWER`). After the last answer it stays visible in `REVEALING_ANSWER` and then in `CHOOSING_CLUE`, until the next clue is chosen. It's cleared when a clue is chosen and when Final Jeopardy starts (Final Jeopardy has its own reveal in §5.3).

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
CHOOSING_CLUE (30s) ◄────────────────────────────────────────┐
   │ choose-clue (or 30s: a clue is chosen for the chooser)  │
   ├── Daily Double ─► WAITING_FOR_DAILY_DOUBLE_WAGER        │
   │                     │ place-daily-double-wager (or 30s) │
   ▼                     ▼                                   │
REVEALING_CLUE (3s)                                          │
   ├── Daily Double ─► WAITING_FOR_ANSWER (chooser only)     │
   ▼                                                         │
WAITING_FOR_BUZZER (10s) ──── buzz ───► WAITING_FOR_ANSWER (5s)
   │ nobody buzzes                         │ correct, wrong with nobody left,
   ▼                                       │ Daily Double, or timeout
REVEALING_ANSWER (3s) ◄────────────────────┘
   │                (wrong with players left → back to WAITING_FOR_BUZZER)
   └─► CHOOSING_CLUE, next round, Final Jeopardy, or FINISHED_GAME ─┘

Final Jeopardy:
FINAL_JEOPARDY_WAGERING (30s) → FINAL_JEOPARDY_ANSWERING (30s)
  → FINAL_JEOPARDY_REVEALING (10s) → scores applied → FINISHED_GAME

Any state ──(session removed, §6.5)──► CLOSED   (only in the very last broadcast)
```

The durations shown are the defaults; each session can change them (§6.3).

### 6.2 Rules

- **Start:** only the host can start, and the host picks the first clue.
- **Choosing a clue:** the player in control has 30 s (by default) to choose. If they don't, the server chooses for them: the top-most available clue (lowest row), leftmost category first. It's played exactly as if they had chosen it, so it can be a Daily Double, and they keep control. This keeps the game moving if the chooser has left.
- **Scoring:** a correct answer adds the clue's value and gives that player control of the board. A wrong answer, or running out of time to answer, subtracts it. Not buzzing costs nothing.
- **Wrong answers:** the other players who haven't buzzed yet can buzz. When everyone has buzzed, the answer is revealed. "Everyone" means every player who hasn't left: players marked `leaving` can't buzz and aren't waited for. If a player leaves while everyone else still in the game has already buzzed, the answer is revealed at once.
- **Rounds:** when every clue in a round has been played, the next round starts. Rounds with no clues are skipped. After Double Jeopardy comes Final Jeopardy if the game has one; otherwise the game finishes.
- **Daily Double:** only the player who chose it plays. Their wager must be between 5 and the larger of their score and the round's highest clue value (normally 1000 in Jeopardy and 2000 in Double Jeopardy). Not wagering in time means a wager of 5. Right or wrong, the wager is added or subtracted, and the chooser keeps control.
- **Final Jeopardy:**
  - Every player wagers between 0 and their score. A score of 0 or less can only wager 0, but that player still plays.
  - Wagers and answers can be changed until their phase ends. Phases always run their full time.
  - A missing wager counts as 0, and a missing answer counts as wrong.
  - Scores change only after the reveal.
- **Answer checking** is lenient about spelling, wording and order but strict about numbers, and Double and Final Jeopardy need the question form ("What is ...?"). See §6.7.

### 6.3 Timings

Each session has its own phase durations, sent in every response as `timers`. They start at these defaults:

| Phase | `timers` field | Default |
|---|---|---|
| Choosing a clue (`CHOOSING_CLUE`) | `clueChoiceMs` | 30 s |
| Clue reveal (`REVEALING_CLUE`) | `clueRevealMs` | 3 s |
| Daily Double wager (`WAITING_FOR_DAILY_DOUBLE_WAGER`) | `dailyDoubleWagerMs` | 30 s |
| Buzzer (`WAITING_FOR_BUZZER`) | `buzzerMs` | 10 s |
| Answer (`WAITING_FOR_ANSWER`) | `answerMs` | 5 s |
| Answer reveal (`REVEALING_ANSWER`) | `answerRevealMs` | 3 s |
| Final Jeopardy wagering | `finalJeopardyWagerMs` | 30 s |
| Final Jeopardy answering | `finalJeopardyAnswerMs` | 30 s |
| Final Jeopardy reveal | `finalJeopardyRevealMs` | 10 s |

`WAITING_FOR_START`, `FINISHED_GAME` and `CLOSED` have no timer.

**Changing them:**

- Only in the lobby, with the host's `update-timers` action (§4.3), in `WAITING_FOR_START`. It broadcasts the new `timers`. Once the game has started they're fixed (`INVALID_STATE`); anyone but the host gets `NOT_HOST`. Every session starts with the defaults.
- Every field is optional, and a field left out keeps its current value.
- **Bounds:** every duration is a whole number of milliseconds from **1000 (1 s) to 300000 (5 min)**, inclusive. Out of range: `INVALID_REQUEST` with `"<field> must be between 1000 and 300000"` (several invalid fields are joined with `", "`). Nothing changes when any field is invalid.

Every response (broadcast and `GET /api/game-sessions/{id}`) carries the current phase's timing:

- `phaseDurationMs`: the full length of the phase, for example `10000` in `WAITING_FOR_BUZZER`.
- `phaseRemainingMs`: how much of it was left when the server built the response, from `0` up to `phaseDurationMs`.
- Both are `null` in a state without a timer.

They're durations, not timestamps, so the client's clock doesn't need to match the server's. To show a countdown, note your own time when the response arrives (`performance.now()`) and count down from `phaseRemainingMs`. It will end late by the network delay of that response, which is usually small. Final Jeopardy wagers and answers don't restart their phase: their broadcasts show the same deadline with less time left. The phase can end a few milliseconds after the countdown reaches 0, when the next broadcast arrives.

### 6.4 Host, kicking and leaving

**Host**

- The first player to join is the host (`hostPlayerId`). Only the host can `start`, `update-timers` and `kick`; anyone else gets `NOT_HOST`.
- When the host leaves or is kicked, the remaining player with the **lowest id** who hasn't left becomes host at once, in the same broadcast. (So the host is always the active player with the lowest id.)
- When nobody is left, the session closes (§6.5).

**Kicking.** The host sends `kick` with another player's id. The kicked player is treated **exactly** like a player who leaves (below). Kicking yourself is rejected with `INVALID_REQUEST` (use `leave`). Kicking an id that isn't an active player in the session, including one who has already left, is rejected with `PLAYER_NOT_FOUND`.

**Leaving.** A player leaves only by sending `leave` (or being kicked). A closed tab or a dropped connection is not leaving: the player keeps their place, the timers act for them, and they can reconnect with their token. There is no grace period after which a disconnected player counts as gone.

As soon as a player leaves:

- **Their token stops working** and their open connections are closed (§4.1). They can't act any more, even in a role they still hold; an action from a stale connection gets `PLAYER_NOT_IN_SESSION`.
- **The host passes on** if they were the host.
- **Then they're either removed at once or marked `leaving`:**

| When they leave | What happens |
|---|---|
| `WAITING_FOR_START` | Removed at once |
| During the clue rounds, without a role | Removed at once |
| While they're `playerChoosingClue` (any clue-round state, including during a clue they chose, a Daily Double, or the reveal) | Stay with `leaving: true` until another player takes control by answering correctly. Until then the timers choose clues and wager for them, and they keep control exactly as an absent chooser would (§6.2). |
| While they're `playerAnswering` (the buzzer, or the chooser on a Daily Double) | Stay with `leaving: true` until their answer time runs out, which counts as a wrong answer (or a wrong Daily Double). If they're also the chooser, they stay until control passes, as above. |
| Final Jeopardy (any of its states) | Removed at once. There are no roles in Final Jeopardy. |
| `FINISHED_GAME` | Removed at once |

A chooser marked `leaving` when the clue rounds end (Final Jeopardy starts, or the game finishes without one) is removed then, and `playerChoosingClue` becomes `null`.

**Score and answers.** While a player is marked `leaving` they stay in `players` and their score still changes: running out of time on an answer subtracts the clue's value (or their Daily Double wager, which the timer sets to 5). When a player is removed, they disappear from the snapshot entirely: from `players` (their score is gone, they don't appear in the final standings), from `clueAnswers` (their answers to the current clue are deleted), from `playersLockedOut`, and from `finalJeopardy.players` (any Final Jeopardy wager or answer is discarded; they aren't scored).

**Buzzing.** `playersLockedOut` only lists players who are still in `players`. A player marked `leaving` can't buzz, and "everyone has buzzed" (§6.2) only counts players who haven't left.

**Rejoining.** Someone who left can join again with `POST /api/game-sessions/{id}/players`, under any name, as a new player (new id, new token, starting at the current `joinScore`, §3.2 and §6.6), unless the host blocked joining after the start.

**The last player.** When the last player who hasn't left leaves (or every remaining player is marked `leaving`), the session is closed at once, even in the lobby (§6.5).

### 6.5 Session cleanup

Sessions live in memory and are removed automatically:

| Session | Removed | `closeReason` |
|---|---|---|
| Every player has left, or every remaining player is marked `leaving` | At once, by the `leave` or `kick` that caused it | `ABANDONED` |
| `WAITING_FOR_START` with no change for **30 minutes** (a change is anything that's broadcast: a join, a leave, a kick, new timers). A session nobody joins counts from its creation. | After 30 minutes without a change | `IDLE` |
| `FINISHED_GAME` with no change for **10 minutes** (counted from the end of the game, or from the last player leaving after it) | After 10 minutes without a change | `FINISHED` |

A game in progress is never removed by a timer: every phase is timed, so even a game whose players all closed their tabs plays itself out to `FINISHED_GAME` and is removed 10 minutes later. All sessions are also lost when the backend restarts.

**What clients see:**

1. One last broadcast on `/topic/sessions/{id}`: a normal `GameSessionResponse` with the next `version`, `state: "CLOSED"`, `closeReason` set, `phaseDurationMs`/`phaseRemainingMs` `null`, and `currentClue`/`finalJeopardy` `null`. `players`, their scores and `board` are as they were, so a final scoreboard can still be shown. Show something like "This game has ended" (or "Everyone left").
2. The session is gone: `GET /api/game-sessions/{id}` returns 404, it disappears from `GET /api/game-sessions`, joining returns 404, and any action gets `SESSION_NOT_FOUND`.
3. Every token in it stops working. Open connections stay open (they just receive nothing more), but a reconnect is rejected on CONNECT exactly as after a backend restart.

### 6.6 Joining after the start and the join score

**Late joining.** By default anyone can join at any time: in the lobby, during the game and after it has finished. Before the start, the host can block joining after the start with `update-settings` `{ "allowLateJoin": false }` (and allow it again with `true`). Once the game starts the setting is fixed (`INVALID_STATE`); anyone but the host gets `NOT_HOST`. With late joining blocked, every join after `WAITING_FOR_START` returns `409`, including from someone who left and wants to come back. A player who joined mid-game plays like anyone else, starting with the next clue they can buzz on; in Final Jeopardy they can wager (0 up to their score) and answer if the phase hasn't ended.

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
| `(N of) A \| B \| C` | A correct response names at least N of the options. `(1 of)` means any one; N equal to the number of options means all of them. `\|` appears nowhere else. | `(1 of) Myanmar \| Burma`, `(2 of) gold \| frankincense \| myrrh` |
| `(...)` | An optional part. Parentheses can nest. | `(Abraham) Lincoln`, `(duck-bill(ed)) platypus` |
| `A/B` | Either side, except in a fraction like `2/3` | `bear/bare` |

To show it to players, render `(1 of) A | B` as "A or B" and `(2 of) A | B | C` as something like "Any 2 of: A, B, C".

**What counts as correct:**

- **Spelling and wording:** case, accents, punctuation, the articles a/an/the and word order don't matter. A few typos are allowed per word, by the length of the word in the answer: none up to 3 letters, 1 up to 6, then 2 (swapping two letters counts as 1).
- **Numbers must be exact.** `1,001` and `1001` are the same, and `$` and `%` are optional, but number words aren't numbers (`nine` isn't `9`). Decades accept the short form: `the 1980s` accepts `1980s`, `80s` and `'80s`. When the response is just a number (`1970`, `$25,000`, `2/3`, `50th`), `numberExpected` is `true` on `currentClue` and `finalJeopardy`, so the player can be told it must be exact. Decades don't set it.
- **Optional parts** can be said or left out, word by word. Everything outside them is needed, so the optional part alone isn't enough: `(Abraham) Lincoln` accepts "Lincoln" and "Abraham Lincoln", not "Abraham".
- **Nothing extra:** words that aren't in the answer make a response wrong, even true ones ("the Wright brothers" for `(Orville) Wright`).
- **Lists:** items can come in any order, separated by commas, "and", "&", "or", or nothing at all. Naming more options than needed is fine; naming anything that isn't one is wrong. For `(2 of) gold | frankincense | myrrh`, "gold and myrrh" and "gold, frankincense, myrrh" are right; "gold" and "gold and silver" are wrong.
- **The question form** is optional in the Jeopardy round and required in Double Jeopardy and Final Jeopardy. It's any of what, who, where, when, which, why or how, followed by is, are, was, were, 's or 're ("What is", "Who's", "whats", "Where were"), whatever kind of answer it is. A few typos of the question word count too ("waht is", "hwo is"). A response without it is wrong there, unless the correct response is itself a question ("Who's the Boss?"), which can then be said as it is. Every answer reports `phrasedAsQuestion` (in `clueAnswers` and, from the reveal, in `finalJeopardy.players`), in every round: `true` when it has the question form, or is a correct response that is itself a question said as it is. In the Jeopardy round, use it to warn a player who left it out that this would be wrong from Double Jeopardy on; in Double and Final Jeopardy, `false` explains a wrong answer. It doesn't say the rest was right: an answer can be wrong both ways.

---

## 7. Errors (`/user/queue/errors`)

`{ "code": GameErrorCode, "message": string }`. The message is human-readable and can be shown to the player.

| Code | Typical cause |
|---|---|
| `UNAUTHENTICATED` | Acting on a connection without a player token |
| `PLAYER_NOT_IN_SESSION` | Your token belongs to a different session, or you have left the session (or were kicked) and are acting from a connection that hasn't been closed yet |
| `SESSION_NOT_FOUND` | The session no longer exists (§6.5) |
| `INVALID_STATE` | The action isn't allowed in the current state (for example, buzzing before the clue is revealed, or changing timers after the start) |
| `NOT_YOUR_TURN` | Another player is choosing or answering, or you already buzzed on this clue |
| `NOT_HOST` | Only the host can do that: `start`, `update-timers` or `kick` (§6.4) |
| `PLAYER_NOT_FOUND` | The player you tried to kick isn't in the session or has already left |
| `CLUE_NOT_FOUND` | That clue isn't on this round's board |
| `CLUE_ALREADY_PLAYED` | That clue was already chosen |
| `INVALID_WAGER` | The wager is outside the allowed range (§6.2) |
| `INVALID_REQUEST` | The payload is missing a field or malformed, or a timer is out of bounds (the message names the field, §6.3); also kicking yourself |

Timing races are normal: two players buzzing at the same moment means one wins and the other gets `INVALID_STATE`. Treat these errors as non-fatal.

---

## 8. TypeScript types

```ts
// ---------- shared enums ----------
type GameRound = "JEOPARDY" | "DOUBLE_JEOPARDY" | "FINAL_JEOPARDY";
type ClueType = "TEXT" | "IMAGE" | "SOUND" | "VIDEO";

type GameSessionState =
  | "WAITING_FOR_START"
  | "CHOOSING_CLUE"
  | "WAITING_FOR_DAILY_DOUBLE_WAGER"
  | "REVEALING_CLUE"
  | "WAITING_FOR_BUZZER"
  | "WAITING_FOR_ANSWER"
  | "REVEALING_ANSWER"
  | "FINAL_JEOPARDY_WAGERING"
  | "FINAL_JEOPARDY_ANSWERING"
  | "FINAL_JEOPARDY_REVEALING"
  | "FINISHED_GAME"
  | "CLOSED";              // only in a removed session's last broadcast (§6.5)

type SessionCloseReason = "ABANDONED" | "IDLE" | "FINISHED";

type GameErrorCode =
  | "SESSION_NOT_FOUND"
  | "NOT_YOUR_TURN"
  | "INVALID_STATE"
  | "CLUE_NOT_FOUND"
  | "CLUE_ALREADY_PLAYED"
  | "PLAYER_NOT_IN_SESSION"
  | "INVALID_WAGER"
  | "INVALID_REQUEST"
  | "UNAUTHENTICATED"
  | "NOT_HOST"
  | "PLAYER_NOT_FOUND";

// ---------- REST: browsing ----------
interface ApiErrorResponse {
  status: number;
  message: string;
}

// Query params of GET /api/games (all optional)
interface GameListParams {
  page?: number;         // default 0
  size?: number;         // default 20, max 100
  season?: string;
  title?: string;        // case-insensitive substring
  from?: string;         // "YYYY-MM-DD", inclusive
  to?: string;           // "YYYY-MM-DD", inclusive
  playable?: boolean;    // true: only games with clues; false: only games without
}

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
  dailyDoubleClueIds: number[];
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
  finalJeopardyRevealMs: number;
}

// PUT /api/game-sessions/{id}?session={id}, with an id the client generated (§3.2)
interface CreateGameSessionRequest { gameId: number; }  // timers and settings are set in the lobby
interface CreateGameSessionResponse { id: string; }

// Query params of GET /api/game-sessions (all optional)
interface GameSessionListParams {
  page?: number;              // default 0
  size?: number;              // default 20, max 100
  includeFinished?: boolean;  // default false
}

// GET /api/game-sessions returns PageResponse<GameSessionSummaryResponse>
interface GameSessionSummaryResponse {
  id: string;
  gameId: number | null;
  gameTitle: string | null;
  state: GameSessionState;
  round: GameRound;
  playerCount: number;        // players who haven't left
  hostName: string | null;    // null before anyone joins
  createdAt: string;          // ISO-8601 UTC, e.g. "2026-10-09T12:30:00Z"
  joinable: boolean;          // a join would be accepted right now
  allowLateJoin: boolean;
  joinScore: number;          // a new player's starting score, never above 0
}

interface JoinSessionRequest { playerName: string; }
interface JoinSessionResponse {
  playerId: number;      // 1, 2, 3... within the session
  token: string;         // secret: only for the CONNECT header
}

// ---------- WebSocket: actions ----------
interface ChooseClueRequest { clueId: number; }
interface AnswerClueRequest { answer: string; }
interface PlaceDailyDoubleWagerRequest { wager: number; }
interface PlaceFinalJeopardyWagerRequest { wager: number; }
interface AnswerFinalJeopardyRequest { answer: string; }
type UpdateTimersRequest = Partial<SessionTimers>;  // host only, WAITING_FOR_START
interface KickPlayerRequest { playerId: number; }  // host only
interface UpdateSettingsRequest { allowLateJoin: boolean; }  // host only, WAITING_FOR_START
// start, buzz and leave have no body

// ---------- WebSocket: state ----------
interface PlayerResponse {
  id: number;
  name: string;
  score: number;
  leaving: boolean;      // left but still holds a role; removed when it ends (§6.4)
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
  dailyDouble: boolean;
  wager: number | null;
  question: string | null;
  clueType: ClueType | null;   // null while the question is hidden
  mediaUrl: string | null;     // null while hidden, and always for TEXT
  numberExpected: boolean | null;  // only an exact number is accepted; null while hidden
  answer: string | null;       // "(N of) A | B" for alternatives (§6.7)
}

interface ClueAnswerResponse {
  playerId: number;
  answer: string | null;    // null: ran out of time
  correct: boolean;
  phrasedAsQuestion: boolean | null;  // set in every round; null: ran out of time
}

interface FinalJeopardyPlayerResponse {
  playerId: number;
  hasWagered: boolean;
  hasAnswered: boolean;
  wager: number | null;     // revealed in FINAL_JEOPARDY_REVEALING
  answer: string | null;    // revealed in FINAL_JEOPARDY_REVEALING
  correct: boolean | null;  // revealed in FINAL_JEOPARDY_REVEALING
  phrasedAsQuestion: boolean | null;  // revealed in FINAL_JEOPARDY_REVEALING; null without an answer
}

interface FinalJeopardyResponse {
  category: string;
  categoryComments: string | null;
  question: string | null;
  numberExpected: boolean | null;  // only an exact number is accepted; null while wagering
  answer: string | null;           // "(N of) A | B" for alternatives (§6.7)
  players: FinalJeopardyPlayerResponse[];
}

interface GameSessionResponse {
  id: string;
  version: number;       // keep the highest one seen
  round: GameRound;
  state: GameSessionState;
  closeReason: SessionCloseReason | null;  // set only when state is "CLOSED"
  phaseDurationMs: number | null;   // null: no timer in this state
  phaseRemainingMs: number | null;  // measured when the server built the response
  timers: SessionTimers;            // this session's configured durations
  players: PlayerResponse[];
  hostPlayerId: number | null;      // null only before anyone joins
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
```

---

## 9. Known limitations

These are tracked on the backend roadmap. Work around them in the UI only where noted.

- **No winner or tie field.** At `FINISHED_GAME`, work out the winner from the final `players[].score`.
- **Answer checking doesn't know synonyms** (§6.7): abbreviations aren't expanded ("Avenue" isn't `Ave.`), and number words aren't numbers.
- **Sessions are lost when the backend restarts**, along with every token. Clients see this the same way as a removed session (§6.5), but without the `CLOSED` broadcast. With several instances, only the sessions on the instance that stopped are lost.
- **Changing the number of instances moves some sessions' routing.** When an instance is added or removed, the load balancer sends some session ids to a different instance than before. Open connections stay where they are and keep working, but new requests for those sessions (a refresh, a join, a reconnect) reach an instance without them and get a 404. Keep the number of instances fixed while games are running.
- **Disconnected players never time out.** Only `leave` and `kick` remove a player (§6.4); the timers keep the game moving around an absent player.
- **No limits** on the number of players, duplicate names or name length.
