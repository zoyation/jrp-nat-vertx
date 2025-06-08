#!/bin/bash
pid=`ps -ef | grep jrp-client-1.0.1.jar | grep -v grep | awk '{print $2}'`
if [ -n "$pid" ]; then
 kill -9 $pid
fi
