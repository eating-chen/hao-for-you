import os
import logging
import yaml
from logging.handlers import RotatingFileHandler


class LoggerService:
    # Load logger configuration from a YAML file
    with open('./app/core/logger_settings.yml', 'r') as file:
        config = yaml.safe_load(file)
    loggers = {}

    @classmethod
    def setup_logger(cls, name=None):
        """
        Set up and return a logger with the name provided.
        """
        if name in cls.loggers:
            return cls.loggers[name]

        if name:
            log_config = cls.config.get(name, {})
            if not log_config:
                raise ValueError(f"No logger configuration found for '{name}'")
        else:
            log_config = cls.config.get('info', {})

        log_file = log_config.get('log_file', 'default.log')
        os.makedirs(os.path.dirname(log_file), exist_ok=True)
        level = log_config.get('level', logging.INFO)
        log_format = log_config.get('format', '%(asctime)s - %(levelname)s - %(message)s')  # Renamed 'format' variable

        formatter = logging.Formatter(log_format)
        handler = RotatingFileHandler(
            log_file,
            maxBytes=log_config.get('maxBytes', 1024 * 1024 * 100),
            backupCount=log_config.get('backupCount', 20)
        )
        handler.setFormatter(formatter)

        logger = logging.getLogger(name)
        logger.setLevel(level)

        if not logger.handlers:
            logger.addHandler(handler)

        # Store the configured logger in the dictionary
        cls.loggers[name] = logger

        return logger


logger = LoggerService.setup_logger()
error_logger = LoggerService.setup_logger('error')
