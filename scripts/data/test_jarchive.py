"""Tests for jarchive.py's reading of J-Archive's pages. No database or network needed.

    python -m unittest discover -s scripts/data
"""
import importlib.util
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

from bs4 import BeautifulSoup

spec = importlib.util.spec_from_file_location("jarchive", Path(__file__).with_name("jarchive.py"))
jarchive = importlib.util.module_from_spec(spec)
# Registered, so read_games' worker processes find its functions.
sys.modules["jarchive"] = jarchive
spec.loader.exec_module(jarchive)


def category(name, comments=""):
    return f"""<td class="category"><table>
        <tr><td class="category_name">{name}</td></tr>
        <tr><td class="category_comments">{comments}</td></tr>
    </table></td>"""


def clue(position, question, answer, daily_double=False):
    value = '<td class="clue_value_daily_double">DD: $1,000</td>' if daily_double else '<td class="clue_value">$200</td>'
    return f"""<td class="clue"><table>
        <tr><td><table class="clue_header"><tr>{value}</tr></table></td></tr>
        <tr>
            <td class="clue_text" id="{position}">{question}</td>
            <td class="clue_text" id="{position}_r" style="display:none;"><em class="correct_response">{answer}</em></td>
        </tr>
    </table></td>"""


def page(title, rounds):
    return f"""<html><body><div id="content">
        <div id="game_title"><h1>{title}</h1></div>
        {rounds}
    </div></body></html>"""


def board(round_id, categories, clues):
    return f"""<div id="{round_id}"><table class="round">
        <tr>{"".join(categories)}</tr>
        <tr>{"".join(clues)}</tr>
    </table></div>"""


GAME = page("Show #1 - Monday, September 10, 1984", "".join([
    board("jeopardy_round", [category("LAKES & RIVERS", "(Alex: Fresh water only.)"), category("INVENTIONS")], [
        clue("clue_J_1_1", "River mentioned most often in the Bible", "the Jordan"),
        clue("clue_J_2_3", 'Seen <a href="http://www.j-archive.com/media/1984-09-10_J_08.jpg">here</a>, it\'s a cotton gin', "Eli Whitney", daily_double=True),
        clue("clue_J_2_4", "=", "="),
    ]),
    board("double_jeopardy_round", [category("PRESIDENTS")], [
        clue("clue_DJ_1_2", "Heard <a href=\"http://www.j-archive.com/media/x.mp3\">here</a>", "Lincoln"),
    ]),
    board("final_jeopardy_round", [category("HOLIDAYS")], [
        clue("clue_FJ", "The day after Thanksgiving", "Black Friday"),
        clue("clue_TB", "A tiebreaker", "not part of the game"),
    ]),
]))


def content(html):
    return BeautifulSoup(html, "html.parser").find("div", id="content")


class ParseGameTest(unittest.TestCase):

    def setUp(self):
        self.game = jarchive.parse_game(content(GAME), "1", 7)

    def test_reads_the_title_and_air_date(self):
        self.assertEqual("Show #1", self.game["title"])
        self.assertEqual(date(1984, 9, 10), self.game["date"])
        self.assertEqual("1", self.game["season"])

    def test_numbers_columns_by_round(self):
        self.assertEqual({1, 2, 7, 13}, set(self.game["categories"]))
        self.assertEqual({"name": "LAKES & RIVERS", "comments": "(Alex: Fresh water only.)"}, self.game["categories"][1])
        self.assertIsNone(self.game["categories"][2]["comments"])
        positions = [(c["column"], c["row"]) for c in self.game["clues"]]
        self.assertEqual([(1, 1), (2, 3), (7, 2), (13, 1)], positions)

    def test_skips_broken_and_tiebreaker_clues(self):
        answers = [c["answer"] for c in self.game["clues"]]
        self.assertNotIn("=", answers)
        self.assertNotIn("not part of the game", answers)

    def test_reads_media_and_daily_doubles(self):
        clues = {(c["column"], c["row"]): c for c in self.game["clues"]}
        self.assertEqual(("TEXT", None, False), (clues[1, 1]["type"], clues[1, 1]["media_url"], clues[1, 1]["daily_double"]))
        self.assertEqual("IMAGE", clues[2, 3]["type"])
        self.assertEqual("http://www.j-archive.com/media/1984-09-10_J_08.jpg", clues[2, 3]["media_url"])
        self.assertTrue(clues[2, 3]["daily_double"])
        self.assertEqual("SOUND", clues[7, 2]["type"])
        # The question keeps J-Archive's HTML.
        self.assertIn("<a href=", clues[2, 3]["question"])

    def test_rejects_an_unknown_media_type(self):
        html = page("Show #2 - Tuesday, September 11, 1984", board("jeopardy_round", [category("A")], [
            clue("clue_J_1_1", '<a href="http://www.j-archive.com/media/x.gif">here</a>', "x"),
        ]))
        with self.assertRaisesRegex(ValueError, "unknown media type"):
            jarchive.parse_game(content(html), "1", 8)

    def test_a_page_without_a_game_is_none(self):
        self.assertIsNone(jarchive.parse_game(content('<div id="content">No game</div>'), "1", 9))


class ReadGamesTest(unittest.TestCase):

    def test_sorts_by_air_date_then_jarchive_id(self):
        with tempfile.TemporaryDirectory() as folder:
            pages = Path(folder)
            for season, game_id, title in [("2", 30, "Show #3 - Friday, September 13, 1985"),
                                           ("1", 20, "Show #2 - Friday, September 14, 1984"),
                                           ("jm", 11, "Special - Friday, September 14, 1984"),
                                           ("1", 10, "Show #1 - Thursday, September 13, 1984")]:
                path = pages / f"season_{season}" / f"episode_{game_id}.html"
                path.parent.mkdir(exist_ok=True)
                path.write_text(page(title, ""), encoding="utf-8")
            games = jarchive.read_games(pages)
        self.assertEqual([10, 11, 20, 30], [g["jarchive_id"] for g in games])


class ListsTest(unittest.TestCase):

    def test_reads_seasons_with_their_number_of_games(self):
        html = """<div id="content"><table>
            <tr><td><a href="showseason.php?season=42">Season 42</a></td><td>(230 games archived)</td></tr>
            <tr><td><a href="showseason.php?season=jm">Jeopardy! Masters</a></td><td>(1 game archived)</td></tr>
        </table></div>"""
        self.assertEqual([("42", 230), ("jm", 1)], jarchive.parse_seasons_list(content(html)))

    def test_reads_a_seasons_game_ids(self):
        html = """<div id="content"><table>
            <tr><td align="left"><a href="showgame.php?game_id=9504">#9615</a></td><td>Players</td></tr>
            <tr><td align="left"><a href="showgame.php?game_id=9503">#9614</a></td><td>Players</td></tr>
        </table></div>"""
        self.assertEqual(["9504", "9503"], jarchive.parse_episodes_list(content(html)))


if __name__ == "__main__":
    unittest.main()
