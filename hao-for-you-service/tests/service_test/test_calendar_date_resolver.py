from datetime import date

import pytest

from app.service.calendar_date_resolver import resolve_calendar_date


TODAY = date(2026, 7, 21)


def test_month_day_uses_current_year_when_still_upcoming():
    assert resolve_calendar_date(
        '幫我新增 7/27 晚上6:00到7:00打球',
        model_date="2025-12-31",
        today=TODAY,
    ) == "2026-07-27"


def test_month_day_rolls_to_next_year_when_already_passed():
    assert resolve_calendar_date(
        "幫我新增 7/20 晚上打球",
        model_date="2025-12-31",
        today=TODAY,
    ) == "2027-07-20"


def test_explicit_year_is_preserved_from_user_text():
    assert resolve_calendar_date(
        "幫我新增 2028年7月27日打球",
        model_date="2025-12-31",
        today=TODAY,
    ) == "2028-07-27"


@pytest.mark.parametrize(
    ("user_text", "expected"),
    [
        ("今天晚上打球", "2026-07-21"),
        ("明天晚上打球", "2026-07-22"),
        ("後天晚上打球", "2026-07-23"),
    ],
)
def test_relative_dates_are_resolved_without_model_year(
    user_text: str,
    expected: str,
):
    assert resolve_calendar_date(
        user_text,
        model_date="2025-12-31",
        today=TODAY,
    ) == expected


def test_invalid_explicit_user_date_is_rejected():
    with pytest.raises(ValueError, match="日期不存在"):
        resolve_calendar_date(
            "幫我新增 2026/2/30 打球",
            model_date="2026-03-01",
            today=TODAY,
        )
