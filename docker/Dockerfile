FROM openjdk:10-jre-slim

LABEL creator Daniel Estevez <destevez@interware.com.mx>
MAINTAINER Daniel Estevez <destevez@interware.com.mx>

ENV PACKAGE_NAME=caudal \
    PACKAGE_VERSION=REPLACE_VERSION

ENV CAUDAL_HOME=/home/${PACKAGE_NAME} \
    CAUDAL_EXT=/home/${PACKAGE_NAME}/ext \
    CAUDAL_CONF=/home/${PACKAGE_NAME}/config 

ADD ${PACKAGE_NAME}-${PACKAGE_VERSION}.tar.gz /home/

RUN mv /home/${PACKAGE_NAME}-${PACKAGE_VERSION} /home/${PACKAGE_NAME}

VOLUME [ "$CAUDAL_CONF" "$CAUDAL_EXT" ]

WORKDIR $CAUDAL_HOME

ENV PATH=${PATH}:${CAUDAL_HOME}/bin

CMD [ "caudal", "start" ]
