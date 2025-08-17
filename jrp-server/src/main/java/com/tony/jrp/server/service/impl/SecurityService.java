package com.tony.jrp.server.service.impl;

import com.tony.jrp.common.model.ClientRegister;
import com.tony.jrp.server.config.JRPServerProperties;
import com.tony.jrp.server.security.AuthInfo;
import com.tony.jrp.server.security.TokenUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 安全控制服务
 */
@Slf4j
@Service
public class SecurityService implements InitializingBean {
    public static final String UTF_8 = "utf-8";
    //String wwwAuth = "Basic realm=\"Restricted Area\"";
    public static final String AUTHORIZATION = "Authorization";
    private Pattern whitePattern;
    /**
     * 已授权主机列表
     */
    private final Set<String> authorizedHostSet = Collections.synchronizedSet(new HashSet<>());
    /**
     * 所有HTTP方法
     */
    private final Set<String> httpMethods = HttpMethod.values().stream().map(HttpMethod::name).collect(Collectors.toSet());
//    private Set<String> httpMethods = new HashSet<>(Arrays.asList("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE", "CONNECT"));
    /**
     * HTTP方法最大长度
     */
    private final Integer maxMethodLen = httpMethods.stream().map(String::length).max(Comparator.comparing(r -> r)).orElse(4);

    @Autowired
    protected JRPServerProperties properties;
    /**
     * @param username      用户名
     * @param password      密码
     * @param method        请求方法
     * @param host          主机
     * @param authorization 授权信息：例如Basic bG9uZ3J1YW46TFJANjg4MDc4，admin:10010
     * @return 是否授权通过
     */
    public boolean authorize(String username, String password, String method, String host, String authorization) {
        boolean authorized = false;
        if (authorizedHostSet.contains(host)) {
            authorized = true;
        } else {
            if (authorization != null && (this.checkHeaderAuth(username, password, method, host, authorization))) {
                authorizedHostSet.add(host);
                authorized = true;
            }
        }
        return authorized;
    }

