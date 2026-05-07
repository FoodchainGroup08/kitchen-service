FROM eclipse-temurin:17-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
