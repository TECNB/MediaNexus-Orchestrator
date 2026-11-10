FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends libarchive-tools \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

ENV TZ=Asia/Shanghai
COPY --from=build /app/target/*.jar /app/medianexus-orchestrator.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/medianexus-orchestrator.jar"]

