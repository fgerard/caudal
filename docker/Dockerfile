FROM azul/zulu-openjdk:21

ENV APP_HOME=/opt/quantumlabs/caudal

WORKDIR ${APP_HOME}

COPY . ${APP_HOME}

RUN chmod +x ${APP_HOME}/bin/caudal

ENV PATH=${PATH}:${APP_HOME}/bin

CMD ["caudal", "start"]
