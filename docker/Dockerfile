# syntax=docker/dockerfile:1.4
# Se construye con docker/bin/build.sh, que antes arma el uberjar en el host: el build de lein
# no puede correr aqui porque OctaneSDKJava (Impinj) y llrp solo existen en el ~/.m2 local, no
# en Maven Central. El contexto es la raiz del repo, filtrado por .dockerignore (lista blanca).
FROM azul/zulu-openjdk:21

# La version sale de project.clj (build.sh la pasa); con ella se escoge el jar y se sincroniza
# el VERSION del instance.env del template, para que bootstrap.sh extraiga uno que coincida
# con la imagen de la que salio.
ARG VERSION

WORKDIR /opt/quantumlabs/caudal

# No hay que meterle nada a data, porque se ligara con la carpeta data/ de la instancia en el
# host (ver start.sh): config, logs, jars extra, etc.
# ext -> data/ext: bin/caudal ya mete $CAUDAL_HOME/ext/* en el classpath, asi cada instancia
# puede traer sus jars extra en su data/ext sin reconstruir la imagen.
RUN mkdir -p data && ln -s data/ext ext

COPY bin/caudal bin/caudal
COPY target/caudal-${VERSION}-standalone.jar lib/
# `web` sirve el dashboard desde $CAUDAL_HOME/resources/public en disco, no desde el jar.
COPY resources/public resources/public

# Respaldo del template de ejecucion (start.sh, bin/, config/, README.md) dentro de la imagen,
# para que bootstrap.sh arme un data/ nuevo con `docker cp` sin mandar los archivos aparte.
COPY docker/execution-template/data/start.sh execution-template/start.sh
COPY docker/execution-template/data/README.md execution-template/README.md
COPY docker/execution-template/data/bin execution-template/bin
COPY docker/execution-template/data/config execution-template/config
# Permisos explicitos, no los del host (COPY los conserva, y en un checkout con umask 077
# bin/caudal llega 700): el contenedor corre con un uid arbitrario (-u en start.sh), que necesita
# poder leer los scripts, no solo ejecutarlos.
RUN sed -i "s/^VERSION=.*/VERSION=${VERSION}/" execution-template/config/instance.env && \
    chmod -R a+rX bin lib resources execution-template && \
    chmod 755 bin/caudal execution-template/start.sh execution-template/bin/*.sh

ENV PATH=${PATH}:/opt/quantumlabs/caudal/bin
ENV CAUDAL_CONFIG=/opt/quantumlabs/caudal/data/config/main.clj

# Directorio de trabajo en data/: las rutas relativas de main.clj y log4j2.xml (./logs,
# ./relevantes) caen en el volumen de la instancia y no dentro del contenedor.
WORKDIR /opt/quantumlabs/caudal/data

CMD ["caudal", "start"]
