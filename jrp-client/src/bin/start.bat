chcp 65001
cd D:\jrp-client
D:
java -server -Dfile.encoding=utf-8 -Dspring.config.location=./application.yml -Xms512m -Xmx1024m -jar jrp-client-1.0.1.jar