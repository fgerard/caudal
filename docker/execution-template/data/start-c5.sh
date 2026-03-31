#!/bin/bash

#OJO se requiere que este contenedor corra dentro de una network
# sudo docker network create -d brige quantum-networkho

echo ""
echo ""
echo "This shell (start.sh) must be started from with in the 'data' directory or pass in the path to the data dir as the first parameter"

TARGET_USER="quantum"

# Run as specific user (default: quantum)
if id "$TARGET_USER" >/dev/null 2>&1; then
  TARGET_UID=$(id -u "$TARGET_USER")
  TARGET_GID=$(id -g "$TARGET_USER")
  USER_FLAG="-u $TARGET_UID:$TARGET_GID"
  echo "Setting container user to $TARGET_USER ($TARGET_UID:$TARGET_GID)"
else
  echo "User '$TARGET_USER' not found on host system. Container will run as defined in Dockerfile."
  USER_FLAG=""
fi

export DATA_DIR="$1"

if [ -z $1 ]
then
  export DATA_DIR="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
fi

export ORIGIN=`hostname`

echo $ORIGIN

echo ""

echo $DATA_DIR

docker run --restart=no \
	$USER_FLAG \
        --network quantum-network \
        --add-host=host.docker.internal:host-gateway \
	--log-opt max-size=1m \
        --name event-stream \
	-v /mnt/datos/images:/mnt/datos/images \
        -v $DATA_DIR:/opt/quantumlabs/ai_streamer/data \
        -e CAUDAL_CONFIG=/opt/quantumlabs/ai_streamer/data/config \
	-e CAUDAL_DATA=/opt/quantumlabs/ai_streamer/data \
        -p 7091:8090 \
        -p 7070:8070 \
        -p 9999:9999 \
        -d \
        quantumlabs/event-stream:1.9.5
