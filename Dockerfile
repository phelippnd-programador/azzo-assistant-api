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

# SEC-013: rodar como usuario nao-root.
RUN groupadd -r azzo && useradd -r -g azzo azzo && chown -R azzo:azzo /work
USER azzo

# SEC-013: porta consistente com quarkus.http.port=8081.
EXPOSE 8081
CMD ["java", "-jar", "quarkus-run.jar"]
