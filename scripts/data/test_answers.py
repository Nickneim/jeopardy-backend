"""Tests for answers.py's parsing and review suggestions. No database needed.

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


class NeedsReviewTest(unittest.TestCase):

    def test_alternatives_and_lists_in_jarchive_form_need_review(self):
        self.assertTrue(answers.needs_review("Myanmar (or Burma)"))
        self.assertTrue(answers.needs_review("Pagliacci (I Pagliacci accepted)"))
        self.assertTrue(answers.needs_review("Santa Claus or Father Christmas"))
        self.assertTrue(answers.needs_review("(2 of) Bucharest, Budapest & Belgrade"))

    def test_answers_in_the_new_notation_are_done(self):
        self.assertFalse(answers.needs_review("(2 of) gold | frankincense | myrrh"))
        self.assertFalse(answers.needs_review("(2 of) coroner | [coronet | coronel]"))
        self.assertFalse(answers.needs_review("[Myanmar | Burma]"))
        self.assertFalse(answers.needs_review("a linen [closet | cupboard]"))

    def test_list_items_keep_bracketed_wordings_together(self):
        self.assertEqual(["coroner", "[coronet | coronel]"], answers.list_items("(2 of) coroner | [coronet | coronel]"))


class SuggestionTest(unittest.TestCase):

    def test_suggests_a_shorter_alternative_as_a_different_whole_answer(self):
        self.assertEqual("(1 of) a duck-billed platypus | echidna", suggestion("a duck-billed platypus (or echidna)"))

    def test_suggests_the_whole_answer_for_an_alternative_as_long_as_the_list(self):
        self.assertEqual("(1 of) hook and loop | hook and eye", suggestion("hook and loop (or hook and eye)"))

    def test_treats_a_quoted_title_as_one_unit(self):
        self.assertEqual('(1 of) "Beam us up, Scotty" | "Energize"', suggestion('"Beam us up, Scotty" (or "Energize")'))


class ProposalTest(unittest.TestCase):

    def test_a_proposal_replaces_the_built_in_suggestion(self):
        group, label, suggested = answers.proposal_hint({
            "decision": "rewrite", "new_answer": "[Myanmar | Burma]", "confidence": "high",
            "note": "same country", "oldDecision": "(1 of) Myanmar | Burma",
        })
        self.assertEqual("[Myanmar | Burma]", suggested)
        self.assertEqual(answers.PROPOSAL_GROUP_UNSURE + 1, group)
        self.assertIn("Your old decision: (1 of) Myanmar | Burma", label)

    def test_an_unsure_keep_proposal_suggests_keeping(self):
        group, _, suggested = answers.proposal_hint({"decision": "keep", "new_answer": "", "confidence": "low"})
        self.assertIsNone(suggested)
        self.assertEqual(answers.PROPOSAL_GROUP_UNSURE, group)


if __name__ == "__main__":
    unittest.main()
