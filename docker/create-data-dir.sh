#!/bin/bash
set -e

SUFFIX=$1
BUILD_DIR=$2

if [ -z "$SUFFIX" ] || [ -z "$BUILD_DIR" ]; then
  echo "Uso: ./create-data-dir.sh <sufijo> <directorio-build>"
  echo "Ejemplo: ./create-data-dir.sh logs ../caudal-0.8.8"
  exit 1
fi

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
TEMPLATE_DIR="$BASE_DIR/execution-template"
OUTPUT_DIR="$BASE_DIR/container-data"

# 🔥 extraer versión del nombre del directorio (caudal-0.8.8 → 0.8.8)
DIR_NAME=$(basename "$BUILD_DIR")
VERSION=$(echo "$DIR_NAME" | sed -E 's/.*-(.*)/\1/')

if [ -z "$VERSION" ]; then
  echo "❌ No se pudo extraer la versión de $DIR_NAME"
  exit 1
fi

echo "📌 Versión detectada: $VERSION"

# 🔥 limpiar salida
echo "🧹 Limpiando container-data..."
rm -rf "$OUTPUT_DIR"

# 🔥 crear estructura
echo "📁 Creando estructura..."
mkdir -p "$OUTPUT_DIR/data"
mkdir -p "$OUTPUT_DIR/data/logs"
mkdir -p "$OUTPUT_DIR/data/relevantes"

DATA_DIR="$OUTPUT_DIR/data"

# 🔹 validar config
CONFIG_SRC="$TEMPLATE_DIR/config-$SUFFIX"
if [ ! -d "$CONFIG_SRC" ]; then
  echo "❌ No existe $CONFIG_SRC"
  exit 1
fi

# 🔹 copiar config
echo "📦 Copiando config-$SUFFIX..."
cp -r "$CONFIG_SRC" "$DATA_DIR/"

# 🔥 sincronizar VERSION/IMAGE_NAME en instance.env (unica fuente de verdad:
# el nombre del directorio de build), igual que robot/docker/build_image.sh
# hace con su instance.env -- asi start.sh no necesita regenerarse por cada
# build, solo lee $VERSION/$IMAGE_NAME del instance.env de su instancia.
ENV_FILE="$DATA_DIR/config-$SUFFIX/instance.env"
if [ ! -f "$ENV_FILE" ]; then
  echo "❌ No existe $ENV_FILE"
  exit 1
fi
echo "📦 Actualizando VERSION/IMAGE_NAME en config-$SUFFIX/instance.env..."
sed -i.bak -E "s/^VERSION=.*/VERSION=$VERSION/; s/^IMAGE_NAME=.*/IMAGE_NAME=caudal/" "$ENV_FILE"
rm -f "$ENV_FILE.bak"

# 🔹 validar start script
START_SRC="$TEMPLATE_DIR/start-$SUFFIX.sh"
if [ ! -f "$START_SRC" ]; then
  echo "❌ No existe $START_SRC"
  exit 1
fi

# 🔥 generar start.sh (solo reescribe el nombre del directorio de config
# propio de esta instancia; VERSION/IMAGE_NAME los lee start.sh de
# instance.env en tiempo de ejecucion, no se hornean aqui)
echo "📦 Generando start.sh... $START_SRC"

sed -E "s#config-plc#config-$SUFFIX#g" "$START_SRC" > "$DATA_DIR/start.sh"

chmod +x "$DATA_DIR/start.sh"

# 🔹 copiar bin
echo "📦 Copiando bin..."
mkdir -p "$DATA_DIR/bin"
cp -r "$TEMPLATE_DIR/bin/"* "$DATA_DIR/bin/"

# 🔹 output final
echo ""
echo "✅ container-data generado correctamente"
echo "📂 Ubicación: $OUTPUT_DIR"
echo "🐳 Imagen: quantumlabs/caudal:$VERSION"
echo ""
echo "👉 Para ejecutar:"
echo "   cd $OUTPUT_DIR/data"
echo "   ./start.sh"
