"""test logger service function"""
import logging
from app.service.logger_service import LoggerService


class TestLoggerService:

    def test_setup_logger(self):
        logger = LoggerService.setup_logger()
        error_logger = LoggerService.setup_logger('error')
        assert isinstance(logger, logging.RootLogger)
        assert isinstance(error_logger, logging.Logger)
