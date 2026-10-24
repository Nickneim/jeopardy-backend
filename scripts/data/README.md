# Game data scripts

`jarchive.py` downloads the games from [J-Archive](https://j-archive.com) and imports them. The other scripts check and fix the imported data. Each one matches clues by their content, never by id, so they work again after a re-import and do nothing on a second run.

`psql.sh` connects with the `DATABASE_*` values from `.env` (or `ENV_FILE`). Its sessions are read-only unless `WRITE=1` is set, and every script is a dry run unless you also pass `-v commit=1` (or `--commit`). Back up first:

```bash
pg_dump -h localhost -p 5433 -U postgres -d jeopardy -Fc -f jeopardy-YYYY-MM-DD.dump
```

Port 5433 is the Docker database (`compose.yaml`). Without a local `pg_dump`, run it inside the container and copy the file out: `docker exec jeopardy-db-1 pg_dump -U postgres -d jeopardy -Fc -f /tmp/backup.dump`, then `docker cp jeopardy-db-1:/tmp/backup.dump .`.

## Import

`jarchive.py` needs `pip install "psycopg[binary]" beautifulsoup4 requests`. It saves J-Archive's pages to `jarchive-pages/` in the project root (git-ignored; from a worktree it uses the main checkout's), then imports from the saved pages only:

```bash
python scripts/data/jarchive.py                    # download, then import
python scripts/data/jarchive.py download           # only download
python scripts/data/jarchive.py import --dry-run   # read every page, say what would be imported
python scripts/data/jarchive.py import             # only import
```

The download waits 20 seconds between requests, as J-Archive's `robots.txt` asks (`--delay` changes it). It downloads the seasons list every time, and a season's list when its number of games changed, then every game it doesn't have yet. All of J-Archive takes more than two days; stop it whenever you like, and the next run continues.

The import reads and checks every page before it writes anything, then inserts the games whose title isn't in the database yet, oldest first, each in its own transaction. Game ids follow the air dates only when the tables start empty; the import says when a game is older than the newest one already there. To import everything again in order, back up (above), empty the tables, import and run the steps under "After an import":

```bash
WRITE=1 scripts/data/psql.sh -c "truncate game, category, clue, game_daily_double restart identity"
python scripts/data/jarchive.py import
```

## After an import

Run these in order. The answer rewrites are keyed by the answer text as the earlier steps leave it.

1. **Broken clues.** The importer skips them (`is_broken_clue` in `jarchive.py`). For data imported before that, `clean-clues.sql` lists them and can delete them:
   ```bash
   scripts/data/psql.sh -f scripts/data/clean-clues.sql
   ```
2. **Square brackets to parentheses.** J-Archive uses both for the same things, and the answer matcher reads only parentheses:
   ```bash
   scripts/data/psql.sh -f scripts/data/brackets-to-parentheses.sql
   WRITE=1 scripts/data/psql.sh -v commit=1 -f scripts/data/brackets-to-parentheses.sql
   ```
3. **Alternatives and lists.** `answers.py apply` rewrites simple alternatives by a rule (`Myanmar (or Burma)` → `(1 of) Myanmar | Burma`), then applies the reviewed rewrites in `answer-rewrites.csv` (`Santa Claus or Father Christmas` → `(1 of) Santa Claus | Father Christmas`):
   ```bash
   python scripts/data/answers.py apply
   python scripts/data/answers.py apply --commit
   ```
   Answers that still need a decision, such as new ones from an import, show up in the review page, which opens in the browser and saves each decision to `answer-rewrites.csv` right away:
   ```bash
   python scripts/data/answers.py review
   ```

## Answer format

- `(N of) A | B | C`: a correct response names N of the options; `(1 of)` means any one. No answer contains `|`.
- `(X)`: an optional part, e.g. `(Abraham) Lincoln`.

## The rule

`answers.py apply` only rewrites an alternative by itself when it clearly replaces the whole answer: `Myanmar (or Burma)`. When it may replace only part of it (after an `&`/`and` list or a comma, as in `coroner & coronet (or coronel)`, or when it has fewer words, as in `a linen closet (or cupboard)`), the answer goes to review instead, where the alternative is suggested as a different whole answer. The tests (no database needed):

```bash
python -m unittest discover -s scripts/data
```
