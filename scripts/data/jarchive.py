"""Downloads every game from J-Archive and imports them into the database.

"download" saves J-Archive's pages to jarchive-pages/ in the project root,
which isn't committed:

    seasons_list.html                      the list of seasons
    season_<season>/episodes_list.html     a season's list of games
    season_<season>/episode_<id>.html      a game, by J-Archive's game id

Every run downloads the seasons list again, and a season's list when its
number of games has changed. A game's page is only downloaded once. J-Archive's
robots.txt asks for 20 seconds between requests, so the first download of all
of it (about 9,500 games) takes more than two days; stop it any time, and the
next run picks up where it left off.

"import" reads only the saved pages. It reads and checks all of them first,
then inserts the games that aren't in the database yet (by title), oldest
first, so into empty tables the game ids follow the air dates. Each game is
committed on its own, so a run that stops can be started again.

Usage (from the project root):
    python scripts/data/jarchive.py                    # download, then import
    python scripts/data/jarchive.py download
    python scripts/data/jarchive.py import --dry-run   # say what it would import
    python scripts/data/jarchive.py import

Needs psycopg, beautifulsoup4 and requests. Reads DATABASE_URL,
DATABASE_USERNAME and DATABASE_PASSWORD from .env (or --env PATH). After an
import, run the cleanup steps in README.md.
"""

import argparse
import re
import sys
import time
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from pathlib import Path

import requests
from bs4 import BeautifulSoup, SoupStrainer

from answers import connect, find_env

SCRIPT_DIR = Path(__file__).resolve().parent
PAGES_FOLDER = "jarchive-pages"

BASE_URL = "https://j-archive.com/"
SEASONS_LIST = "seasons_list.html"
EPISODES_LIST = "episodes_list.html"
# J-Archive's robots.txt: "Crawl-delay: 20".
DEFAULT_DELAY = 20.0

MEDIA_TYPES = {
    "mp4": "VIDEO", "mov": "VIDEO", "wmv": "VIDEO",
    "jpg": "IMAGE", "png": "IMAGE",
    "mp3": "SOUND", "mp5": "SOUND",  # one clue links an "mp5"
}
# Links to media without an extension.
MEDIA_WITHOUT_EXTENSION = {"http://www.j-archive.com/media/2001-04-02_DJ_29": "IMAGE"}

# "clue_J_3_2" is column 3, row 2 of the Jeopardy round; "clue_FJ" is Final
# Jeopardy. A tiebreaker ("clue_TB") isn't part of the game.
CLUE_POSITION = re.compile(r"clue_(?:(J|DJ)_(\d+)_(\d+)|FJ)")
FIRST_COLUMN = {"J": 1, "DJ": 7}
FINAL_COLUMN = 13
ROUNDS = (("jeopardy_round", 1), ("double_jeopardy_round", 7), ("final_jeopardy_round", FINAL_COLUMN))


def find_pages():
    """The nearest jarchive-pages folder in the project root or a folder above
    it, so a git worktree (.claude/worktrees/<name>) uses the main checkout's.
    Without one, a new one in the project root."""
    project = SCRIPT_DIR.parent.parent
    for folder in [project, *project.parents]:
        if (folder / PAGES_FOLDER).is_dir():
            return folder / PAGES_FOLDER
    return project / PAGES_FOLDER


def read_content(path):
    """The page's content div, the only part of a page this script reads."""
    with open(path, encoding="utf-8") as file:
        page = BeautifulSoup(file, "html.parser", parse_only=SoupStrainer("div", id="content"))
    return page.find("div", id="content")


# --- Download -----------------------------------------------------------------

class Downloader:
    """Saves pages, waiting `delay` seconds between requests."""

    def __init__(self, delay):
        self.delay = delay
        self.session = requests.Session()
        self.last_request = None

    def save(self, url, path):
        if self.last_request is not None:
            time.sleep(max(0.0, self.last_request + self.delay - time.monotonic()))
        self.last_request = time.monotonic()
        response = self.session.get(url, timeout=30)
        response.raise_for_status()
        path.parent.mkdir(parents=True, exist_ok=True)
        # Written next to it first, so a download that stops halfway never
        # leaves a page that looks complete.
        partial = path.with_name(path.name + ".part")
        partial.write_text(response.text, encoding="utf-8", newline="")
        partial.replace(path)


def parse_seasons_list(content):
    """(season, number of games archived) for every season, as J-Archive lists them."""
    seasons = []
    for row in content.find_all("tr"):
        link = row.find("a", href=re.compile(r"showseason\.php\?season="))
        if link is None:
            continue
        archived = re.search(r"\((\d+) games? archived\)", row.get_text())
        seasons.append((link["href"].split("=", 1)[1], int(archived[1]) if archived else None))
    return seasons


