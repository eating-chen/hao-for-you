"""base"""
from sqlalchemy.ext.asyncio import AsyncSession


class DBSessionContext(object):
    """有要初始db的物件先繼承這個class"""

    def __init__(self, _db: AsyncSession):
        self.db = _db


class AppCRUD(DBSessionContext):
    """負責處理CRUD的 base class"""
    pass
