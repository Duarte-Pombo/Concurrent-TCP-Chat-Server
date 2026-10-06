FROM gradle:8.7-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle clean build -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/build/libs/server.jar ./
COPY --from=builder /app/build/libs/client.jar ./

# Expose the server port
EXPOSE 1234