def parse_episodes_list(content):
    """The J-Archive game ids in a season's list."""
    return [
        link["href"].split("=", 1)[1]
        for cell in content.table.find_all("td", align="left")
        for link in cell.find_all("a", href=re.compile(r"showgame\.php\?game_id="))
    ]


def download(pages, delay):
    downloader = Downloader(delay)
    print(f"Downloading to {pages}")
    downloader.save(BASE_URL + "listseasons.php", pages / SEASONS_LIST)

    missing = []
    for season, archived in parse_seasons_list(read_content(pages / SEASONS_LIST)):
        folder = pages / f"season_{season}"
        episodes_list = folder / EPISODES_LIST
        if not episodes_list.is_file() or len(parse_episodes_list(read_content(episodes_list))) != archived:
            print(f"Downloading the list of season {season}")
            downloader.save(f"{BASE_URL}showseason.php?season={season}", episodes_list)
        for game_id in parse_episodes_list(read_content(episodes_list)):
            page = folder / f"episode_{game_id}.html"
            if not page.is_file():
                missing.append((f"{BASE_URL}showgame.php?game_id={game_id}", page))

    hours = len(missing) * delay / 3600
    print(f"{len(missing)} games to download (about {hours:.1f} hours)")
    for number, (url, page) in enumerate(missing, 1):
        downloader.save(url, page)
        print(f"Downloaded {page.relative_to(pages)} ({number} of {len(missing)})")


# --- Parse --------------------------------------------------------------------

def is_broken_clue(question, answer):
    # J-Archive shows "=" (or a row of dashes, or a lone "(") where it has no
    # clue or response. Same rule as clean-clues.sql.
    return re.fullmatch(r"[\s=-]*", answer) is not None or re.fullmatch(r"[\s=(-]*", question) is not None


def parse_clue(cell):
    """A clue cell as a dict, or None for an empty, broken or tiebreaker clue."""
    question_cell = cell.find("td", class_="clue_text")
    if question_cell is None:
        return None
    position = CLUE_POSITION.fullmatch(question_cell.get("id", ""))
    if position is None:
        return None
    if position[1] is None:
        column, row = FINAL_COLUMN, 1
    else:
        column, row = FIRST_COLUMN[position[1]] + int(position[2]) - 1, int(position[3])

    answer_tag = cell.find("em", class_="correct_response")
    answer = answer_tag.get_text() if answer_tag else ""
    if is_broken_clue(question_cell.get_text(), answer):
        return None

    clue_type, media_url = "TEXT", None
    # The game has one media file per clue: the first link.
    link = question_cell.find("a")
    if link:
        media_url = str(link.get("href"))
        clue_type = MEDIA_WITHOUT_EXTENSION.get(media_url) or MEDIA_TYPES.get(media_url.rsplit(".", 1)[-1].lower())
        if clue_type is None:
            raise ValueError(f"unknown media type: {media_url}")

    return {
        "column": column,
        "row": row,
        # HTML, as J-Archive writes it: it can hold links and line breaks.
        "question": question_cell.decode_contents(),
        "answer": answer,
        "type": clue_type,
        "media_url": media_url,
        "daily_double": cell.find("td", class_="clue_value_daily_double") is not None,
    }


def parse_game(content, season, jarchive_id):
    """A game page as a dict ready to insert, or None for a page without a game."""
    title_tag = content.find("div", id="game_title")
    if title_tag is None:
        return None
    # "Show #9615 - Friday, July 24, 2026"
    title, _, aired = title_tag.get_text().rpartition("-")
    game = {
        "title": title.strip(),
        "date": datetime.strptime(aired.strip(), "%A, %B %d, %Y").date(),
        "season": season,
        "jarchive_id": jarchive_id,
        "categories": {},
        "clues": [],
    }
    for round_id, first_column in ROUNDS:
        board = content.find("div", id=round_id)
        if board is None:
            continue
        # Only the first table: a tiebreaker has its own.
        table = board.find("table")
        for column, category in enumerate(table.find_all(class_="category"), first_column):
            comments = category.find(class_="category_comments")
            game["categories"][column] = {
                "name": category.find(class_="category_name").get_text().strip(),
                "comments": (comments.get_text().strip() if comments else "") or None,
            }
        game["clues"] += filter(None, map(parse_clue, table.find_all("td", class_="clue")))

    for clue in game["clues"]:
        if clue["column"] not in game["categories"]:
            raise ValueError(f"a clue in column {clue['column']}, which has no category")
    return game


