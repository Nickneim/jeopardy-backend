# Jeopardy! backend

The server for a multiplayer Jeopardy! game you play in the browser with friends. One player creates a session from any of thousands of real games (imported from [J-Archive](https://j-archive.com)) or from a custom show they wrote, shares the 5-character code, and everyone plays live: choosing clues, buzzing in, wagering on Daily Doubles and Final Jeopardy, with answers checked automatically.

The React client is in a separate repository: **[jeopardy-frontend](https://github.com/Nickneim/jeopardy-frontend)**.

**Live demo:** <https://d14x3ygmehrd5x.cloudfront.net>. The server is started on demand to keep costs down, so the page usually says it's offline.

## Features

- **Real-time multiplayer over WebSockets (STOMP).** The server is authoritative: after every change it sends each player the full game state, and players render whatever arrives. Anyone can watch a session without joining.
- **The full show.** Jeopardy, Double Jeopardy and Final Jeopardy rounds, round title cards, Daily Doubles with wager limits, a buzzer with lockouts, and a Final Jeopardy reveal that goes through the players one at a time, lowest score first.
- **Lenient answer checking.** Responses are matched against the stored answer while tolerating typos, optional words, alternative wordings and "name N of these" lists. From Double Jeopardy on they must be phrased as a question. The rules are pinned down by a suite of test cases built from real clues.
- **Host as Trebek.** The host can run the show instead of playing: revealing clues, ruling on answers, adjusting scores and pausing the clock. Secrets such as unrevealed answers only go to the host's connection.
- **Custom shows.** Write your own board in the client and play it without storing anything. It's validated with specific feedback for each mistake.
- **Robust sessions.** Players reconnect with a secret token after a refresh. Every phase is on a server-side timer, so an absent player can't stall the game. Players can join mid-game, the host role passes on when the host leaves, and abandoned sessions are cleaned up.
- **Lobby and game browser.** Live sessions can be filtered, sorted and paged. Imported games can be browsed by season, title and air date.
- **Horizontal scaling.** Sessions live in memory, and Kubernetes manifests route each session to the instance that holds it. The lobby gathers sessions from every instance.

## Tech stack

| Area | Technology |
| --- | --- |
| Language and framework | Java 21, Spring Boot 4 (Web MVC, WebSocket/STOMP, Data JPA, Validation, Actuator) |
| Database | PostgreSQL 17, schema managed with Flyway, Hibernate in validate-only mode |
| Testing | JUnit 5, Mockito, Testcontainers (a throwaway Postgres for every run), STOMP client integration tests, ~800 tests |
| Build | Gradle (wrapper included), Cloud Native Buildpacks for the container image |
| Local infrastructure | Docker Compose, Kubernetes manifests (ingress-nginx) for multi-replica runs |
| Deployment | AWS: EC2, ECR, CloudFront, S3, SSM Parameter Store, CloudWatch; GitHub Actions with OIDC (no stored AWS keys) |
| Data import | Python scripts that scrape J-Archive (politely: 20 s between requests, per its `robots.txt`) and clean up the answers |

## How it works

```
Browser (React)  ──REST──▶  /api/games, /api/game-sessions     browse games, create and join sessions
                 ◀─STOMP─▶  /ws                                 play: actions in, full game state out
                                │
                Spring Boot ────┤  game:     games, categories and clues (JPA → PostgreSQL)
                                └  session:  live sessions in memory, one state machine per game
```

- **`game`**: read-only REST access to the imported games.
- **`session`**: everything live. `GameSession` is a plain Java state machine that holds every rule (`WAITING_FOR_START → ROUND_INTRO → CHOOSING_CLUE → … → FINAL_JEOPARDY_* → FINISHED_GAME`). `GameSessionService` handles locking, timers and broadcasts. Sessions play from an immutable copy of the game, never from database entities.
- **Identity** comes from the connection, never from a message. Joining returns a secret token, and the client sends it once when it connects. Tokens are never broadcast or logged.

The complete API (every endpoint, message, field, state, timing and error, plus TypeScript types) is in **[docs/api.md](docs/api.md)**, the contract between this backend and the frontend.

## Running it locally

### Requirements

- **JDK 21** (Gradle itself comes with the wrapper)
- **Docker** (Docker Desktop on Windows/macOS) for the database and the tests
- **Python 3**, only to import games
- **Node.js and pnpm**, only to run the frontend

### 1. Start the database

```bash
git clone https://github.com/Nickneim/jeopardy-backend.git
cd jeopardy-backend
cp .env.example .env          # then set DATABASE_PASSWORD to anything you like
docker compose up -d          # Postgres 17 on localhost:5433
```

`.env` is git-ignored. Docker Compose uses it to create the database user, and the app reads it to connect.

### 2. Run the backend

```bash
./gradlew bootRun             # Windows PowerShell: .\gradlew.bat bootRun
```

The server listens on `http://localhost:8080`. Flyway creates the schema on the first start. You can also run `JeopardyApplication` from your IDE, from the project root, so it finds `.env`.

### 3. Import some games (optional)

Without imported games you can still play **custom shows**. To get real games:

```bash
pip install "psycopg[binary]" beautifulsoup4 requests
python scripts/data/jarchive.py download    # stop it with Ctrl+C whenever you have enough
python scripts/data/jarchive.py import
```

The download waits 20 seconds between pages, so all of J-Archive takes days, but a few minutes already gives you a handful of games, and the next run picks up where it stopped. [scripts/data/README.md](scripts/data/README.md) covers the clean-up steps that make more answers match.

### 4. Run the frontend

```bash
git clone https://github.com/Nickneim/jeopardy-frontend.git
cd jeopardy-frontend
pnpm install
pnpm dev                      # http://localhost:5173
```

The dev server forwards `/api` and `/ws` to the backend on `http://localhost:8080`. Open <http://localhost:5173>, create a session and open the same address in another tab to join as a second player. If the frontend is served from any other origin, list it in the backend's `ALLOWED_ORIGINS` (comma-separated).

## Tests

The tests start their own Postgres with Testcontainers, so they need Docker running but not the Compose database or `.env`, and they never touch your games.

```bash
./gradlew test                                      # everything
./gradlew test --tests "*GameSessionTest*"          # one class
scripts/test.sh                                     # bash: quiet run that prints only failures (full log in build/test-output.log)
python -m unittest discover -s scripts/data         # the import scripts' tests
```

## Deployment

- **Kubernetes** (`k8s/`): runs any number of replicas on a local cluster. An ingress hashes each request's `session` parameter, so a session always reaches the pod that holds it. See [k8s/README.md](k8s/README.md).
- **AWS** (`deploy/aws/`): the live demo. A single EC2 instance runs the backend and Postgres with Docker Compose, starts on demand and stops itself when idle. CloudFront serves the frontend from S3 and forwards `/api` and `/ws` to the instance. A GitHub Actions workflow tests every push to `main`, publishes the image to ECR and restarts the backend, but never while a game is running.

## Project layout

```
src/main/java/com/nickneim/jeopardy/
  game/        REST API for imported games (entities, repositories, filters)
  session/     live sessions: model (state machine), service, answer checking,
               board, custom shows, lobby, security (tokens), STOMP controllers
  config/      WebSocket, CORS and clock configuration
src/main/resources/db/migration/   Flyway migrations
docs/api.md                        the REST and STOMP contract
scripts/data/                      J-Archive import and data clean-up
k8s/, deploy/aws/, compose.yaml    infrastructure
```

## Disclaimer

This is a personal, non-commercial project. Jeopardy! is a trademark of Jeopardy Productions, Inc., and this project has no affiliation with it or with J-Archive. The repository doesn't include any game data: you download it yourself with the import script.
