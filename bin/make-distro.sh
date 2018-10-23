#!/usr/bin/env bash
#
# Distro Generator
#

export DKR_PATH="$(cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )" && \
export ROOT_PATH=$(dirname $DKR_PATH) && \
export PROJECT=$( grep "defproject" $ROOT_PATH/project.clj ) && \
export SERVICE_NAME=$( echo $PROJECT | awk '{split($2,t0,"/"); print(t0[2])}' ) && \
export SERVICE_VERSION=$( echo $PROJECT | awk '{print(substr($3,2,length($3)-2))}' ) && \
export DISTRO_DIR=$SERVICE_NAME-$SERVICE_VERSION && \
cd $ROOT_PATH && \
lein clean && \
lein with-profile prod uberjar && \
rm -rvf $DISTRO_DIR* && \
mkdir -p $DISTRO_DIR/lib $DISTRO_DIR/config && \
cp -v target/*standalone.jar $DISTRO_DIR/lib/ && \
cp -vr bin $DISTRO_DIR/ && \
cp -vr resources $DISTRO_DIR/ && \
cp -v config/caudal-config.clj $DISTRO_DIR/config/ && \
cp -v config/log4j2.xml $DISTRO_DIR/config/ && \
cp -v project.clj $DISTRO_DIR/ && \
tar cvzf $DISTRO_DIR.tar.gz $DISTRO_DIR
