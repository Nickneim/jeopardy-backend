# Jeopardy backend API

The contract between this backend and the React frontend. It covers the REST API (browsing games, creating and joining sessions) and the STOMP WebSocket protocol used to play.

**Keep this file in sync with the code.** Any change to an endpoint, payload, field, state, timing or error code should update it in the same commit.

---

## 1. Running the backend locally

- Base URL: `http://localhost:8080`. The WebSocket endpoint is `ws://localhost:8080/ws`.
- Required environment variables: `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`. They live in the backend's git-ignored `.env`. The IntelliJ run configuration (`.run/JeopardyApplication.run.xml`) loads them, but `./gradlew bootRun` does **not**, so export them first if you use Gradle.
- **Browser origins (CORS):** the backend accepts browser requests and WebSocket connections from `http://localhost:5173` (the Vite dev server) by default. To allow other origins, set `ALLOWED_ORIGINS` to a comma-separated list, for example `ALLOWED_ORIGINS=https://jeopardy.example,http://localhost:5173`.

---

## 2. How a multiplayer game works

```
1. POST /api/game-sessions                    { gameId }       → { id }               (create)
2. POST /api/game-sessions/{id}/players       { playerName }   → { playerId, token }  (join)
3. STOMP CONNECT ws://localhost:8080/ws       header  player-token: <token>
4. SUBSCRIBE /topic/sessions/{id}             ← full game state after every change
   SUBSCRIBE /user/queue/errors               ← errors caused by your own actions
5. GET  /api/game-sessions/{id}                                → current state      (catch up)
6. SEND /app/sessions/{id}/start | choose-clue | buzz | answer | ...
```

Key ideas:

