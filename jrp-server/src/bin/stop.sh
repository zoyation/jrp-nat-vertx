#!/bin/bash
pid=`ps -ef | grep jrp-server-1.0.0-SNAPSHOT.jar | grep -v grep | awk '{print $2}'`
if [ -n "$pid" ]; then
 kill -9 $pid
fi
