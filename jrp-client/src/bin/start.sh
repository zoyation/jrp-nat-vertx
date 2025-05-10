#!/bin/bash
pid=`ps -ef | grep jrp-client-1.0.0-SNAPSHOT.jar | grep -v grep | awk '{print $2}'`
if [ -n "$pid" ]; then
  kill -9 $pid
fi
nohup java -server -Dfile.encoding=utf-8 -Dspring.config.location=./application.yml -Xms512m -Xmx1024m -jar jrp-client-1.0.0-SNAPSHOT.jar start>/dev/null 2>&1 &
sleep 5
tail -f log/jrp.log
