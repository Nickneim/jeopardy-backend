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

    def test_suggests_replacing_the_last_words_with_a_shorter_alternative(self):
        self.assertEqual("(1 of) a linen closet | a linen cupboard", suggestion("a linen closet (or cupboard)"))
        self.assertEqual("(1 of) the Fugitive Slave Law | the Fugitive Slave Act", suggestion("the Fugitive Slave Law (or Act)"))

    def test_suggests_the_whole_answer_for_an_alternative_as_long_as_the_list(self):
        self.assertEqual("(1 of) hook and loop | hook and eye", suggestion("hook and loop (or hook and eye)"))


if __name__ == "__main__":
    unittest.main()
