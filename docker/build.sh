#!/usr/bin/env bash
#
# Docker Image Generator 
#

export DKR_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
export ROOT_PATH=$(dirname $DKR_PATH)
export PROJECT=$( grep "defproject" $ROOT_PATH/project.clj )
export SERVICE_NAME=$( echo $PROJECT | awk '{print($2)}' )
export SERVICE_VERSION=$( echo $PROJECT | awk '{print(substr($3,2,length($3)-2))}' )

echo "Docker build path : $DKR_PATH"

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
export DKR_IMG=$SERVICE_NAME:$SERVICE_VERSION

echo $DKR_IMG

cd $ROOT_PATH && bin/make-distro.sh && rm -fv $DKR_PATH/$SERVICE_NAME-$SERVICE_VERSION.tar.gz && mv $SERVICE_NAME-$SERVICE_VERSION.tar.gz $DKR_PATH/ && git checkout $DKR_PATH/Dockerfile && cd $DKR_PATH  && sed -i.bk s/REPLACE_VERSION/$SERVICE_VERSION/g Dockerfile && docker build -t $DKR_IMG .
