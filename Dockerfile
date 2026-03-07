FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV DB_PATH=/app/data/bot.db

RUN mkdir -p /app/data

COPY --from=build /app/target/roof-bot.jar /app/app.jar

# Optional: put your 1.jpg next to Dockerfile to include it in the image.
COPY 1.jpg /app/1.jpg

CMD ["java", "-jar", "/app/app.jar"]
