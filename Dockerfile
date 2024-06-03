FROM amazoncorretto:21-alpine
LABEL authors="jmulenga"

WORKDIR /home
COPY target/news-service.jar .
