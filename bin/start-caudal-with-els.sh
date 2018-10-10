#!/usr/bin/env bash


echo "Verifying JAVA instalation ..."
if type -p java; then
    echo "JAVA executable found in PATH"
    JAVA_BIN=java
elif [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    echo "JAVA executable found in JAVA_HOME"
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    echo "No JAVA installation found, please verify. Exiting ..."
    exit 1
fi

if [[ "$JAVA_BIN" ]]; then
    JAVA_VERSION=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}')
    echo "JAVA Version : $JAVA_VERSION"
fi


echo "Stalling for Elasticsearch"
while ! nc -z elasticsearch 9200; do
  echo "Waiting for Elasticsearch ..."
  sleep 2
done
echo "Elasticsearch is up and running"

export BIN_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
echo "BIN path $BIN_PATH"
export ROOT_PATH=$(dirname $BIN_PATH)
echo "Starting Caudal from $ROOT_PATH"
export CAUDAL_CONFIG=$(cat $ROOT_PATH/config/caudal-config.edn)
echo "CAUDAL_CONFIG : $CAUDAL_CONFIG"

$JAVA_BIN -Djava.ext.dirs=lib mx.interware.caudal.core.StarterDSL "$@"
