FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy pom and source, then build the executable jar
COPY pom.xml ./
COPY src ./src
RUN mvn -U -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy jar from build stage (matches the Spring Boot fat jar)
COPY --from=build /workspace/target/*.jar ./app.jar

# Application listens on port 8008 by default
EXPOSE 8008

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
