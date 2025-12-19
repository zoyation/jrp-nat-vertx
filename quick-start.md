# JRP内网穿透快速上手指南
## 一、下载解压
1. **服务端（部署在外网服务器）：** https://gitee.com/java-tony/jrp-nat-vertx/releases/download/v1.1.0/jrp-server-1.1.0.zip
2. **客户端（部署在内网机器，连接服务端）：** https://gitee.com/java-tony/jrp-nat-vertx/releases/download/v1.1.0/jrp-client-1.1.0.zip
## 三、部署
### 服务端部署（外网服务器）
1. **防火墙开放端口：** 10010(验证启动)、2000(注册)、8001(客户端外网配置页面)、1080(正向代理穿透端口)
2. **安装Java：** JDK8或JRE8
3. **启动服务：**
   - Windows：双击 start.bat 
   - Linux：chmod u+x start.sh 后（首次启动给权限），执行 ./start.sh
4. **验证启动：** 访问 http://IP:10010/jrp-server 可获取访问端公网出口IP

### 客户端部署（内网机器）
1. **修改配置：** application.yml中register-address值里的IP改为服务端IP
2. **安装Java：** JDK8或JRE8
3. **启动客户端：** 同样通过 start.bat 或 start.sh
4. **管理界面：** 访问 http://127.0.0.1:8000/jrp-client/web/ 配置穿透(也可修改config.json文件，无需重启)

## 三、两种使用方式
### 方式一：基于端口穿透（简单直接）
- **适用类型：** HTTP、HTTPS、TCP、UDP
- **访问方式：** 直接通过 外网IP:端口 访问内网服务
- **首次需认证：** 默认用户名client，默认密码10086
### 方式二：正向代理穿透（一网打尽）
- **适用类型：** 
  - HTTP(S)代理：HTTP_PROXY、HTTPS_PROXY
  - socks代理：SOCKS4、SOCKS5
  - 智能代理：SMART_PROXY，自动匹配HTTP_PROXY,HTTPS_PROXY,SOCKS4,SOCKS5
- **设置步骤：**
1. 电脑配置代理：IP设为外网IP，端口1080
2. 浏览器访问 http://IP:1080 完成认证
3. 直接输入内网地址访问所有服务


## 四、用户电脑配置和访问
用户使用时支持多种方式，可借助第三方用户端代理软件，也可以直接通过windows或浏览器配置代理。
下面介绍windows代理配置使用和Chrome浏览器配置使用：
### 1、win11配置
1. **打开设置界面：**"开始"->"设置"->"网络和 Internet"->"代理"->"手动设置代理"->"编辑" 
2. **代理IP地址(只支持socks4)：** 值可设置为http=外网IP:1080;socks=外网IP:1080
3. **请勿对以下列条目开头的地址使用代理服务：** 值设置为https://*;127.0.0.1;外网IP;http://www.*;functional.events.data.microsoft.com;chinanorth3-0.in.applicationinsights.azure.cn;access-point.cloudmessaging.edge.microsoft.com
4. **首次访问：** 需访问 http://WANIP:1080 认证，输入穿透客户端application.yml里配置的用户名和密码（client，10086）进行认证
5. **访问内网服务：** 输入内网服务地址访问内网服务

### 2、Google Chrome浏览器配置
1. **找到chrome.exe浏览器的路径：** 比如C:\Program Files\Google\Chrome\Application\chrome.exe
2. **创建chrome代理快捷方式：** 右键chrome.exe，选择"发送到"->"桌面快捷方式", 然后到桌面选择快捷方式右键->属性->目标地址后面填写代理地址：
   - "C:\Program Files\Google\Chrome\Application\chrome.exe" --proxy-server="http://WANIP:1080"并双击快捷方式运行
3. **首次访问：** 需访问 http://WANIP:1080 认证，输入穿透客户端application.yml里配置的用户名和密码（client，10086）进行认证
4. **访问内网服务：** 输入内网服务地址访问内网服务

## 总结
外网部署服务端，内网部署客户端，配置穿透规则，即可通过外网IP或者内网IP直接访问内网服务