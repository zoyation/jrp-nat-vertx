
# jrp Intranet Penetration Quick Start Guide
jrp-nat-vertx supports "HTTP, HTTPS, TCP, UDP" port mapping penetration and "HTTP proxy, HTTPS proxy, STCP (sock4/SOCKS5 TCP)" forward proxy penetration

## 1. Download and Extract
1. **Server (deployed on an external network server):** https://gitee.com/java-tony/jrp-nat-vertx/releases/download/v1.1.0/jrp-server-1.1.0.zip
2. **Client (deployed on an intranet machine, connects to the server):** https://gitee.com/java-tony/jrp-nat-vertx/releases/download/v1.1.0/jrp-client-1.1.0.zip

## 2. Deployment
### Server Deployment (External Network Server)
1. **Open firewall ports:** 10010 (verify startup), 2000 (registration), 8001 (client external network configuration page), 1080 (forward proxy penetration port)
2. **Install Java:** JDK8 or JRE8
3. **Start the service:**
    - Windows: Double-click start.bat
    - Linux: Run `chmod u+x start.sh` (grant permissions on first run), then execute `./start.sh`
4. **Verify startup:** Visit http://IP:10010/jrp-server to get the public network egress IP of the access endpoint

### Client Deployment (Intranet Machine)
1. **Modify configuration:** Change the IP value in the `register-address` in `application.yml` to the server's IP
2. **Install Java:** JDK8 or JRE8
3. **Start the client:** Use `start.bat` or `start.sh` as above
4. **Management interface:** Visit http://127.0.0.1:8000/jrp-client/web/ to configure penetration (or modify the `config.json` file, no restart required)

## 3. Two Usage Methods
### Method 1: Port-based Penetration (Simple and Direct)
- **Supported types:** HTTP, HTTPS, TCP, UDP
- **Access method:** Directly access intranet services via `ExternalIP:Port`
- **First-time authentication required:** Default username `client`, default password `10086`

### Method 2: Forward Proxy Penetration (Comprehensive)
- **Supported types:**
    - HTTP(S) proxy: HTTP_PROXY, HTTPS_PROXY
    - SOCKS proxy: SOCKS4, SOCKS5
    - Smart proxy: SMART_PROXY, automatically matches HTTP_PROXY, HTTPS_PROXY, SOCKS4, SOCKS5
- **Setup steps:**
1. Configure proxy on your computer: Set IP to the external network IP, port 1080
2. Visit http://IP:1080 in your browser to complete authentication
3. Enter intranet addresses directly to access all services

## 4. User Computer Configuration and Access
Users can use various methods, with the help of third-party proxy client software, or by directly configuring the proxy through Windows or browser settings.
Below are instructions for Windows proxy configuration and Google Chrome browser configuration:

### 1. Windows 11 Configuration
1. **Open settings:** "Start" -> "Settings" -> "Network & Internet" -> "Proxy" -> "Manual proxy setup" -> "Edit"
2. **Proxy IP address (only supports socks4):** Set value to `http=ExternalIP:1080;socks=ExternalIP:1080`
3. **Do not use proxy server for addresses beginning with:** Set value to `https://*;127.0.0.1;ExternalIP;http://www.*;functional.events.data.microsoft.com;chinanorth3-0.in.applicationinsights.azure.cn;access-point.cloudmessaging.edge.microsoft.com`
4. **First access:** Visit http://WANIP:1080 for authentication, enter the username and password configured in the penetration client's `application.yml` (client, 10086)
5. **Access intranet services:** Enter intranet service addresses to access intranet services

### 2. Google Chrome Browser Configuration
1. **Find the chrome.exe path:** For example, `C:\Program Files\Google\Chrome\Application\chrome.exe`
2. **Create a Chrome proxy shortcut:** Right-click `chrome.exe`, select "Send to" -> "Desktop (create shortcut)", then right-click the shortcut on desktop -> "Properties" -> append the proxy address to the "Target" field:
    - `"C:\Program Files\Google\Chrome\Application\chrome.exe" --proxy-server="http=WANIP:1080"` and double-click the shortcut to run
3. **First access:** Visit http://WANIP:1080 for authentication, enter the username and password configured in the penetration client's `application.yml` (client, 10086)
4. **Access intranet services:** Enter intranet service addresses to access intranet services

## Summary
Deploy the server on the external network, deploy the client on the intranet, configure penetration rules, and then you can access intranet services directly through the external IP or intranet IP.