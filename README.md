# CPD Chat Server

Java SE 21 TCP chat system with concurrency and fault tolerance — FEUP CPD assignment 2.

## Requirements

- Java 21+
- Docker (for Ollama AI)
- bash

## Running on multiple computers (uses ngrok)

### Host
```bash
ngrok tcp 1234

# Copy the address and port. Example: 6.tcp.eu.ngrok.io:16843
```

### Clients
```bash
./gradlew assemble

java -jar build/libs/client.jar 6.tcp.eu.ngrok.io 16843
# Copied address and port
```


## Running via docker on the local host

```bash
# Building the server, takes some time to download docker
docker-compose up -d server

# Ensuring the model is active
docker exec -it chat-ollama ollama pull gemma3:1b

# Running individual clients (self destructing containers)
docker-compose run --rm client
```
## Quick Start

```bash
bash scripts/start.sh          # launch Ollama + build + start server
# In another terminal:
./gradlew runClient
```

Pass `ssl=true` to enable TLS:

```bash
ssl=true bash scripts/start.sh
./gradlew runClient -Dssl=true
```

## Gradle Commands

```bash
./gradlew build           # compile + unit tests + both jars
./gradlew test            # unit tests only
./gradlew integrationTest # integration tests (needs a running server)
./gradlew runServer       # start the server
./gradlew runClient       # start an interactive client
./gradlew runServer -Dssl=true   # SSL mode
```

## ChatRoom Message History Cap

```bash
java -Droom.history.size=XXX Server   # override the default (100) to XXX
```

## Tests

```bash
./gradlew test            # unit tests
./gradlew integrationTest # integration tests (start server first)
```

Tests cover concurrency, authentication, rooms, and logout across unit and integration suites.

## Project Structure

```
src/               Production code (Server, Client, Protocol, Managers, Room)
test/              Test suites (unit + integration)
scripts/           Build and test automation
bin/               Compiled production classes (generated)
out-test/          Compiled test classes (generated)
lib/               Test dependencies (JUnit) — download on first setup
```

## Implementation Status

✅ Multi-client TCP server with virtual threads
✅ User registration and authentication with SHA-256
✅ Token-based sessions and reconnection support
✅ Room management with real-time message broadcast
✅ LOGOUT command with session invalidation
✅ Concurrency safety with explicit locks (no java.util.concurrent collections)
✅ Full test coverage with JUnit 5
