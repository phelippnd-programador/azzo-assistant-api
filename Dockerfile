# Etapa 1 - Build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B package -DskipTests

# Etapa 2 - Runtime
FROM eclipse-temurin:21-jre
WORKDIR /work/

COPY --from=build /app/target/quarkus-app/ /work/

EXPOSE 8081
CMD ["java", "-jar", "quarkus-run.jar"]
