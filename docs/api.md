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
- **Timers run on the server.** Phases such as revealing a clue or waiting for buzzes end automatically. The client just renders whatever state arrives. See §6.3 for the durations.

---

## 3. REST API

All bodies are JSON. Errors use `ApiErrorResponse` (`{ "status": 404, "message": "..." }`). The exception is request validation errors (such as a missing or blank field), which currently return Spring's default 400 error body.

### 3.1 Browsing games

#### `GET /api/games`

A paged list of games, newest first (by `gameDate`, then `id`).

| Query param | Default | Rules |
|---|---|---|
| `page` | `0` | ≥ 0 (0-based) |
| `size` | `20` | 1–100 |
| `season` | (none) | Optional exact filter, for example `"40"` |

Response: `PageResponse<GameSummaryResponse>`. An invalid `page` or `size` returns 400 with the message `"Invalid request parameters"`.

#### `GET /api/games/seasons`

Every season that has at least one game, with its game count, for example to fill a season filter. Not paged. Response: `SeasonResponse[]`, for example `[{ "season": "42", "gameCount": 230 }, …]`.

Order, newest first:

1. Numbered seasons (`season` is all digits), by number from highest to lowest. They're compared as numbers, so `"10"` comes before `"9"`.
2. Then special seasons whose `season` isn't a number (such as `"jm"` or `"pcj"`: tournaments and other specials), by their most recent `gameDate`, newest first, then by name.

Pass a `season` value unchanged as the `season` filter of `GET /api/games`.

#### `GET /api/games/{id}`

A full game with every category and clue. **It includes answers and Daily Double locations**, which is fine for browsing and single-player use, but never send it to other players in a multiplayer game. Response: `GameResponse`. Unknown id: `404 { "status": 404, "message": "Game not found: 999" }`.

#### `GET /api/clues/{id}`

One clue. Response: `ClueResponse`. Unknown id: 404.

### 3.2 Game sessions

#### `POST /api/game-sessions`

Creates a live session from a stored game.

- Request: `{ "gameId": 123 }`
- `201 { "id": "8f6c…-uuid" }`
- Unknown game: 404.

#### `GET /api/game-sessions/{sessionId}`

The session's current state: the same `GameSessionResponse` as the latest broadcast, including its `version`. Use it to catch up after connecting, refreshing or reconnecting (§2). No token is needed, because the state never contains secrets. Unknown session: 404.

#### `POST /api/game-sessions/{sessionId}/players`

Joins a session.

- Request: `{ "playerName": "Nico" }` (must not be blank)
- `201 { "playerId": 1, "token": "Xk3…43 chars" }`. Players are numbered 1, 2, 3… within each session, so ids are only unique inside a session.
- Unknown session: `404 { "status": 404, "message": "Game session not found: <id>" }`
- Blank name: 400.

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
| `players` | `{ id, name, score }` in join order |
| `playerChoosingClue` | Whose turn it is to pick. `null` before the game starts. Correct answers take control of the board. |
| `playerAnswering` | Who must answer right now. Only set in `WAITING_FOR_ANSWER` (the buzzer, or the chooser on a Daily Double); otherwise `null`. |
| `playersLockedOut` | Players who already buzzed on this clue and can't buzz again. Only filled in `WAITING_FOR_BUZZER` and `WAITING_FOR_ANSWER`; otherwise `[]`. |
| `board` | The current round's grid (§5.1). Never contains questions, answers or Daily Double locations. |
| `currentClue` | The clue in play (§5.2), or `null` |
| `finalJeopardy` | Final Jeopardy status (§5.3), or `null` |

### 5.1 `board`

A list of categories in column order. Each category has one slot per row, from 1 up to the highest row used anywhere in the round, so the grid is always rectangular.

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
| `answer` | Only in `REVEALING_ANSWER` |

### 5.3 `finalJeopardy`

Non-null during the Final Jeopardy states, and in `FINISHED_GAME` if the game had a Final round (so the results stay visible).

| Field | Shown from |
|---|---|
| `category` | `FINAL_JEOPARDY_WAGERING` |
| `question` | `FINAL_JEOPARDY_ANSWERING` |
| `answer` (the correct response) | `FINAL_JEOPARDY_REVEALING` |
| `players[]` → `playerId`, `hasWagered`, `hasAnswered` | always |
| `players[]` → `wager`, `answer`, `correct` | `FINAL_JEOPARDY_REVEALING` (before that they're `null`, so nobody can see anyone else's) |

---

## 6. States, rules and timings

### 6.1 State machine

```
WAITING_FOR_START
   │ start
   ▼
CHOOSING_CLUE ◄──────────────────────────────────────────────┐
   │ choose-clue                                             │
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
| Clue reveal (`REVEALING_CLUE`) | 3 s |
| Daily Double wager | 30 s |
| Buzzer (`WAITING_FOR_BUZZER`) | 10 s |
| Answer (`WAITING_FOR_ANSWER`) | 5 s |
| Answer reveal (`REVEALING_ANSWER`) | 3 s |
| Final Jeopardy wagering | 30 s |
| Final Jeopardy answering | 30 s |
| Final Jeopardy reveal | 10 s |

These aren't sent to the client yet. To show countdowns, use these values and start counting when a broadcast enters the state.

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
  slots: ClueSlotResponse[];
}

interface CurrentClueResponse {
  clueId: number;
  value: number;
  dailyDouble: boolean;
  wager: number | null;
  question: string | null;
  answer: string | null;
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
  question: string | null;
  answer: string | null;
  players: FinalJeopardyPlayerResponse[];
}

interface GameSessionResponse {
  id: string;
  version: number;       // keep the highest one seen
  round: GameRound;
  state: GameSessionState;
  players: PlayerResponse[];
  playerChoosingClue: number | null;
  playerAnswering: number | null;
  playersLockedOut: number[];
  board: BoardCategoryResponse[];
  currentClue: CurrentClueResponse | null;
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
- **Media clues aren't playable in sessions.** `currentClue` has no `clueType` or `mediaUrl` yet. Browsing (`GET /api/games/{id}`) does include them.
- **Exact answer matching** (§6.2). Answers must match the stored text exactly, including capitalisation.
- **Timeouts aren't configurable or sent to clients** (§6.3).
- **Sessions are never cleaned up** and are lost when the backend restarts, along with every token.
- **No limits** on the number of players, duplicate names or name length.
