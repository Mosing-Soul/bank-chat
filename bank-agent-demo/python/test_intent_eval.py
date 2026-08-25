import json
import tempfile
import unittest
from pathlib import Path

from intent.intent_eval import evaluate, load_dataset, render_markdown, write_report
from intent.structured_intent import IntentRecognitionService


class IntentEvalTest(unittest.TestCase):
    def setUp(self):
        self.dataset_path = Path(__file__).parent / "intent" / "intent_eval.json"
        self.dataset = load_dataset(self.dataset_path)

    def test_dataset_covers_current_intents_unknown_and_clarification(self):
        expected = {
            case["expectedIntent"]
            for case in self.dataset["cases"]
            if case["expectedIntent"] != "MODEL_TOP1"
        }
        self.assertEqual(
            expected,
            {
                "KNOWLEDGE_QA",
                "CUSTOMER_AUM_QUERY",
                "EXTERNAL_API_QUERY",
                "MESSAGE_SEND",
                "GENERAL_CHAT",
                "UNKNOWN",
            },
        )
        self.assertTrue(any(case["caseType"] == "clarification" for case in self.dataset["cases"]))
        compound = [case for case in self.dataset["cases"] if case["caseType"] == "compound_top1"]
        self.assertTrue(compound)
        self.assertTrue(all(len(case["allowedIntents"]) >= 2 for case in compound))

    def test_legacy_six_intent_report_is_serializable_for_before_after_comparison(self):
        report = evaluate(
            self.dataset,
            IntentRecognitionService(llm=None, threshold=0.6),
            self.dataset_path,
            "offline",
            "deterministic-fallback",
            0.6,
        )
        self.assertEqual(report["dataset"]["sampleCount"], len(self.dataset["cases"]))
        self.assertIn("clarificationAccuracy", report["summary"])
        self.assertIn("clarificationRecall", report["summary"])
        # This is intentionally the pre-simplification six-intent dataset. The
        # phase-1 router no longer executes AUM/message routes, so it is retained
        # as a measurable legacy baseline rather than a zero-failure gate.
        self.assertLess(report["summary"]["clarificationRecall"]["accuracy"], 1.0)
        self.assertIn("UNKNOWN", report["byExpectedIntent"])
        self.assertIn("confusionMatrix", report)
        self.assertGreater(report["summary"]["failureCount"], 0)
        json.dumps(report, ensure_ascii=False)
        self.assertIn("判分说明", render_markdown(report))

        with tempfile.TemporaryDirectory() as directory:
            json_path, markdown_path = write_report(report, Path(directory), "report")
            self.assertTrue(json_path.exists())
            self.assertTrue(markdown_path.exists())


if __name__ == "__main__":
    unittest.main()
