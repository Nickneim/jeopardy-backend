"""Rewrites J-Archive answers so alternatives and lists have one form.

Alternatives and lists are written "(N of) A | B | C", where a correct
response names N of the options: "(1 of) Santa Claus | Father Christmas"
means either one. Answers contain commas, "&" and "or", but never "|", so the
separator can't be mistaken for part of an option. A parenthesized part that
isn't an alternative stays an optional part: "(Abraham) Lincoln".

J-Archive writes these many ways: "Santa Claus or Father Christmas",
"Myanmar (or Burma)", "Pagliacci (I Pagliacci accepted)", "(2 of) A, B & C",
"(one of) A or B", "A, B &/or C" with "2 of the 3" in the clue.

- Simple cases are rewritten by a rule: one alternative at the end, as in
  "Myanmar (or Burma)" or "Pagliacci (I Pagliacci accepted)".
- The rest need a person: a bare "or" is often part of the answer ("To Be Or
  Not To Be"), and a comma may be part of a title. "review" opens a page in
  the browser to decide them one at a time. Each decision is saved to
  answer-rewrites.csv right away, keyed by the answer text.

"apply" runs the rule and then the reviewed rewrites, so after a re-import the
same command brings the answers back to where they were. Run it after
brackets-to-parentheses.sql (see README.md).

Usage (from the project root):
    python scripts/data/answers.py review             # review in the browser
    python scripts/data/answers.py apply              # dry run: show what would change
    python scripts/data/answers.py apply --commit     # update the database

Reads DATABASE_URL, DATABASE_USERNAME and DATABASE_PASSWORD from .env (or
--env PATH). Every session is read-only except "apply --commit".
"""

import argparse
import csv
import html
import json
import os
import re
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import psycopg

SCRIPT_DIR = Path(__file__).resolve().parent
DECISIONS_FILE = SCRIPT_DIR / "answer-rewrites.csv"
REVIEW_PAGE = SCRIPT_DIR / "answers-review.html"
FIELDS = ["answer", "decision", "new_answer"]

# An "or" outside parentheses. Uppercase "OR" is left alone (Oregon).
BARE_OR = re.compile(r"\b(?!OR\b)[oO][rR]\b")
# A parenthesized part. Square brackets count too, though the database's are
# turned into parentheses (brackets-to-parentheses.sql).
PARENS = re.compile(r"\([^)]*\)?|\[[^\]]*\]?")
AND_OR = re.compile(r"(and|&)\s*/\s*or", re.I)
# Parentheses that hold an alternative: "(or X)", "(X accepted)", "(also X)".
ALTERNATIVE_GROUP = re.compile(r"\(\s*(?:or|also)\s[^()]*\)|\([^()]*\baccept[^()]*\)", re.I)
# The start of one, also when J-Archive left it unclosed ("The Last Emperor (or
# Last Tango in Paris") or put parentheses inside it ("cesium (Cs) (or chlorine (Cl))").
ALTERNATIVE_START = re.compile(r"\(\s*(?:or|also)\s|\([^()]*\baccept(?:ed|able)\b", re.I)
IDIOMS = re.compile(
    r"\b(either|whether|more or less|or not|or else|or bust|or die|or never|or treat|or swim|"
    r"or alive|or nothing|or shine|or later|or other|or so|give or take|for richer or poorer|"
    r"or worse|or poorer|or fight|or less|or two)\b",
    re.I,
)


# --- Database -----------------------------------------------------------------

def find_env():
    """The nearest .env in the project root or a folder above it, so it is
    also found from a git worktree (.claude/worktrees/<name>) of the project."""
    for folder in [SCRIPT_DIR.parent.parent, *SCRIPT_DIR.parent.parent.parents]:
        if (folder / ".env").is_file():
            return folder / ".env"
    sys.exit("No .env found in the project or a folder above it; pass --env PATH")


