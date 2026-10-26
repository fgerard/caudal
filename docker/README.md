# caudal - imagen docker

```
docker/
├── Dockerfile
├── bin/build.sh                construye (y con --push publica) la imagen
└── execution-template/
    ├── bootstrap.sh            se copia a mano al servidor e instala una instancia
    └── data/                   template de una instancia; viaja dentro de la imagen
```

## Build (en la máquina de desarrollo)

```
docker/bin/build.sh            # solo la arquitectura del host, cargada en el docker local
docker/bin/build.sh --push     # linux/amd64 + linux/arm64, publicada en Docker Hub
```

Compila el dashboard (`shadow-cljs release`) y el uberjar, y construye
`quantumlabs/caudal:$VERSION`, con `$VERSION` leída de `project.clj`. El uberjar se arma en el
host y no dentro de docker porque `OctaneSDKJava` y `llrp` solo existen en el `~/.m2` local.

La imagen lleva el jar, `bin/caudal` y una copia de `execution-template/data/` con el
`VERSION` de su `instance.env` ya sincronizado. El contexto del build es la raíz del repo,
filtrado por `.dockerignore` (lista blanca). La imagen es pública, así que nada de configs de
clientes.

## Instalar una instancia (en el servidor)

Requiere Docker, y que el usuario que instala esté en el grupo `docker` (o tenga `sudo`).

1. Crear el directorio de la instancia, darle dueño y copiar ahí `bootstrap.sh`:
   ```
   sudo mkdir -p /opt/quantum/caudal-<nombre>/data
   sudo chown -R quantum:quantum /opt/quantum/caudal-<nombre>
   scp docker/execution-template/bootstrap.sh <server>:/opt/quantum/caudal-<nombre>/data/
   ```
2. En el servidor, como el usuario dueño (`sudo -iu quantum`):
   ```
   cd /opt/quantum/caudal-<nombre>/data
   chmod +x bootstrap.sh
   ./bootstrap.sh --version <ver>
   ```
3. Editar `config/instance.env` y `config/main.clj` y correr `./start.sh`. El `README.md` que
   queda en `data/` documenta cada archivo, cómo actualizar y cómo operar la instancia.

Para otra instancia en el mismo host, repetir con otro directorio, y en su `instance.env`
poner otro `CONTAINER_NAME` y otros `PORTS`.
