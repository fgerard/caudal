# caudal - imagen docker

## 1. Build del distro (en la maquina de desarrollo, dentro de este repo)
```
bin/make-distro.sh
```
Compila el uberjar y arma `caudal-$VERSION/` en la raiz del repo (jar, bin/,
config/, resources/, project.clj), donde `$VERSION` sale de `project.clj`
(unica fuente de verdad para la version).

## 2. Build de la imagen (en la maquina de desarrollo)
```
docker/build.sh <ruta-a-caudal-$VERSION> [directorio-libs-extra] [--push]
```
Empaqueta `docker/container-content/` a partir del distro generado en el
paso 1 (jar, resources, bin, project.clj) y construye la imagen
`quantumlabs/caudal:$VERSION`. Con `--push` construye multi-arquitectura
(`linux/arm64,linux/amd64`) y sube a Docker Hub; sin `--push` construye solo
para la arquitectura del host y la carga al daemon local (`--load`, que no
soporta manifests multi-plataforma) para probarla ahi mismo.

## 3. Generar los datos de una instancia (cliente/planta)
```
docker/create-data-dir.sh <sufijo> <ruta-a-caudal-$VERSION>
```
Genera `docker/container-data/data/` a partir de
`docker/execution-template/config-<sufijo>/` y
`docker/execution-template/start-<sufijo>.sh`, sincronizando
`VERSION`/`IMAGE_NAME` en el `instance.env` de esa instancia (unica fuente
de verdad: el nombre del directorio de build, ej. `caudal-0.9.1`) --
`start.sh` los lee de ahi en tiempo de ejecucion, no se regenera por cada
build. Ver `docker/execution-template/README.md` para el contrato de cada
`config-<sufijo>/`/`start-<sufijo>.sh` y como crear uno nuevo a partir de
`config-TEMPLATE/`/`start-TEMPLATE.sh`.

## 4. Deploy y uso (en el servidor destino)
Copiar `container-data/data/` (con `start.sh`, `bin/` y
`config-<sufijo>/`) a donde sea en el servidor -- `start.sh` se autoubica y
monta ese mismo directorio como `/opt/quantumlabs/caudal/data` en el
contenedor. `config-<sufijo>/instance.env` trae `CONTAINER_NAME`,
`TIMEZONE`, `MINIMUM_MEMORY`, `MAXIMUM_MEMORY`, `TCP_PORT`, `HTTP_PORT` y
`BIND_EXTRA_IPS` editables a mano por instancia.
```
data/start.sh
```
Lanza `docker run` usando los parametros de `config-<sufijo>/instance.env`
(puertos config-driven: caudal no tiene puertos fijos como otros productos
de quantumlabs, cada `main.clj` de instancia declara los suyos).
