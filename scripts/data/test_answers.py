"""Tests for answers.py's rule and review suggestions. No database needed.

    python -m unittest discover -s scripts/data
"""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("answers", Path(__file__).with_name("answers.py"))
answers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(answers)


def suggestion(answer, question=""):
    return answers.hint({"answer": answer, "question": question})[2]


class AutoRewriteTest(unittest.TestCase):

    def test_rewrites_one_alternative_that_replaces_the_whole_answer(self):
        self.assertEqual("(1 of) Myanmar | Burma", answers.auto_rewrite("Myanmar (or Burma)"))
        self.assertEqual("(1 of) Pagliacci | I Pagliacci", answers.auto_rewrite("Pagliacci (I Pagliacci accepted)"))
        self.assertEqual("(1 of) (Abraham) Lincoln | John Adams", answers.auto_rewrite("(Abraham) Lincoln (or John Adams)"))
        # Articles don't count as words.
        self.assertEqual("(1 of) the key | legend", answers.auto_rewrite("the key (or legend)"))

    def test_leaves_an_alternative_after_a_list_to_review(self):
        # coronel replaces one item: "coroner & coronet | coronel & coronet".
        self.assertIsNone(answers.auto_rewrite("coroner & coronet (or coronel)"))
        self.assertIsNone(answers.auto_rewrite("hook and loop (or hook and eye)"))
        self.assertTrue(answers.needs_review("coroner & coronet (or coronel)"))

    def test_leaves_an_alternative_after_a_comma_to_review(self):
        self.assertIsNone(answers.auto_rewrite("Lon Chaney, Jr. (or Bela Lugosi)"))
        self.assertTrue(answers.needs_review("Lon Chaney, Jr. (or Bela Lugosi)"))

    def test_leaves_an_alternative_with_fewer_words_to_review(self):
        self.assertIsNone(answers.auto_rewrite("a linen closet (or cupboard)"))
        self.assertIsNone(answers.auto_rewrite("Natasha Richardson (or Joely)"))
        self.assertTrue(answers.needs_review("a linen closet (or cupboard)"))


class SuggestionTest(unittest.TestCase):

    def test_suggests_a_shorter_alternative_as_a_different_whole_answer(self):
        self.assertEqual("(1 of) a duck-billed platypus | echidna", suggestion("a duck-billed platypus (or echidna)"))
        self.assertEqual("(1 of) a linen closet | cupboard", suggestion("a linen closet (or cupboard)"))

    def test_suggests_the_whole_answer_for_an_alternative_as_long_as_the_list(self):
        self.assertEqual("(1 of) hook and loop | hook and eye", suggestion("hook and loop (or hook and eye)"))

    def test_treats_a_quoted_title_as_one_unit(self):
        self.assertEqual('(1 of) "Beam us up, Scotty" | "Energize"', suggestion('"Beam us up, Scotty" (or "Energize")'))
        self.assertEqual('(1 of) "Don\'t Cry For Me Argentina" | Evita', suggestion('"Don\'t Cry For Me Argentina" (or Evita)'))
        # The quotes aren't words: one word for one word.
        self.assertEqual('(1 of) "(I Can\'t Get No) Satisfaction" | "Satisfaction"',
                         answers.auto_rewrite('"(I Can\'t Get No) Satisfaction" ("Satisfaction" accepted)'))


if __name__ == "__main__":
    unittest.main()
