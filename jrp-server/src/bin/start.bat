chcp 65001
cd D:\jrp-server
D:
java -server -Dfile.encoding=utf-8 -Dspring.config.location=./application.yml -Xms512m -Xmx1024m -jar jrp-server-1.0.1.jar