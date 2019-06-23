FROM openjdk:10-jre-slim

MAINTAINER Daniel Estevez <destevez@quantumlabs.ai>

ENV PACKAGE_NAME=caudal \
    PACKAGE_VERSION=0.7.17

ENV CAUDAL_HOME=/home/${PACKAGE_NAME} \
    CAUDAL_EXT=/home/${PACKAGE_NAME}/ext \
    CAUDAL_CONF=/home/${PACKAGE_NAME}/config 

ADD ${PACKAGE_NAME}-${PACKAGE_VERSION}.tar.gz /home/

RUN mv /home/${PACKAGE_NAME}-${PACKAGE_VERSION} /home/${PACKAGE_NAME}

VOLUME [ "$CAUDAL_CONF" "$CAUDAL_EXT" ]

WORKDIR $CAUDAL_HOME

ENV PATH=${PATH}:${CAUDAL_HOME}/bin

CMD [ "caudal", "start" ]
