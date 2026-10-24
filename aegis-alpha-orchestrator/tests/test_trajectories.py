"""P4-13: run every tests/trajectories/*.yaml case through the real agent loop with a scripted model."""
import pytest

from trajectory_harness import check_expectations, load_cases, run_case

CASES = load_cases()


def test_at_least_ten_cases():
    assert len(CASES) >= 10


@pytest.mark.asyncio
@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
async def test_trajectory(case):
    result = await run_case(case)
    failures = check_expectations(case, result)
    assert not failures, f"{case['name']}: " + "; ".join(failures) + f"\nevents={result.names}"
