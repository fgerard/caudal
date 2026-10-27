# config-person

Detección de personas por cámara: cuando aparece alguien frente a una cámara (con histéresis por
`:alias`), manda un texto y la foto (`:image`, jpg en base64) por Telegram.

Copia sanitizada de una config real. Para usarla en una instancia:

```
docker cp <contenedor>:/opt/quantumlabs/CONFIGS/config-person/. data/
```

deja `config/` en su lugar dentro de `data/` (su `main.clj` reemplaza al de ejemplo). Después:

- Reemplazar los `CAMBIAR-*`: `config/tg-token.txt` y `config/tg-chat-id.txt`.
- Ajustar `plantId` y `origin` (`config/main.clj`).
- En `config/instance.env`: `PORTS=9999,8070,8090` (TCP, REST y dashboard) y `HTTP_PORT=8090`.
