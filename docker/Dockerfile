FROM azul/zulu-openjdk:21-lts

ENV APP_HOME=/opt/quantumlabs/ai_streamer

WORKDIR ${APP_HOME}

COPY . ${APP_HOME}

RUN chmod +x ${APP_HOME}/bin/ai_streamer

ENV PATH=${PATH}:${APP_HOME}/bin

CMD ["ai_streamer", "start"]
