# config-plc-face-plate

Cámaras de placas con control de plumas y acceso de personas con reconocimiento facial y
control de torniquetes, vía PLCs Modbus (`camera-plc-config.edn`).

Copia sanitizada de una config real. Para usarla en una instancia:

```
docker cp <contenedor>:/opt/quantumlabs/CONFIGS/config-plc-face-plate/. data/
```

deja `config/` y `ext/` en su lugar dentro de `data/`. Después:

- Reemplazar los `CAMBIAR-*`: `TG-TOKEN`/`TG-CHAT-ID` en `config/constants.clj` y
  `authorization` en `config/plc_controler.clj`.
- Ajustar IPs de PLCs (`config/camera-plc-config.edn`), `url-base` (`config/plc_controler.clj`)
  y `PLATFORM-URL` (`config/env/constants.clj`).
- En `config/instance.env`: `PORTS=9999,8070,8090` (TCP, REST, dashboard) y `HTTP_PORT=8090`.
- `ext/j2mod-3.2.1.jar` es el cliente Modbus; no viene en el jar de caudal.
- Opcional: `config/QA_URL.edn` con URLs de fallback (formato comentado en
  `config/env/constants.clj`).
