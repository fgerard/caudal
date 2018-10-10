#!/usr/bin/env bash
#
# Docker Image Generator 
#

export DKR_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
export ROOT_PATH=$(dirname $DKR_PATH)
export PROJECT=$( grep "defproject" $ROOT_PATH/project.clj )
export SERVICE_NAME=$( echo $PROJECT | awk '{split($2,t0,"/"); print(t0[2])}' )
export SERVICE_VERSION=$( echo $PROJECT | awk '{print(substr($3,2,length($3)-2))}' )

echo "Docker build path : $DKR_PATH"
echo $SERVICE_NAME
echo $SERVICE_VERSION

# Custom Registry
#
# export DKR_HOST="myprivateregistry.com"
# export DKR_PORT=5000
# export DKR_NAMESPACE="devops"

if [ -z ${DKR_HOST} ] || [ -z ${DKR_PORT} ] || [ -z ${DKR_NAMESPACE} ]; then
  echo "DKR_HOST, DKR_PORT or DKR_NAMESPACE not defined"
else
  export DKR_IMG=$DKR_HOST:$DKR_PORT/$DKR_GROUP
fi
export DKR_IMG=$DKR_IMG$SERVICE_NAME:$SERVICE_VERSION

echo $DKR_IMG

cd $ROOT_PATH && lein libdir && lein jar && mv target/$SERVICE_NAME-$SERVICE_VERSION.jar lib && cd .. && tar -czvf $ROOT_PATH/docker/$SERVICE_NAME.tar.gz $SERVICE_NAME/project.clj $SERVICE_NAME/bin $SERVICE_NAME/lib $SERVICE_NAME/config $SERVICE_NAME/caudal-dashboard && cd $DKR_PATH && docker build -t $DKR_IMG .
