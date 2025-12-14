#!/bin/bash
pid=`ps -ef | grep jrp-client.jar | grep -v grep | awk '{print $2}'`
if [ -n "$pid" ]; then
 kill -9 $pid
fi