def read_env(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def connect(env_path, read_only=True):
    env = read_env(env_path)
    match = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", env.get("DATABASE_URL", ""))
    if not match:
        sys.exit(f"Can't read DATABASE_URL from {env_path}")
    conn = psycopg.connect(
        host=match[1],
        port=int(match[2] or 5432),
        dbname=match[3],
        user=env.get("DATABASE_USERNAME"),
        password=env.get("DATABASE_PASSWORD"),
    )
    conn.read_only = read_only
    return conn


# Answers that may need a rewrite: a bare "or", a list marker, or an
# alternative in parentheses. The Python checks below decide.
CANDIDATES_SQL = r"""
    select clue.answer, clue.question, category.name, category.column_number, game.title
    from clue
    join category on category.id = clue.category_id
    join game on game.id = category.game_id
    where regexp_replace(clue.answer, '\([^)]*\)?', '', 'g') ~* '\mor\M'
       or clue.answer ~* '^\s*\(\s*(any\s+)?(\d+|one|two|three|four|five|either)\s+of\M'
       or clue.answer ~* '\(\s*(or|also)\s|accept'
    order by clue.id
"""


def load_candidates(conn):
    """Every distinct answer that needs a person to decide, with its clues.
    An answer that is already the result of a reviewed rewrite is done."""
    rewritten = {d["new_answer"] for d in load_decisions().values() if d["decision"] == "rewrite"}
    clues_by_answer = {}
    for answer, question, category, column, title in conn.execute(CANDIDATES_SQL):
        if answer in rewritten or not needs_review(answer):
            continue
        clues_by_answer.setdefault(answer, []).append({
            "question": clean_question(question),
            "category": category,
            "round": "Jeopardy" if column <= 6 else "Double Jeopardy" if column <= 12 else "Final Jeopardy",
            "game": title,
        })

    candidates = []
    for answer, clues in clues_by_answer.items():
        # Show the clue that asks for "N of" them first, if one does.
        clues.sort(key=lambda c: n_of_in_clue(c["question"]) is None)
        candidates.append({"answer": answer, "count": len(clues), "clues": clues[:3], **clues[0]})

    candidates.sort(key=lambda c: (hint(c)[0], c["answer"].lower()))
    return candidates


def clean_question(question):
    return html.unescape(re.sub(r"<[^>]+>", "", question)).strip()


# --- Splitting ----------------------------------------------------------------

def split_top_level(text, separator):
    """Splits on a regex, ignoring matches inside quotes, parentheses or brackets."""
    parts, depth, in_quotes, start, i = [], 0, False, 0, 0
    while i < len(text):
        char = text[i]
        if char == '"':
            in_quotes = not in_quotes
        elif char in "([":
            depth += 1
        elif char in ")]":
            depth = max(0, depth - 1)
        elif depth == 0 and not in_quotes:
            match = separator.match(text, i)
            if match and match.end() > i:
                parts.append(text[start:i])
                start = i = match.end()
                continue
        i += 1
    parts.append(text[start:])
    return [part.strip() for part in parts if part.strip()]


OR_SEPARATOR = re.compile(r"\s*,?\s+(?!OR\b)[oO][rR]\s+")
COMMA_SEPARATOR = re.compile(r"\s*,\s*")
# Between the options of a list: a comma, "or", "and/or" or "&/or", e.g.
# "A, B &/or C", "A, B, or C" or "A, B, & C". A plain "&" isn't one, since
# options can contain it ("pink & Pinky").
ITEM_SEPARATOR = re.compile(
    # Not the comma in "$1,000".
    r"\s*(?:(?<!\d),|,(?!\d{3}\b))\s*(?:(?:and|&)\s*/\s*or\s+|(?!OR\b)[oO][rR]\s+|(?:and|&)\s+)?"
    r"|\s+(?:(?:and|&)\s*/\s*or|(?!OR\b)[oO][rR])\s+"
)

NUMBER_WORDS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
NUMBER = r"\d+|" + "|".join(NUMBER_WORDS)
# "1 of 2", "one of the 2", "2 of the 3", "1 of only 2", "either of these 2".
N_OF_M = re.compile(
    rf"(?<![\d,])\b(?P<n>{NUMBER})\s+of\s+(?:(?:the|only|these|its|his|her|their)\s+)*(?P<m>{NUMBER})\b"
    rf"|\beither\s+of\b(?:\s+(?:the|these)\s+)?(?:(?P<m2>{NUMBER})\b)?",
    re.I,
)
# A list marker at the start of an answer: "(2 of)", "(one of)", "(1 of 3)",
# "(any 2 of)", "(either of)". The standard form is "(2 of) A | B | C".
MARKER = re.compile(
    rf"^\s*\(\s*(?:any\s+)?(?P<n>{NUMBER}|either)\s+of(?:\s+(?:{NUMBER}))?\s*\)\s*",
    re.I,
)
LAST_AND = re.compile(r"\s+(?:&|and)\s+")


def to_number(word):
    return int(word) if word.isdigit() else NUMBER_WORDS[word.lower()]


def n_of_in_clue(question):
    """(n, total or None, the words in the clue) if the clue asks for "n of" the options."""
    match = N_OF_M.search(question)
    if not match:
        return None
    if match["n"]:
        return to_number(match["n"]), to_number(match["m"]), match.group(0)
    return 1, to_number(match["m2"]) if match["m2"] else None, match.group(0)


def marker_n(answer):
    """N from the answer's own list marker, or None."""
    match = MARKER.match(answer)
    if not match:
        return None
    return 1 if match["n"].lower() == "either" else to_number(match["n"])


def list_items(answer):
    """The options of an answer like "A, B &/or C", "(2 of) A, B & C" or "(2 of) A | B | C"."""
    marker = MARKER.match(answer)
    body = answer[marker.end():] if marker else answer
    if "|" in body:
        return [item.strip() for item in body.split("|") if item.strip()]
    return split_options(body, split_last_and=bool(marker))


def split_options(text, split_last_and):
    items = split_top_level(text, ITEM_SEPARATOR)
    # In a marked list, the last "&" or "and" separates options too
    # ("(2 of) A, B & C"); elsewhere it's usually part of an option.
    if split_last_and and items:
        last = split_top_level(items[-1], LAST_AND)
        if len(last) > 1:
            items[-1:] = [" & ".join(last[:-1]), last[-1]]

    options = []
    for item in items:
        # Parentheses around or after an option hold more options:
        # '"Que Sera, Sera" or ("Secret Love")', "Cuba (Haiti or the
        # Dominican Republic)", "Venezuela (or Colombia)". At the start of an
        # option they're an optional part instead: "(Ronald) Reagan".
        match = re.fullmatch(r"(.*?)\s*\(([^()]*)\)", item)
        if match and not re.search(r"\([^()]*$", match[1]):
            inner = re.sub(r"^\s*or\s+", "", match[2], flags=re.I)
            options += ([match[1]] if match[1] else []) + split_options(inner, split_last_and=True)
        else:
            options.append(item)
    return options


def format_n_of(n, items):
    """'(2 of) A | B | C'. No answer contains "|", so it can't be mistaken for part of an option."""
    return f"({n} of) " + " | ".join(items)


# --- The automatic rule ---------------------------------------------------------

# One alternative at the very end: "Myanmar (or Burma)", "Pagliacci (I Pagliacci accepted)".
ALTERNATIVE_AT_END = re.compile(r"\s*\(\s*(?:or\s+(?P<or>[^()]+?)|(?P<accepted>[^()]+?)\s+accepted)\s*\)\s*$", re.I)


def auto_rewrite(answer):
    """The rewrite of an answer simple enough not to need a person, or None.

    "A (or X)" and "A (X accepted)" become "(1 of) A | X" when A is plain text
    (optional parts like "(Abraham)" are fine) and X is a single option: no
    comma, "or", "?" or parentheses, any of which could be a list or part of a
    title ("Rudy (or Knute Rockne, All American)").

    X must also clearly replace all of A, so A can't be a list ("coroner &
    coronet (or coronel)", where coronel replaces only one item) or have a
    comma, and X can't have fewer words than A ("a linen closet (or
    cupboard)", which means "a linen cupboard"). Those go to review.
    """
    if MARKER.match(answer):
        return None
    match = ALTERNATIVE_AT_END.search(answer)
    if not match:
        return None
    first = answer[:match.start()].strip()
    other = (match["or"] or match["accepted"]).strip()
    if not first:
        return None

    outside_groups = re.sub(r"\([^()]*\)", "", first)
    if "(" in outside_groups or ")" in outside_groups:
        return None  # nested or unbalanced
    if ALTERNATIVE_GROUP.search(first):
        return None
    if BARE_OR.search(PARENS.sub("", first)) or AND_OR.search(first):
        return None
    if re.search(r"[,;?()]|\bor\b", other, re.I):
        return None
    if "," in first or LIST_AND.search(first) or len(content_words(other)) < len(content_words(first)):
        return None  # X may replace only part of A
    return format_n_of(1, [first, other])


LIST_AND = re.compile(r"\s(?:&|and)\s", re.I)
ARTICLES = {"a", "an", "the"}


def content_words(text):
    """The words outside parentheses, without articles or lone punctuation."""
    return [word for word in PARENS.sub(" ", text).lower().split()
            if word not in ARTICLES and re.search(r"\w", word)]


# --- Suggestions for review -------------------------------------------------------

def needs_review(answer):
    """True if a person should decide: a bare "or", a list not in the standard
    form, or an alternative in parentheses the automatic rule can't handle."""
    n = marker_n(answer)
    if n is not None:
        return format_n_of(n, list_items(answer)) != answer
    if auto_rewrite(answer):
        return False
    return bool(BARE_OR.search(PARENS.sub("", answer)) or ALTERNATIVE_START.search(answer))


def single_alternative(answer):
    """(A, X) for an answer that ends in one plain alternative, "A (or X)" or
    "A (X accepted)", that the rule left for review; otherwise None."""
    end = ALTERNATIVE_AT_END.search(answer)
    if not end:
        return None
    first = answer[:end.start()].strip()
    other = (end["or"] or end["accepted"]).strip()
    if not first or ALTERNATIVE_GROUP.search(first) or re.search(r"[,;?()]|\bor\b", other, re.I):
        return None
    return first, other


def is_shorter_alternative(answer):
    """True for one alternative at the end with fewer words than what it
    follows: "a linen closet (or cupboard)" may mean "a linen cupboard", or
    "a duck-billed platypus (or echidna)" just "echidna"."""
    single = single_alternative(answer)
    return bool(single) and len(content_words(single[1])) < len(content_words(single[0]))


def alternatives_in_parentheses(answer):
    """Options for "A (or B, C or D)", "baker (breadmaker acceptable)",
    "Greek (or Eastern) Orthodox"."""
    single = single_alternative(answer)
    if single:
        # A different whole answer, "and" and all: "hook and loop (or hook
        # and eye)", "a duck-billed platypus (or echidna)".
        return list(single)

    # An "(or X)" in the middle replaces as many words before it as X has.
    inline = re.search(r"\(\s*or\s+([^()]*)\)\s*(?=\w)", answer, re.I)
    if inline:
        before = answer[:inline.start()].split()
        after = answer[inline.end():].strip()
        options = [" ".join(before + [after])]
        for other in split_top_level(inline[1], ITEM_SEPARATOR):
            words = other.split()
            options.append(" ".join(before[:max(0, len(before) - len(words))] + words + [after]))
        return options

    def as_or_group(group):
        # "(or X)", "(also X)", "(X accepted)", "(also would accept X)", "(X later ruled acceptable)"
        inner = re.sub(r"^\s*(or|also)\s+", "", group[1:-1], flags=re.I)
        inner = re.sub(r"\b(also|would|accept(ed|able)?|later\s+(ruled|judged))\b", " ", inner, flags=re.I)
        return "(or " + re.sub(r"\s+", " ", inner).strip(" :,") + ")"

    return split_options(ALTERNATIVE_GROUP.sub(lambda m: as_or_group(m[0]), answer), split_last_and=False)


def hint(candidate):
    """(group, label, suggestion) for an answer. A suggestion of None means keep it."""
    answer = candidate["answer"]
    stripped = PARENS.sub("", answer)
    n_of = n_of_in_clue(candidate["question"])
    items = list_items(answer)

    def warnings(label, options):
        if n_of and n_of[1] and n_of[1] != len(options):
            label += f" The clue says {n_of[1]}; check the split."
        if any("&" in option for option in options):
            label += " An option contains \"&\"; check the split."
        return label

    n = marker_n(answer)
    if n is not None:
        return 0, warnings(f"Already a list of {len(items)} options.", items), format_n_of(n, items)

    if n_of and len(items) >= 2 and not ALTERNATIVE_GROUP.search(answer):
        return 0, warnings(f"The clue says \"{n_of[2]}\": {len(items)} options.", items), format_n_of(n_of[0], items)

    if ALTERNATIVE_GROUP.search(answer) and not BARE_OR.search(stripped):
        options = alternatives_in_parentheses(answer)
        n = n_of[0] if n_of else 1
        if re.search(r"\(\s*or\s+[^()]*\)\s*\w", answer, re.I):
            label = "\"(or X)\" in the middle: check which words X replaces."
        elif is_shorter_alternative(answer):
            label = "The alternative is shorter: it may be a different answer (suggested), or replace only some words."
        elif LIST_AND.search(PARENS.sub("", answer)):
            label = "The alternative may replace only one item of the list, or all of it."
        else:
            label = "Alternatives in parentheses: check the split."
        return 8, warnings(label, options), format_n_of(n, options) if len(options) > 1 else None

    if AND_OR.search(stripped):
        return 1, "An and/or list: the clue may ask for \"N of\" them.", format_n_of(n_of[0] if n_of else 1, items)
    if IDIOMS.search(stripped):
        return 2, "Looks like a phrase or title that contains \"or\".", None

    parts = split_top_level(answer, OR_SEPARATOR)
    if len(parts) < 2:
        return 3, "\"or\" is only inside a quoted title.", None

    if len({PARENS.sub("", p).strip().lower() for p in parts}) == 1:
        return 4, "The same word with different pronunciations.", None

    # "A, B or C": the commas before the last "or" separate alternatives too.
    if "," in parts[0] and len(parts) == 2:
        parts = split_top_level(parts[0], COMMA_SEPARATOR) + parts[1:]

    suggestion = format_n_of(1, parts)
    words = [len(p.split()) for p in parts]
    if words[0] == 1 and max(words[1:]) > 1:
        return 6, "The first part may share an ending (\"barometric or atmospheric pressure\").", suggestion
    if len(parts) > 2:
        return 7, "Several alternatives.", suggestion
    return 5, "Looks like two alternatives.", suggestion


GROUP_NAMES = {
    0: "Lists (N of)",
    1: "and/or lists",
    2: "Phrases with \"or\"",
    3: "\"or\" in a quoted title",
    4: "Pronunciations",
    5: "Two alternatives",
    6: "Shared ending?",
    7: "Several alternatives",
    8: "Alternatives in parentheses",
}


# --- Decisions file -----------------------------------------------------------

def load_decisions():
    if not DECISIONS_FILE.exists():
        return {}
    with DECISIONS_FILE.open(encoding="utf-8", newline="") as file:
        return {row["answer"]: row for row in csv.DictReader(file)}


def save_decisions(decisions):
    tmp = DECISIONS_FILE.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS)
        writer.writeheader()
        for answer in sorted(decisions, key=str.lower):
            writer.writerow(decisions[answer])
    os.replace(tmp, DECISIONS_FILE)


