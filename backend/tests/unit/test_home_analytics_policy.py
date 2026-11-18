from app.services.reports.overview import percentage_delta


def test_percentage_delta_never_fabricates_a_zero_baseline() -> None:
    assert percentage_delta(0, 0) is None
    assert percentage_delta(100, 0) is None
    assert percentage_delta(100, -10) is None
    assert percentage_delta(150, 100) == 50.0
    assert percentage_delta(50, 100) == -50.0