    /**
     * @param clientRegister 客户端注册信息
     * @param host           主机
     * @param data           http文本信息
     * @return 是否授权通过
     */
    public boolean authorizeHttp(ClientRegister clientRegister, String host, Buffer data) {
        if (data == null) {
            return false;
        }
        String[] httpArr = data.toString().split("\r\n");
        String authorization = null;
        String uri = null;
        //GET /%E6%95%B0%E6%8D%AE%E4%B8%AD%E5%8F%B0/ HTTP/1.1
        String method = null;
        if (httpArr.length > 0) {
            String line = httpArr[0];
            String[] first = line.split(" ");
            if (first.length >= 3) {
                method = first[0];
                uri = first[1];
            }
        }
        if (uri == null) {
            return false;
        }
        for (int i = 1; i < httpArr.length; i++) {
            String line = httpArr[i];
            if (line.startsWith(AUTHORIZATION)) {
                //截取冒号“:”后的值
                authorization = line.substring(AUTHORIZATION.length() + 1);
                break;
            }
        }
        if (authorization == null) {
            return false;
        }
        try {
            boolean authorized = whitePattern.matcher(URLDecoder.decode(uri, UTF_8)).matches();
            String username = clientRegister.getUsername();
            String password = clientRegister.getPassword();
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                username = properties.getUsername();
                password = properties.getPassword();
            }
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                //如果客户端和服务端都没有配置用户名和密码，不做验证。
                return true;
            }
            return authorized || this.authorize(username, password, method, host, authorization);
        } catch (UnsupportedEncodingException e) {
            return false;
        }
    }

    /**
     * @param host 主机
     * @return 判断ip/主机是否授权过
     */
    public boolean authorized(String host) {
        return authorizedHostSet.contains(host);
    }

    /**
     * @param host 主机
     * @return www-authenticate认证参数
     */
    public String getWWWAuthenticate(String host) {
        String algorithm = properties.getAlgorithm();
        String nonce = getNonce(host);
        return " Digest realm=\"jrp-auth@example.org\",qop=\"auth, auth-int\",algorithm=" + algorithm + ",nonce=\"" + nonce + "\",opaque=\"" + TokenUtils.runtimeToken + "\"";
    }

    /**
     * 返回TCP认证报文
     *
     * @param host 主机名称、IP
     * @return 认证报文
     */
    public String getAuthenticateResponse(String host) {
        return "HTTP/1.1 401 Unauthorized\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "www-authenticate: " + getWWWAuthenticate(host) + "\r\n" +
                "\r\n";
    }

    /**
     * 验证认证信息是否有效
     *
     * @param username 用户名
     * @param password 密码
     * @param method   方法
     * @param host     主机ip
     * @param auth     授权信息，类似Authorization: Basic bG9uZ3J1YW46TFJANjg4MDc4
     * @return 检查认证结果
     */
    public boolean checkHeaderAuth(String username, String password, String method, String host, String auth) {
        try {
            if ((auth != null) && (auth.length() > 6)) {
                AuthInfo authInfo = getAuthInfo(auth);
                String HA1 = TokenUtils.MD5(username + ":" + authInfo.getRealm() + ":" + password);
                String nonce = getNonce(host);
                if (!nonce.equals(authInfo.getNonce())) {
                    return false;
                }
                String HD = String.format(authInfo.getNonce() + ":" + authInfo.getNc() + ":" + authInfo.getCnonce() + ":" + authInfo.getQop());
                String HA2 = TokenUtils.MD5(method + ":" + authInfo.getUrl());
                //response = MD5(MD5(username:realm:password):nonce:nc:cnonce:qop:MD5(<request-method>:url))
                String responseValid = TokenUtils.MD5(HA1 + ":" + HD + ":" + HA2);
                return responseValid.equals(authInfo.getResponse());
            }
        } catch (Exception e) {
            log.error("检查认证信息异常：{}", e.getMessage(), e);
        }
        return false;
    }

    /**
     * 解析认证信息为权限对象
     *
     * @param auth 认证字符串
     * @return 认证对象
     */
    private static AuthInfo getAuthInfo(String auth) {
        AuthInfo authInfo = new AuthInfo();
        if (auth != null) {
            //内容为： Digest username="admin", realm="jrp-auth@example.org", nonce="e9fbd885ad82a440b0879941e67447d3", uri="/", algorithm=MD5, response="b00144c208ac431dfb6deb6b9f0276cb", opaque="zpjmcNA626xb3kJF0v/kPg==", qop=auth, nc=00000002, cnonce="562de37a0569d379"
            for (String kv : auth.split(",")) {
                while (kv.startsWith(" ")) {
                    kv = kv.substring(kv.indexOf(" ") + 1);
                }
                int blankIndex = kv.indexOf(" ");
                if (blankIndex > 0 && kv.indexOf("=") > blankIndex) {
                    kv = kv.substring(blankIndex + 1);
                }
                String key = kv.substring(0, kv.indexOf("=")).trim();
                String value = kv.substring(kv.indexOf("=") + 1).replaceAll("\"", "");
                switch (key) {
                    case "username":
                        authInfo.setUsername(value);
                        break;
                    case "realm":
                        authInfo.setRealm(value);
                        break;
                    case "nonce":
                        authInfo.setNonce(value);
                        break;
                    case "uri":
                        authInfo.setUrl(value);
                        break;
                    case "response":
                        authInfo.setResponse(value);
                        break;
                    case "qop":
                        authInfo.setQop(value);
                        break;
                    case "nc":
                        authInfo.setNc(value);
                        break;
                    case "cnonce":
                        authInfo.setCnonce(value);
                        break;
                }
            }
        }
        return authInfo;
    }

    private static String getNonce(String host) {
        return TokenUtils.MD5(host + TokenUtils.runtimeToken);
    }

    public String getHttpWarnResponse() {
        return "HTTP/1.1 " + HttpResponseStatus.FORBIDDEN + "\r\n" +  //响应头第一行
                "Content-Type: text/html; charset=utf-8\r\n" +  //简单放一个头部信息
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "\r\n" +  //这个空行是来分隔请求头与请求体的
                "<h1>HTTP NOT SUPPORT!</h1>\r\n";
    }

    public String getNotHttpSuccessResponse() {
        return "HTTP/1.1 " + HttpResponseStatus.FORBIDDEN + "\r\n" +  //响应头第一行
                "Content-Type: text/html; charset=utf-8\r\n" +  //简单放一个头部信息
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "\r\n" +  //这个空行是来分隔请求头与请求体的
                "<h1>非HTTP请求用户名密码验证通过!</h1>\r\n";
    }

    public boolean isHTTPRequest(Buffer data) {
        boolean result = false;
        if (data != null) {
            Buffer method = data.length() >= maxMethodLen ? data.getBuffer(0, maxMethodLen) : data;
            result = httpMethods.stream().anyMatch(r -> method.toString().startsWith(r));
        }
        return result;
    }

    /**
     * 判断是否可转为TCP请求
     *
     * @param data 请求信息
     * @return true-可转为TCP请求，false-不可转为TCP请求
     */
    public boolean canToNetSocket(String data) {
        return data.startsWith(HttpMethod.CONNECT.name()) || (data.startsWith(HttpMethod.GET.name()) && data.contains("connection: upgrade"));
    }

    @Override
    public void afterPropertiesSet() {
        whitePattern = Pattern.compile(properties.getWhiteUrl());
    }
}
