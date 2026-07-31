FROM eclipse-temurin:21-jre-alpine
COPY ./build/install/ /opt/
WORKDIR /opt/core
HEALTHCHECK CMD wget --spider -q http://localhost:8080/actuator/health/ping || exit 1
CMD ["sh", "./bin/core"]
EXPOSE 8080