# --- Review in the browser --------------------------------------------------------

def review(args):
    print("Loading answers from the database...")
    with connect(args.env) as conn:
        candidates = load_candidates(conn)

    items = []
    for candidate in candidates:
        group, label, suggestion = hint(candidate)
        n_of = n_of_in_clue(candidate["question"])
        items.append({
            "answer": candidate["answer"],
            "count": candidate["count"],
            "clues": candidate["clues"],
            "group": group,
            "label": label,
            "suggestion": suggestion,
            "clueN": marker_n(candidate["answer"]) or (n_of[0] if n_of else None),
        })
    payload = json.dumps({"items": items, "groups": GROUP_NAMES}).encode("utf-8")

    decisions = load_decisions()
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send(self, status, body, content_type="application/json; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/":
                self.send(200, REVIEW_PAGE.read_bytes(), "text/html; charset=utf-8")
            elif self.path == "/api/items":
                self.send(200, payload)
            elif self.path == "/api/decisions":
                with lock:
                    self.send(200, json.dumps(decisions).encode("utf-8"))
            else:
                self.send(404, b"{}")

        def do_POST(self):
            if self.path != "/api/decision":
                self.send(404, b"{}")
                return
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            answer, decision = body["answer"], body["decision"]
            new_answer = (body.get("new_answer") or "").strip()
            with lock:
                if decision == "clear":
                    decisions.pop(answer, None)
                elif decision == "keep" or (decision == "rewrite" and new_answer and new_answer != answer):
                    decisions[answer] = {
                        "answer": answer,
                        "decision": "keep" if decision == "keep" else "rewrite",
                        "new_answer": new_answer if decision == "rewrite" else "",
                    }
                else:
                    self.send(400, json.dumps({"error": "A rewrite needs a new answer"}).encode("utf-8"))
                    return
                save_decisions(decisions)
                self.send(200, json.dumps(decisions.get(answer)).encode("utf-8"))

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{args.port}/"
    print(f"{len(items)} answers to review, {len(decisions)} decided. Open {url}")
    print(f"Decisions are saved to {DECISIONS_FILE} as you go. Ctrl+C to stop.")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


# --- Apply --------------------------------------------------------------------

def apply(args):
    with connect(args.env, read_only=not args.commit) as conn:
        def rewrite(pairs):
            """Rewrites every clue whose answer is a key of pairs, in one query
            (answer has no index, so one query per answer would scan the table each time)."""
            old, new = [p[0] for p in pairs], [p[1] for p in pairs]
            counts = dict(conn.execute(
                "select answer, count(*) from clue where answer = any(%s) group by answer", (old,)
            ).fetchall())
            if args.commit and counts:
                conn.execute(
                    "update clue set answer = m.new from unnest(%s::text[], %s::text[]) as m(old, new)"
                    " where clue.answer = m.old",
                    (old, new),
                )
            return counts

        answers = conn.execute(r"select distinct answer from clue where answer ~* '\(\s*or\s|accepted' order by 1")
        automatic = [(old, new) for (old,) in answers if (new := auto_rewrite(old))]
        auto_total = sum(rewrite(automatic).values())
        print(f"Rule:     {auto_total} clues ({len(automatic)} answers), e.g.")
        for old, new in automatic[:5]:
            print(f"            {old}  →  {new}")

        reviewed = [(d["answer"], d["new_answer"]) for d in load_decisions().values()
                    if d["decision"] == "rewrite" and not args.rule_only]
        counts = rewrite(reviewed)
        reviewed_total = sum(counts.values())
        if args.verbose:
            for old, new in reviewed:
                if old in counts:
                    print(f"{counts[old]:4}  {old}\n   →  {new}")
        print(f"Reviewed: {reviewed_total} clues ({len(counts)} of the {len(reviewed)} rewrites"
              f" in {DECISIONS_FILE.name} found in the database)")

        if args.commit:
            conn.commit()
            print(f"\nUpdated {auto_total + reviewed_total} clues.")
        else:
            print(f"\nDry run: {auto_total + reviewed_total} clues would change. Run with --commit to update them.")


def main():
    global DECISIONS_FILE
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env", type=Path, help="path to the .env file (default: the nearest one above this script)")
    parser.add_argument("--decisions", type=Path, default=DECISIONS_FILE, help=f"decisions file (default: {DECISIONS_FILE.name})")
    commands = parser.add_subparsers(dest="command", required=True)
    review_parser = commands.add_parser("review", help="review answers in the browser")
    review_parser.add_argument("--port", type=int, default=8765)
    review_parser.add_argument("--no-browser", action="store_true", help="don't open the browser")
    apply_parser = commands.add_parser("apply", help="run the rule and the reviewed rewrites")
    apply_parser.add_argument("--commit", action="store_true", help="update the database")
    apply_parser.add_argument("--verbose", action="store_true", help="list every reviewed rewrite")
    apply_parser.add_argument("--rule-only", action="store_true", help="skip the reviewed rewrites")
    args = parser.parse_args()

    DECISIONS_FILE = args.decisions.resolve()
    args.env = args.env or find_env()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    review(args) if args.command == "review" else apply(args)


if __name__ == "__main__":
    main()
