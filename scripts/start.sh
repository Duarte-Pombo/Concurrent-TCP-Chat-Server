#!/usr/bin/env bash
set -e

# Start or restart the Ollama container
if docker ps -q -f name=ollama | grep -q .; then
  echo "[*] Ollama container already running"
else
  if docker ps -aq -f name=ollama | grep -q .; then
    docker start ollama
  else
    docker run -d --name ollama -p 11435:11434 -v ollama_data:/root/.ollama ollama/ollama
    sleep 2
    docker exec ollama ollama pull gemma3:1b
  fi
  echo "[*] Ollama started on port 11435"
fi

# Kill any existing server
lsof -ti :1234 | xargs kill -9 2>/dev/null || true

# Build and start server via Gradle
echo "[*] Building..."
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./gradlew compileJava --quiet

echo "[*] Starting server..."
./gradlew runServer -Dollama.url=http://localhost:11435 ${ssl:+-Dssl=true}