# ---- build stage ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /work
COPY --from=build /app/target/quarkus-app/ /work/quarkus-app/
EXPOSE 12000
CMD ["java","-jar","/work/quarkus-app/quarkus-run.jar"]
