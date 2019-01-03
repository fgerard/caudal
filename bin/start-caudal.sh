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

export BIN_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
echo "BIN path $BIN_PATH"
export ROOT_PATH=$(dirname $BIN_PATH)
echo "Starting Caudal from $ROOT_PATH"

export caudal_lib_name=`ls lib/caudal*`

#$JAVA_BIN -Djava.ext.dirs=lib mx.interware.caudal.core.StarterDSL "$@"
#/usr/lib/jvm/java-1.8.0-openjdk-amd64/bin/java -cp $caudal_lib_name mx.interware.caudal.core.StarterDSL "$@"
nohup $JAVA_BIN -cp $caudal_lib_name mx.interware.caudal.core.StarterDSL "$@" &
