# execution-template

Contrato que sigue `docker/create-data-dir.sh <sufijo> <ruta-al-distro>` para
generar los datos de una instancia (un cliente/planta) a partir de esta
carpeta. Caudal es multi-tenant: cada cliente/planta corre su propia
instancia con su propio `main.clj` (los puertos y streamers dependen
enteramente de esa config, no hay puertos fijos como en otros productos de
quantumlabs).

## Para dar de alta una instancia nueva

1. Copiar `data/config-TEMPLATE/` a `data/config-<sufijo>/` y editar ahi:
   - `main.clj` -- la config real del cliente/planta (listeners, streamers,
     puertos).
   - `log4j2.xml` -- logging, normalmente no hace falta tocarlo.
   - `instance.env` -- `CONTAINER_NAME`, `TIMEZONE`, `MINIMUM_MEMORY`,
     `MAXIMUM_MEMORY`, `TCP_PORT`/`HTTP_PORT` (deben coincidir con los
     puertos que declara `main.clj`) y `BIND_EXTRA_IPS`.
2. Copiar `data/start-TEMPLATE.sh` a `data/start-<sufijo>.sh`. No hace falta
   editarlo a mano: usa el placeholder literal `config-plc` para referirse a
   su propio directorio de config, y `create-data-dir.sh` lo reescribe a
   `config-<sufijo>` (y la version de la imagen) al generar `start.sh` para
   esa instancia.
3. (Opcional) agregar scripts especificos de la instancia en `bin/` --
   `create-data-dir.sh` copia todo `bin/` tal cual a `data/bin/` de la
   instancia generada. Ya trae `health-check.sh` (verifica que el
   contenedor responde en `HTTP_PORT`).
4. Generar los datos de la instancia:
   ```
   docker/create-data-dir.sh <sufijo> <ruta-a-caudal-$VERSION>
   ```
   Esto produce `docker/container-data/data/` listo para copiar al servidor
   destino y correr `./start.sh` ahi.

`config-TEMPLATE/` reusa el ejemplo `config/test_config.clj` del repo
(listener TCP en 7777, listener REST en 8099) como punto de partida simple
y funcional -- no es la config de ningun cliente real.
