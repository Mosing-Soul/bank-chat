import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "evaluation"))
from rag_evaluator import diversity_metrics, key_point_score, retrieval_metrics, source_scores


class RagEvaluatorMetricTest(unittest.TestCase):
    def test_rank_metrics(self):
        result = retrieval_metrics([0, 1, 0], relevant_total=1, k=3)
        self.assertEqual(1.0, result["recall"])
        self.assertAlmostEqual(0.5, result["mrr"])
        self.assertAlmostEqual(1 / 3, result["precision"])

    def test_unanswerable_is_clean_rejection(self):
        self.assertEqual(1.0, retrieval_metrics([0, 0], 0, 2)["hitRate"])

    def test_deterministic_generation_metrics(self):
        score = key_point_score("需要装修合同和预算清单", ["装修合同", "预算清单", "用途凭证"])
        self.assertAlmostEqual(2 / 3, score["score"], places=3)
        sources = source_scores(["a.pdf"], ["a.pdf", "b.xlsx"])
        self.assertEqual(1.0, sources["precision"])
        self.assertEqual(0.5, sources["recall"])

    def test_diversity(self):
        docs = [SimpleNamespace(page_content="A", metadata={"source": "x"}), SimpleNamespace(page_content="B", metadata={"source": "y"})]
        self.assertEqual(1.0, diversity_metrics(docs)["uniqueSourceRatio"])


if __name__ == "__main__":
    unittest.main()
