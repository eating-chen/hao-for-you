#!/bin/sh
set -eu

MODEL="${1:-${OLLAMA_MODEL:-qwen3.5:4b}}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${SCRIPT_DIR}/docker-compose.yaml}"
OLLAMA_SERVICE="${OLLAMA_SERVICE:-ollama}"
API_SERVICE="${API_SERVICE:-api}"

export OLLAMA_MODEL="${MODEL}"

echo "Starting ${OLLAMA_SERVICE} and ${API_SERVICE} with model ${MODEL}..."
docker compose -f "${COMPOSE_FILE}" up -d --build "${OLLAMA_SERVICE}" ai-postgres

echo "Waiting for Ollama service..."
until docker compose -f "${COMPOSE_FILE}" exec -T "${OLLAMA_SERVICE}" ollama list >/dev/null 2>&1; do
  sleep 2
done

if docker compose -f "${COMPOSE_FILE}" exec -T "${OLLAMA_SERVICE}" ollama list | awk 'NR > 1 { print $1 }' | grep -qx "${MODEL}"; then
  echo "Ollama model ${MODEL} already exists."
else
  echo "Pulling Ollama model ${MODEL}..."
  docker compose -f "${COMPOSE_FILE}" exec -T "${OLLAMA_SERVICE}" ollama pull "${MODEL}"
fi

echo "Ollama model ${MODEL} is ready."
echo "Waiting for PostgreSQL service..."
until docker compose -f "${COMPOSE_FILE}" exec -T ai-postgres pg_isready -U haoforyou -d haoforyou >/dev/null 2>&1; do
  sleep 2
done
docker compose -f "${COMPOSE_FILE}" up -d --build "${API_SERVICE}"
echo "API is running on http://localhost:8080"
