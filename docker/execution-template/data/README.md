# data/

Directorio de una instancia de caudal. `start.sh` lo monta en el contenedor como
`/opt/quantumlabs/caudal/data`, y ese es también el directorio de trabajo de caudal: las rutas
relativas de `main.clj` y `log4j2.xml` (`./logs`, `./relevantes`) caen aquí.

Puede haber varias instancias en el mismo host, cada una en su propio `data/` (ej.
`/opt/quantum/caudal-rfid/data`, `/opt/quantum/caudal-axis/data`), con `CONTAINER_NAME` y
`PORTS` distintos en su `instance.env`.

```
data/
├── start.sh            crea y arranca el contenedor
├── README.md           este archivo
├── bin/
│   ├── health-check.sh contenedor corriendo y respondiendo en HTTP_PORT
│   ├── logs.sh         docker logs -f del contenedor (args extra van a docker logs)
│   ├── restart.sh      reinicia el contenedor (toma cambios de main.clj y ext/)
│   └── docker-lib.sh   comun a los anteriores
├── config/
│   ├── instance.env    parametros del contenedor (ver abajo)
│   ├── main.clj        la configuración de caudal: listeners, streamers, sinks
│   └── log4j2.xml      logging de caudal
├── ext/                jars extra para el classpath de esta instancia
├── logs/               logs que escriba log4j2.xml, si se configura un archivo
└── relevantes/         salida que escriban los sinks de main.clj, si la usan
```

## instance.env

`start.sh` y `bin/*.sh` lo leen con `source`; es la única fuente de verdad de la instancia.

| Variable | Qué es |
|---|---|
| `VERSION` | Versión de la imagen `quantumlabs/caudal`. Cambiarla es como se actualiza (ver abajo). |
| `IMAGE_NAME` | `caudal`. |
| `CONTAINER_NAME` | Nombre del contenedor. Único en el host. |
| `TARGET_USER`, `TARGET_GROUP` | Usuario y grupo del host con los que corre el contenedor (`-u uid:gid`). Deben existir y ser dueños de `data/`. |
| `TZ` | Zona horaria de caudal (`-Duser.timezone`) y del contenedor. |
| `MINIMUM_MEMORY`, `MAXIMUM_MEMORY` | `-Xms` / `-Xmx` de la JVM. |
| `PORTS` | Puertos que declaran los listeners de `main.clj`, separados por comas (`514/udp` para UDP). Se publican con el mismo número en host y contenedor. |
| `HTTP_PORT` | Puerto REST/dashboard que prueba `bin/health-check.sh`. Vacío = no se prueba HTTP. |
| `NETWORK` | Red de docker. Vacío = la de omisión. `quantum-network` para llegar a otros contenedores por nombre (ej. `http://qface-server:8000`); se crea si no existe. |
| `BIND_EXTRA_IPS` | IPs del host, además de `127.0.0.1`, donde se publican los `PORTS`. Ver la advertencia en el propio archivo: `0.0.0.0` los abre a internet, ufw no los filtra. |

Desde el contenedor, los servicios del propio host se alcanzan como `host.docker.internal`
(siempre que escuchen en una IP alcanzable desde docker, no solo en `127.0.0.1`).

## main.clj y log4j2.xml

`main.clj` y sus archivos auxiliares son la definición del procesamiento de eventos de cada
cliente; caudal solo pone la plataforma. Arranca con `-c config/`, igual que `bin/caudal`
fuera de docker: carga **todos** los `.clj` de `config/` (y subdirectorios) y su
`log4j2.xml`. Por eso no hay que dejar ahí respaldos `.clj` viejos. `config/` también está en
el classpath, así que los `.clj` pueden requerirse entre sí. Dentro del contenedor están
`CAUDAL_CONFIG` (`.../data/config`) y `CAUDAL_DATA` (`.../data`).

La imagen trae configs sanitizadas de clientes típicos en `/opt/quantumlabs/CONFIGS/<nombre>/`
(sin passwords ni tokens), con la forma de un `data/`. Su `config/README.md` dice qué llenar:

```
docker cp <contenedor>:/opt/quantumlabs/CONFIGS/config-rfid/. data/
```

Dentro del contenedor, cada listener de `main.clj` tiene que escuchar en `:host "0.0.0.0"`.
Algunos (ej. `caudal.io.tcp-server`) usan `localhost` por omisión, que en docker es el loopback
del contenedor: el puerto publicado acepta la conexión pero el evento nunca llega. Quién puede
alcanzar el puerto desde fuera lo controlan `PORTS` y `BIND_EXTRA_IPS`, no el `:host`.

El `main.clj` que trae la imagen es solo un ejemplo funcional (listener TCP en 7777, REST en
8099, dashboard en 8080), para reemplazarlo por el del cliente.

## Operación

- Arrancar: `./start.sh`. Falla si ya existe un contenedor con `CONTAINER_NAME`.
- Cambios en `main.clj`, `log4j2.xml` o `ext/`: `bin/restart.sh`.
- Cambios en `instance.env` (versión, puertos, memoria, red): recrear el contenedor,
  `docker rm -f <CONTAINER_NAME>` y `./start.sh`.
- Actualizar caudal: cambiar `VERSION` en `instance.env` y recrear el contenedor. Para traer
  también los `start.sh`/`bin/` nuevos: `bootstrap.sh --version <ver> --force`, que nunca
  sobrescribe nada en `config/`.
- Ver logs: `bin/logs.sh`. Revisar salud: `bin/health-check.sh`.