- **The server is authoritative and sends full snapshots.** After every state change (a player's action, a timer expiring, someone joining), every subscriber gets the complete `GameSessionResponse`. Render from the latest message; don't try to apply deltas.
- **Catching up after connecting.** Subscribe first, then fetch `GET /api/game-sessions/{id}`. Every response carries a `version` that goes up with each change, so whether the fetch result or a broadcast arrives first, **keep whichever has the higher `version`** and ignore the other. Use the same approach after a refresh or reconnect.
- **Who you are comes from your connection, not your messages.** You prove your identity once, with your token, when you connect. Action payloads never contain a player id, so a player can't act as someone else.
- **Anyone can watch.** A connection without a token can subscribe to a session's topic but can't act.
- **Timers run on the server.** Phases such as revealing a clue or waiting for buzzes end automatically. The client just renders whatever state arrives. Every response says how long the current phase lasts and how much of it is left (`phaseDurationMs`, `phaseRemainingMs`), so countdowns are right even after a refresh. See §6.3.

---

## 3. REST API

All bodies are JSON. Errors use `ApiErrorResponse` (`{ "status": 404, "message": "..." }`). That includes request bodies that fail validation (a missing or blank field): `400` with a message naming each invalid field, for example `"playerName must not be blank"` (several are joined with `", "`). Messages are always in English, whatever the `Accept-Language`.

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

All filters are optional and combine with AND. Ordering and paging stay the same when filtering, and `totalElements` / `totalPages` count only the matching games.

Response: `PageResponse<GameSummaryResponse>`. Errors (all `400` with an `ApiErrorResponse`):

| Cause | `message` |
|---|---|
| Invalid `page` or `size` | `"Invalid request parameters"` |
| `from` or `to` isn't a valid `YYYY-MM-DD` date (for example `2025-02-30` or `02/01/2025`) | `"Invalid date for 'from': expected YYYY-MM-DD"` (or `'to'`) |
| `from` is after `to` | `"'from' must not be after 'to'"` |

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

#### `POST /api/game-sessions`

Creates a live session from a stored game.

- Request: `{ "gameId": 123 }`
- `201 { "id": "8f6c…-uuid" }`
- Unknown game: 404.
- Game without clues (some imported games, such as the newest ones, have none): `422 { "status": 422, "message": "Game 725 has no clues and can't be played" }`. The game itself is valid and can still be browsed.
- Missing `gameId`: `400 { "status": 400, "message": "gameId must not be null" }`

#### `GET /api/game-sessions/{sessionId}`

The session's current state: the same `GameSessionResponse` as the latest broadcast, including its `version`. Use it to catch up after connecting, refreshing or reconnecting (§2). No token is needed, because the state never contains secrets. Unknown session: 404.

#### `POST /api/game-sessions/{sessionId}/players`

Joins a session.

- Request: `{ "playerName": "Nico" }` (must not be blank)
- `201 { "playerId": 1, "token": "Xk3…43 chars" }`. Players are numbered 1, 2, 3… within each session, so ids are only unique inside a session.
- Unknown session: `404 { "status": 404, "message": "Game session not found: <id>" }`
- Missing or blank name: `400 { "status": 400, "message": "playerName must not be blank" }`

**The token is a secret.** Only the joining player receives it. Store it (for example in `sessionStorage` or `localStorage`, keyed by session id) so the player can reconnect after a refresh, and never display it or send it anywhere except the CONNECT header. Joining also broadcasts the updated player list to the session's topic.

Players can currently join at any time, including mid-game.

---

## 4. WebSocket (STOMP)

### 4.1 Connecting

- URL: `ws://localhost:8080/ws`. It's a **plain WebSocket, not SockJS**, so use a client such as [`@stomp/stompjs`](https://github.com/stomp-js/stompjs).
- To play, send the token in the CONNECT frame header `player-token`.
- With no token you get an anonymous connection that can only watch.
- An unknown or invalid token **rejects the connection** with a STOMP ERROR frame. A token stops working when its session is removed.
- Reconnecting with the same token restores the same player.

```ts
import { Client } from "@stomp/stompjs";

const client = new Client({
  brokerURL: "ws://localhost:8080/ws",
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
| `start` | none (an empty body or `{}` is fine) | `WAITING_FOR_START`; any joined player can start |
| `choose-clue` | `{ "clueId": number }` | `CHOOSING_CLUE`, if you are `playerChoosingClue` |
| `buzz` | none | `WAITING_FOR_BUZZER`, unless you're in `playersLockedOut` |
| `answer` | `{ "answer": string }` | `WAITING_FOR_ANSWER`, if you are `playerAnswering` |
| `place-daily-double-wager` | `{ "wager": number }` | `WAITING_FOR_DAILY_DOUBLE_WAGER`, if you chose the clue |
| `place-final-jeopardy-wager` | `{ "wager": number }` | `FINAL_JEOPARDY_WAGERING` (can be changed until the phase ends) |
| `answer-final-jeopardy` | `{ "answer": string }` | `FINAL_JEOPARDY_ANSWERING` (can be changed until the phase ends) |

A successful action produces a broadcast. A failed one produces an error on `/user/queue/errors` and nothing else.

---

## 5. The game state (`GameSessionResponse`)

| Field | Meaning |
|---|---|
| `id` | Session id (UUID string) |
| `version` | Goes up by one with every change (0 for a new session). Keep the response with the highest version (§2). |
| `round` | `JEOPARDY` \| `DOUBLE_JEOPARDY` \| `FINAL_JEOPARDY`, the round being played (or about to be, before the start) |
| `state` | See §6 |
| `phaseDurationMs` | Full length of the current phase in milliseconds, or `null` if the state has no timer (§6.3) |
| `phaseRemainingMs` | Milliseconds left in the current phase when the server built this response (never negative), or `null` if the state has no timer |
| `players` | `{ id, name, score }` in join order |
| `playerChoosingClue` | Whose turn it is to pick. `null` before the game starts. Correct answers take control of the board. |
| `playerAnswering` | Who must answer right now. Only set in `WAITING_FOR_ANSWER` (the buzzer, or the chooser on a Daily Double); otherwise `null`. |
| `playersLockedOut` | Players who already buzzed on this clue and can't buzz again. Only filled in `WAITING_FOR_BUZZER` and `WAITING_FOR_ANSWER`; otherwise `[]`. |
| `board` | The current round's grid (§5.1). Never contains questions, answers or Daily Double locations. |
| `currentClue` | The clue in play (§5.2), or `null` |
| `clueAnswers` | Every answer given to the clue in play, or to the last clue played (§5.4). Never `null`; `[]` when there are none. |
| `finalJeopardy` | Final Jeopardy status (§5.3), or `null` |

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
| `answer` | Only in `REVEALING_ANSWER` |

### 5.3 `finalJeopardy`

Non-null during the Final Jeopardy states, and in `FINISHED_GAME` if the game had a Final round (so the results stay visible).

| Field | Shown from |
|---|---|
| `category` | `FINAL_JEOPARDY_WAGERING` |
| `categoryComments` (the category's comments, or `null` if it has none) | `FINAL_JEOPARDY_WAGERING` |
| `question` | `FINAL_JEOPARDY_ANSWERING` |
| `answer` (the correct response) | `FINAL_JEOPARDY_REVEALING` |
| `players[]` → `playerId`, `hasWagered`, `hasAnswered` | always |
| `players[]` → `wager`, `answer`, `correct` | `FINAL_JEOPARDY_REVEALING` (before that they're `null`, so nobody can see anyone else's) |

### 5.4 `clueAnswers`

What players answered on the current ordinary clue or Daily Double, oldest first, so everyone can see each answer and its result. Each entry is `{ playerId, answer, correct }`:

- `answer` is the text the player submitted, or `null` if they ran out of time (which counts as wrong).
- `correct` is the result of the answer check (§6.2).

An entry appears in the broadcast that follows the answer. After a wrong answer, the list stays visible while others buzz and answer (`WAITING_FOR_BUZZER`, `WAITING_FOR_ANSWER`). After the last answer it stays visible in `REVEALING_ANSWER` and then in `CHOOSING_CLUE`, until the next clue is chosen. It's cleared when a clue is chosen and when Final Jeopardy starts (Final Jeopardy has its own reveal in §5.3).

**Every answer to the clue is kept, not just the latest one.** Clients render from the latest snapshot (§2), so one that missed a broadcast or joined mid-clue still sees that, for example, player 1 answered wrong before player 2 answered right. For "the latest answer", use the last element.

It never contains the stored correct response, which is only sent as `currentClue.answer` in `REVEALING_ANSWER`. A correct answer matches that response exactly (§6.2), so its text is public at the same moment. A wrong answer, however, is shown to everyone while other players can still buzz, as on the TV show.

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
```

### 6.2 Rules

- **Start:** the first player to join picks the first clue.
- **Choosing a clue:** the player in control has 30 s to choose. If they don't, the server chooses for them: the top-most available clue (lowest row), leftmost category first. It's played exactly as if they had chosen it, so it can be a Daily Double, and they keep control. This keeps the game moving if the chooser has left.
- **Scoring:** a correct answer adds the clue's value and gives that player control of the board. A wrong answer, or running out of time to answer, subtracts it. Not buzzing costs nothing.
- **Wrong answers:** the other players who haven't buzzed yet can buzz. When everyone has buzzed, the answer is revealed.
- **Rounds:** when every clue in a round has been played, the next round starts. Rounds with no clues are skipped. After Double Jeopardy comes Final Jeopardy if the game has one; otherwise the game finishes.
- **Daily Double:** only the player who chose it plays. Their wager must be between 5 and the larger of their score and the round's highest clue value (normally 1000 in Jeopardy and 2000 in Double Jeopardy). Not wagering in time means a wager of 5. Right or wrong, the wager is added or subtracted, and the chooser keeps control.
- **Final Jeopardy:**
  - Every player wagers between 0 and their score. A score of 0 or less can only wager 0, but that player still plays.
  - Wagers and answers can be changed until their phase ends. Phases always run their full time.
  - A missing wager counts as 0, and a missing answer counts as wrong.
  - Scores change only after the reveal.
- **Answer checking is currently an exact, case-sensitive string match** against the stored answer. Lenient matching is planned (§9).

### 6.3 Timings (server defaults)

| Phase | Duration |
|---|---|
| Choosing a clue (`CHOOSING_CLUE`) | 30 s |
| Clue reveal (`REVEALING_CLUE`) | 3 s |
| Daily Double wager | 30 s |
| Buzzer (`WAITING_FOR_BUZZER`) | 10 s |
| Answer (`WAITING_FOR_ANSWER`) | 5 s |
| Answer reveal (`REVEALING_ANSWER`) | 3 s |
| Final Jeopardy wagering | 30 s |
| Final Jeopardy answering | 30 s |
| Final Jeopardy reveal | 10 s |

`WAITING_FOR_START` and `FINISHED_GAME` have no timer.

Every response (broadcast and `GET /api/game-sessions/{id}`) carries the current phase's timing:

- `phaseDurationMs`: the full length of the phase, for example `10000` in `WAITING_FOR_BUZZER`.
- `phaseRemainingMs`: how much of it was left when the server built the response, from `0` up to `phaseDurationMs`.
- Both are `null` in a state without a timer.

They're durations, not timestamps, so the client's clock doesn't need to match the server's. To show a countdown, note your own time when the response arrives (`performance.now()`) and count down from `phaseRemainingMs`. It will end late by the network delay of that response, which is usually small. Final Jeopardy wagers and answers don't restart their phase: their broadcasts show the same deadline with less time left. The phase can end a few milliseconds after the countdown reaches 0, when the next broadcast arrives.

---

## 7. Errors (`/user/queue/errors`)

`{ "code": GameErrorCode, "message": string }`. The message is human-readable and can be shown to the player.

| Code | Typical cause |
|---|---|
| `UNAUTHENTICATED` | Acting on a connection without a player token |
| `PLAYER_NOT_IN_SESSION` | Your token belongs to a different session |
| `SESSION_NOT_FOUND` | The session no longer exists |
| `INVALID_STATE` | The action isn't allowed in the current state (for example, buzzing before the clue is revealed) |
| `NOT_YOUR_TURN` | Another player is choosing or answering, or you already buzzed on this clue |
| `CLUE_NOT_FOUND` | That clue isn't on this round's board |
| `CLUE_ALREADY_PLAYED` | That clue was already chosen |
| `INVALID_WAGER` | The wager is outside the allowed range (§6.2) |
| `INVALID_REQUEST` | The payload is missing a field or malformed (the message names the field) |

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
  | "FINISHED_GAME";

type GameErrorCode =
  | "SESSION_NOT_FOUND"
  | "NOT_YOUR_TURN"
  | "INVALID_STATE"
  | "CLUE_NOT_FOUND"
  | "CLUE_ALREADY_PLAYED"
  | "PLAYER_NOT_IN_SESSION"
  | "INVALID_WAGER"
  | "INVALID_REQUEST"
  | "UNAUTHENTICATED";

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
interface CreateGameSessionRequest { gameId: number; }
interface CreateGameSessionResponse { id: string; }

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
// start and buzz have no body

// ---------- WebSocket: state ----------
interface PlayerResponse {
  id: number;
  name: string;
  score: number;
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
  answer: string | null;
}

interface ClueAnswerResponse {
  playerId: number;
  answer: string | null;    // null: ran out of time
  correct: boolean;
}

interface FinalJeopardyPlayerResponse {
  playerId: number;
  hasWagered: boolean;
  hasAnswered: boolean;
  wager: number | null;     // revealed in FINAL_JEOPARDY_REVEALING
  answer: string | null;    // revealed in FINAL_JEOPARDY_REVEALING
  correct: boolean | null;  // revealed in FINAL_JEOPARDY_REVEALING
}

interface FinalJeopardyResponse {
  category: string;
  categoryComments: string | null;
  question: string | null;
  answer: string | null;
  players: FinalJeopardyPlayerResponse[];
}

interface GameSessionResponse {
  id: string;
  version: number;       // keep the highest one seen
  round: GameRound;
  state: GameSessionState;
  phaseDurationMs: number | null;   // null: no timer in this state
  phaseRemainingMs: number | null;  // measured when the server built the response
  players: PlayerResponse[];
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
- **Exact answer matching** (§6.2). Answers must match the stored text exactly, including capitalisation.
- **Timeouts aren't configurable** (§6.3).
- **Sessions are never cleaned up** and are lost when the backend restarts, along with every token.
- **No limits** on the number of players, duplicate names or name length.
