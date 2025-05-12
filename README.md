# jrp-nat 内网穿透工具
## 内网穿透工具介绍
Java Reverse Proxy Network Address Translation，基于spring boot、vert.x开发的跨平台的内网穿透工具（jrp-server+jrp-Client），服务中转方式实现。

内网穿透工具包括服务端jrp-server和客户端jrp-Client，在先在有固定公网IP和开放对应端口的服务器上部署穿透服务中转程序jrp-server，然后在内网部署服务代理程序jrp-Client，后期支持通过可视化web配置页面或者配置文件管理配置。
## 软件架构
软件架构说明
Spring Boot 2.7.14（运行控制、配置管理）+Vert.x 4.5.3（服务管理、服务代理、服务中转）+vue3(element ui实现web端管理配置信息)
## 安装教程
1. 安装jdk8+或jre8+
2. 修改配置文件application.yml里vertx.jrp下参数：     
   a. 内网穿透中转服务jrp-server配置（带独立外网ip和端口的服务器）：

   ```
   vertx:
     jrp:
       #内网服务注册访问地址
       register-port: 2000
       #内网穿透中转服务web管理页面端口
       page-port: 10086
       #内网穿透中转服务web管理页面访问路径
       page-path: /jrp-server
       #内网穿透中转服务web管理页面登录用户名
       username: admin
       #内网穿透中转服务web管理页面密码
       password: 10010
       #http Digest认证算法
       algorithm: MD5
       #内网穿透服务注册验证信息，客户端需要和服务端一样，不然不能注册。
       token: 2023202
   ```  
   b. 内网穿透客户端服务jrp-client配置（没有外网ip，能联网访问到带外网ip和端口服务器的局域网机器）:
    ```
    vertx:
      jrp:
        #配置文件存储方式
        config-store-type: file
        #是否注册到内网穿透代理服务器
        register-to-server: true
        #内网穿透代理服务注册地址，外网ip和端口
        register-address: xxx.xxx.xxx.xxx:2000
        #内网穿透验证信息和jrp-server配置值一样，不然不能注册。
        token: 2023202
    ```

3. 修改内网穿透客户端穿透代理配置参数config.json，通过java -jar jrp-client-1.0.0-SNAPSHOT.jar启动内网穿透客户端服务（一般是一台能联网的内网服务器）,目前主要支持HTTP、TCP:
   ```
    {
     "path": "/java-proxy",//代理服务配置管理服务HTTP访问路径
     "port": 2000,//代理服务配置管理服务HTTP访问端口
      "remote_proxies": [//内网穿透配置：内网服务注册到外网中转代理服务上实现内网穿透
       {
         "type": "HTTP",//穿透类型
         "remote_port": 2002,//穿透端口，外网中转代理服务代理后的服务端口
         "proxy_pass": "http://127.0.0.1:8001"//内网服务地址
       },
       {
         "type": "TCP",
         "remote_port": 2022,//穿透端口，外网中转代理服务代理后的服务端口
         "proxy_pass": "127.0.0.1:22"
       }
      ]
    }
   ```

4. 穿透代理成功后，不管是http还是tcp代理成功后，得先通过浏览器HTTP方式访问外网ip端口，输入服务端配置的用户名密码认证信息，服务端重启后会要求重新输入认证信息。
5. windows开机启动设置配置。

   打开文件夹“C:\ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp”，start.bat脚本放到里面，示例如下：
   [start.bat](jrp-client/src/bin/start.bat)
   ```
   chcp 65001
   cd D:\jrp-client
   D:
   java -server -Dfile.encoding=utf-8 -Dspring.config.location=./application.yml -jar jrp-client-1.0.0-SNAPSHOT.jar
   ```