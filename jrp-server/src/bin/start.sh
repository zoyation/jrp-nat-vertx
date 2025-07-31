#!/bin/bash
pid=`ps -ef | grep jrp-server-1.0.1.jar | grep -v grep | awk '{print $2}'`
if [ -n "$pid" ]; then
 kill -9 $pid
fi
nohup java -Dfile.encoding=utf-8 -Dspring.config.location=./application.yml -jar jrp-server-1.0.1.jar start>/dev/null 2>&1 &
sleep 5
tail -f log/jrp.log