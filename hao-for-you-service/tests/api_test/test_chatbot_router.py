"""test api router for finish_info"""
from unittest.mock import patch
from fastapi.testclient import TestClient
from app.main import app


client = TestClient(app)


def test_echo():
    response = client.post("/echo", json={"message": "Hello, World!"})
    assert response.status_code == 200
