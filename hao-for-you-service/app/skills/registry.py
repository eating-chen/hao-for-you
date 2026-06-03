"""Skill Registry：管理所有已註冊的 Skill"""
from app.skills.base_skill import BaseSkill
from app.service.logger_service import logger


class SkillRegistry:
    _skills: dict[str, BaseSkill] = {}

    @classmethod
    def register(cls, skill: BaseSkill) -> None:
        cls._skills[skill.name] = skill
        logger.info("Skill registered: %s", skill.name)

    @classmethod
    def get_by_name(cls, name: str) -> BaseSkill | None:
        return cls._skills.get(name)

    @classmethod
    def get_all(cls) -> list[BaseSkill]:
        return list(cls._skills.values())

    @classmethod
    def get_keyword_skills(cls) -> list[BaseSkill]:
        """回傳有設定 keywords 的 skill（排除 general_chat 等 fallback）"""
        return [s for s in cls._skills.values() if s.keywords]

    @classmethod
    def list_names(cls) -> list[str]:
        return list(cls._skills.keys())
