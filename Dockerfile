FROM adoptopenjdk:14-jre-hotspot

RUN apt-get update && apt-get install -y git \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir /app
RUN useradd -ms /bin/bash appuser
USER appuser
WORKDIR /app
CMD java -jar app.jar
