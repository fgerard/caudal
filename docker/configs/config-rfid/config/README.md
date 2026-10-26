# config-rfid

Control de salida de camiones con antenas RFID (lectoras Impinj vía `caudal.io.rfid-server`).

Copia sanitizada de una config real. Para usarla en una instancia:

```
docker cp <contenedor>:/opt/quantumlabs/CONFIGS/config-rfid/. data/
```

deja `config/` en su lugar dentro de `data/` (su `main.clj` reemplaza al de ejemplo). Después:

- Reemplazar los `CAMBIAR-*`: `config/tg-token.txt`, `config/tg-chat-id.txt`, y
  `USERNAME`/`PASSWORD` en `config/env/constants.clj`.
- Ajustar `plantId` e IPs de las lectoras (`config/main.clj`), `PLATFORM-URL`
  (`config/env/constants.clj`) y el prefijo de tags (`config/rfid-tag.regex`).
- En `config/instance.env`: `PORTS=9911` (dashboard) y `HTTP_PORT=9911`. Las lectoras no
  necesitan puertos publicados: caudal se conecta a ellas.
- Para prender los mensajes de debug por Telegram: crear `config/debug-telegram`.
