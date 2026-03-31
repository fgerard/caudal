#!/bin/bash
set -e

SUFFIX=$1

if [ -z "$SUFFIX" ]; then
  echo "Uso: ./generate-data.sh <sufijo>"
  echo "Ejemplo: ./generate-data.sh c5"
  exit 1
fi

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
TEMPLATE_DIR="$BASE_DIR/execution-template"
OUTPUT_DIR="$BASE_DIR/container-data"

echo "🧹 Limpiando container-data..."
rm -rf "$OUTPUT_DIR"

echo "📁 Creando estructura..."
mkdir -p "$OUTPUT_DIR/data"
mkdir -p "$OUTPUT_DIR/data/logs"
mkdir -p "$OUTPUT_DIR/data/relevantes"

# 🔹 Validar config
CONFIG_DIR="$TEMPLATE_DIR/config-$SUFFIX"
if [ ! -d "$CONFIG_DIR" ]; then
  echo "❌ No existe $CONFIG_DIR"
  exit 1
fi

echo "📦 Copiando configuración..."
cp -r "$CONFIG_DIR" "$OUTPUT_DIR/data/config"

# 🔹 start script
START_SCRIPT="$TEMPLATE_DIR/start-$SUFFIX.sh"
if [ ! -f "$START_SCRIPT" ]; then
  echo "❌ No existe $START_SCRIPT"
  exit 1
fi

echo "📦 Copiando start script..."
cp "$START_SCRIPT" "$OUTPUT_DIR/data/start.sh"
chmod +x "$OUTPUT_DIR/data/start.sh"

# 🔹 bin
echo "📦 Copiando bin..."
mkdir -p "$OUTPUT_DIR/data/bin"
cp -r "$TEMPLATE_DIR/bin/"* "$OUTPUT_DIR/data/bin/"

echo "✅ container-data generado correctamente para: $SUFFIX"