def read_page(path):
    """The game on a saved page, or None. Runs in a worker process."""
    season = path.parent.name.removeprefix("season_")
    jarchive_id = int(path.stem.removeprefix("episode_"))
    try:
        return parse_game(read_content(path), season, jarchive_id)
    except Exception as e:
        raise ValueError(f"Can't read {path}: {e!r}") from None


def read_games(pages):
    """Every saved game, oldest first. Games aired the same day are in J-Archive's order."""
    games = []
    paths = sorted(pages.glob("season_*/episode_*.html"))
    print(f"Reading {len(paths)} pages in {pages}")
    # Parsing takes about a tenth of a second per page, so it's spread over every core.
    with ProcessPoolExecutor() as pool:
        try:
            for number, (path, game) in enumerate(zip(paths, pool.map(read_page, paths, chunksize=50)), 1):
                if game is None:
                    print(f"No game in {path}")
                else:
                    games.append(game)
                if number % 1000 == 0:
                    print(f"Read {number} of {len(paths)}")
        except ValueError as e:
            sys.exit(str(e))
    games.sort(key=lambda game: (game["date"], game["jarchive_id"]))
    return games


# --- Import -------------------------------------------------------------------

def insert_game(cursor, game):
    """Inserts the game with its categories, clues and Daily Doubles."""
    cursor.execute(
        "insert into game (game_date, season, title) values (%s, %s, %s) returning id",
        (game["date"], game["season"], game["title"]),
    )
    game_id = cursor.fetchone()[0]
    category_ids = {}
    for column, category in game["categories"].items():
        cursor.execute(
            "insert into category (name, column_number, comments, game_id) values (%s, %s, %s, %s) returning id",
            (category["name"], column, category["comments"], game_id),
        )
        category_ids[column] = cursor.fetchone()[0]
    for clue in game["clues"]:
        cursor.execute(
            "insert into clue (question, answer, row_number, type, media_url, category_id)"
            " values (%s, %s, %s, %s, %s, %s) returning id",
            (clue["question"], clue["answer"], clue["row"], clue["type"], clue["media_url"], category_ids[clue["column"]]),
        )
        clue_id = cursor.fetchone()[0]
        if clue["daily_double"]:
            cursor.execute("insert into game_daily_double (game_id, clue_id) values (%s, %s)", (game_id, clue_id))


def import_games(pages, env_path, dry_run):
    games = read_games(pages)

    # A title is unique in the database. The oldest game with it wins.
    by_title = {}
    for game in games:
        by_title.setdefault(game["title"], []).append(game)
    for title, same in by_title.items():
        if len(same) > 1:
            pages_list = ", ".join(f"season_{g['season']}/episode_{g['jarchive_id']}" for g in same)
            print(f"{len(same)} games are titled {title!r}; only the first is imported: {pages_list}")

    with connect(env_path, read_only=dry_run) as conn:
        conn.autocommit = True
        existing = {title for (title,) in conn.execute("select title from game")}
        newest = conn.execute("select max(game_date) from game").fetchone()[0]
        new = [same[0] for title, same in by_title.items() if title not in existing]

        types = Counter(clue["type"] for game in new for clue in game["clues"])
        print(f"{len(games)} games read, {len(new)} not in the database yet, "
              f"with {sum(types.values())} clues ({', '.join(f'{n} {t}' for t, n in types.most_common())})")
        older = sum(1 for game in new if newest is not None and game["date"] < newest)
        if older:
            print(f"{older} of them aired before the newest game in the database ({newest}), "
                  "so their ids won't follow the air dates (see README.md to import into empty tables)")
        if dry_run:
            return

        for number, game in enumerate(new, 1):
            with conn.transaction(), conn.cursor() as cursor:
                insert_game(cursor, game)
            if number % 100 == 0 or number == len(new):
                print(f"Imported {number} of {len(new)} ({game['date']}, {game['title']})")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", nargs="?", choices=["download", "import"],
                        help="only download, or only import (default: both)")
    parser.add_argument("--pages", type=Path, help=f"the saved pages (default: the nearest {PAGES_FOLDER} folder)")
    parser.add_argument("--env", type=Path, help="path to the .env file (default: the nearest one above this script)")
    parser.add_argument("--delay", type=float, default=DEFAULT_DELAY,
                        help=f"seconds between requests to J-Archive (default: {DEFAULT_DELAY:g})")
    parser.add_argument("--dry-run", action="store_true", help="read the pages and say what would be imported")
    args = parser.parse_args()
    pages = args.pages or find_pages()

    if args.command in (None, "download"):
        download(pages, args.delay)
    if args.command in (None, "import"):
        import_games(pages, args.env or find_env(), args.dry_run)


if __name__ == "__main__":
    main()
