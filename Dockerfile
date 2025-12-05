FROM amazoncorretto:21
LABEL authors="jmulenga"

WORKDIR /home
COPY target/news-service.jar .
