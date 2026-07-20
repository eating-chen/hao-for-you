"""Chatbot route unit tests."""
from app.api.chatbot_router import echo


def test_echo():
    response = echo("Hello, World!")
    assert response == {
        "received": "Hello, World!",
        "message": "Echo from hao-for-you server",
    }
