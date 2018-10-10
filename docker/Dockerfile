FROM anapsix/alpine-java:8_server-jre

LABEL creator Daniel Estevez <destevez@interware.com.mx>
MAINTAINER Daniel Estevez <destevez@interware.com.mx>

ENV PACKAGE_NAME=caudal

ENV JAVA_HOME=/opt/jdk \
    CAUDAL_HOME=/home/${PACKAGE_NAME} \
    CAUDAL_LIB=/home/${PACKAGE_NAME}/lib \
    CAUDAL_CONF=/home/${PACKAGE_NAME}/config 

ADD ${PACKAGE_NAME}*tar.gz /home/

VOLUME [ "$CAUDAL_CONF" "$CAUDAL_LIB" ]

WORKDIR $CAUDAL_HOME

ENV PATH=${PATH}:/opt/jdk/bin:${CAUDAL_HOME}/bin

CMD [ "caudal", "start" ]
