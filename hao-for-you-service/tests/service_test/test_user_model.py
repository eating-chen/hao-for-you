from sqlalchemy import UniqueConstraint

from app.models.user import User


def test_user_identity_is_unique_per_platform_and_user_id():
    constraints = {
        constraint.name: tuple(constraint.columns.keys())
        for constraint in User.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }

    assert constraints["idx_users_platform_user"] == ("platform", "user_id")
    assert User.__table__.columns.user_id.unique is not True
