#!/bin/bash

#OJO se requiere que este contenedor corra dentro de una network
# sudo docker network create -d brige quantum-networkho

echo ""
echo ""
echo "This shell (start.sh) must be started from with in the 'data' directory or pass in the path to the data dir as the first parameter"

export DATA_DIR="$1"

if [ -z $1 ]
then
  export DATA_DIR="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
fi

export ORIGIN=`hostname`

echo $ORIGIN

echo ""

echo $DATA_DIR

docker run --restart=always \
        --network quantum-network \
        --log-opt max-size=1m \
        --name event-stream \
        -v $DATA_DIR:/opt/quantumlabs/ai_streamer/data \
        -e CAUDAL_CONFIG=/opt/quantumlabs/ai_streamer/data/config-plc \
        -p 7091:8090 \
        -p 7070:8070 \
        -p 9999:9999 \
        -d \
        quantumlabs/event-stream:1.9

