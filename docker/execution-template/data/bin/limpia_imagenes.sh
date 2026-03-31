#!/bin/bash

# configuración
ROOT="/opt/quantum/event-stream-c5colima/data/images"
MIN_FREE_GB=170
TARGET_FREE_GB=185
FILESYSTEM="/opt"
LOGFILE="/var/log/limpia_imagenes.log"

# función de log con timestamp
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOGFILE"
}

# función para obtener espacio libre en GB
free_gb() {
    df -BG "$FILESYSTEM" | awk 'NR==2 {gsub("G","",$4); print $4}'
}

log "------------------------------------------------------------"
log "Inicio de ejecución del limpiador"

CURRENT_FREE=$(free_gb)
log "Espacio libre actual: $CURRENT_FREE GB"

if (( CURRENT_FREE >= MIN_FREE_GB )); then
    log "No es necesario borrar. Fin."
    exit 0
fi

log "Espacio por debajo de $MIN_FREE_GB GB. Iniciando eliminación..."
log "Objetivo de histéresis: llegar a $TARGET_FREE_GB GB."

# listar directorios YYYY/MM/DD/HH ordenados por antigüedad
mapfile -t DIRS < <(find "$ROOT" -mindepth 4 -maxdepth 4 -type d | sort)

for d in "${DIRS[@]}"; do
    log "Eliminando directorio: $d"
    rm -rf "$d"

    CURRENT_FREE=$(free_gb)
    log "Espacio libre ahora: $CURRENT_FREE GB"

    if (( CURRENT_FREE >= TARGET_FREE_GB )); then
        log "Histéresis alcanzada ($TARGET_FREE_GB GB). Finalizando limpieza."
        exit 0
    fi
done

log "Advertencia: No quedaron más directorios para borrar.":0
