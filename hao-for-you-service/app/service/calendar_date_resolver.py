"""Deterministic date resolution for calendar requests."""
from __future__ import annotations

import re
from datetime import date, timedelta


EXPLICIT_DATE_PATTERN = re.compile(
    r"(?<!\d)(?P<year>\d{4})\s*(?:年|[-/.])\s*"
    r"(?P<month>\d{1,2})\s*(?:月|[-/.])\s*"
    r"(?P<day>\d{1,2})\s*日?"
)
MONTH_DAY_PATTERN = re.compile(
    r"(?<!\d)(?P<month>\d{1,2})\s*(?:月|[-/.])\s*"
    r"(?P<day>\d{1,2})\s*日?"
)


def resolve_calendar_date(
    user_text: str,
    model_date: str,
    today: date | None = None,
) -> str:
    """Resolve the date from user text; use model output only as a fallback.

    An explicit user year is always preserved. A numeric month/day without a
    year is assigned to its next occurrence, so the model cannot invent a year.
    """
    current_date = today or date.today()

    explicit_match = EXPLICIT_DATE_PATTERN.search(user_text)
    if explicit_match:
        return _build_date(explicit_match).isoformat()

    relative_offsets = (("後天", 2), ("明天", 1), ("今天", 0))
    for keyword, offset in relative_offsets:
        if keyword in user_text:
            return (current_date + timedelta(days=offset)).isoformat()

    month_day_match = MONTH_DAY_PATTERN.search(user_text)
    if month_day_match:
        month = int(month_day_match.group("month"))
        day = int(month_day_match.group("day"))
        candidate = date(current_date.year, month, day)
        if candidate < current_date:
            candidate = date(current_date.year + 1, month, day)
        return candidate.isoformat()

    return model_date


def _build_date(match: re.Match[str]) -> date:
    try:
        return date(
            int(match.group("year")),
            int(match.group("month")),
            int(match.group("day")),
        )
    except ValueError as exc:
        raise ValueError("使用者提供的日期不存在，請重新確認。") from exc
