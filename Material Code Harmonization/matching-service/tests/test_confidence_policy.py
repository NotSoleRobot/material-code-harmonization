import os
import sys

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

from matching.inference import HIGH_CONF_THRESHOLD, MEDIUM_CONF_THRESHOLD, _confidence_tier


def test_confidence_thresholds_follow_governance_policy():
    assert HIGH_CONF_THRESHOLD == 0.85
    assert MEDIUM_CONF_THRESHOLD == 0.60


def test_confidence_tier_boundaries_are_consistent():
    assert _confidence_tier(0.8499) == "MEDIUM"
    assert _confidence_tier(0.85) == "HIGH"
    assert _confidence_tier(0.5999) == "LOW"
    assert _confidence_tier(0.60) == "MEDIUM"
