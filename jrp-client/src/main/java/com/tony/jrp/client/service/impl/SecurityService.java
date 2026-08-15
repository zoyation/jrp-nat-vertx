package com.tony.jrp.client.service.impl;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Set;
import java.util.StringTokenizer;
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
    /**
     * 代理授权信息
     */
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    /**
     * 代理连接信息
     */
    public static final String PROXY_CONNECTION = "Proxy-Connection";
    /**
     * 所有HTTP方法
     */
    private final Set<String> httpMethods = HttpMethod.values().stream().map(HttpMethod::name).collect(Collectors.toSet());
//    private Set<String> httpMethods = new HashSet<>(Arrays.asList("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE", "CONNECT"));
    /**
     * HTTP方法最大长度
     */
    private final Integer maxMethodLen = httpMethods.stream().map(String::length).max(Comparator.comparing(r -> r)).orElse(4);

    /**
     * 签名信息
     */
    @Getter
    private KeyCertOptions keyCertOptions;

    public String getOKResponse() {
        return "HTTP/1.1 " + HttpResponseStatus.OK + "\r\n" +  //响应头第一行
                "Content-Type: text/html; charset=utf-8\r\n" +  //简单放一个头部信息
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "Connection: close\r\n" +  // 添加连接关闭指令
                "\r\n" +  //这个空行是来分隔请求头与请求体的
                "<h1>OK</h1>\r\n";
    }

    public boolean isHTTPRequest(Buffer data) {
        boolean result = false;
        if (data != null) {
            Buffer method = data.length() >= maxMethodLen ? data.getBuffer(0, maxMethodLen) : data;
            result = httpMethods.stream().anyMatch(r -> method.toString().startsWith(r));
        }
        return result;
    }


    @Override
    public void afterPropertiesSet() {
        try {
            // 使用 Vert.x 的自签名证书工具
            io.vertx.core.net.SelfSignedCertificate selfSignedCert =
                    io.vertx.core.net.SelfSignedCertificate.create();
            keyCertOptions = selfSignedCert.keyCertOptions();
        } catch (Exception e) {
            log.error("Failed to create self-signed certificate", e);
            // 回退到默认的空 PemKeyCertOptions
            keyCertOptions = new PemKeyCertOptions();
        }
    }

    /**
     * 移除Authorization信息
     *
     * @param httpData 请求数据
     * @return 移除Authorization信息后的数据
     */
    public Buffer removeHead(String httpData, String headName) {
        String prefix = headName + ": ";
        //去掉指定head头
        boolean contains = httpData.contains("\r\n" + prefix);
        if (!contains) {
            return Buffer.buffer(httpData);
        }
        StringBuilder builder = new StringBuilder();
        StringTokenizer requestLines = new StringTokenizer(httpData, "\r\n", true);
        while (requestLines.hasMoreTokens()) {
            String requestLine = requestLines.nextToken();
            if (requestLine.startsWith(prefix)) {
                //去掉后面回车换行
                if (requestLines.hasMoreTokens()) {
                    requestLines.nextToken();
                }
                if (requestLines.hasMoreTokens()) {
                    requestLines.nextToken();
                }
                continue;
            }
            builder.append(requestLine);
        }
        log.debug("removeHead: {}", builder);
        return Buffer.buffer(builder.toString());
    }

    /**
     * 移除请求行和请求头里的代理信息
     *
     * @param bufferStr 请求数据
     * @return 移除代理信息后的数据
     */
    public Buffer removeHttpProxy(String bufferStr) {
        StringTokenizer requestLines = new StringTokenizer(bufferStr, "\r\n", true);
        boolean connection = bufferStr.contains("\nConnection: ");
        StringBuilder dataBuilder = new StringBuilder(); // 使用 StringBuilder 替代 StringJoiner 并修正换行符
        while (requestLines.hasMoreTokens()) {
            String requestLine = requestLines.nextToken();
            if (dataBuilder.length() == 0) {
                // 替换第一行的 URL 为相对路径
                requestLine = requestLine.replaceFirst("(https|http)://[^/]+", "");
            }
            if (requestLine.startsWith(PROXY_AUTHORIZATION)) {
                if (requestLines.hasMoreTokens()) {
                    requestLines.nextToken();
                }
                if (requestLines.hasMoreTokens()) {
                    requestLines.nextToken();
                }
                continue;
            }
            if (requestLine.startsWith(PROXY_CONNECTION)) {
                if (!connection) {
                    requestLine = requestLine.replace(PROXY_CONNECTION, "Connection");
                } else {
                    if (requestLines.hasMoreTokens()) {
                        requestLines.nextToken();
                    }
                    if (requestLines.hasMoreTokens()) {
                        requestLines.nextToken();
                    }
                    continue;
                }
            }
            dataBuilder.append(requestLine);
        }
        return Buffer.buffer(dataBuilder.toString());
    }

    /**
     * 获取HTTPS请求成功响应码
     *
     * @return HTTPS请求成功响应码
     */
    public String getHttpsConnectResponse() {
        return "HTTP/1.1 200 Connection Established\r\n" +
                "Proxy-Agent: JRP-Server\r\n" +
                "\r\n";
    }

    /**
     * 添加额外的头信息
     *
     * @param data        请求数据
     * @param headerName  头信息名称
     * @param headerValue 头信息值
     * @return 添加头信息后的数据
     */
    public Buffer addHead(String data, String headerName, String headerValue) {
        // 已存在该头信息则不添加（忽略大小写）
        if (data.toLowerCase().contains(headerName.toLowerCase() + ":")) {
            return Buffer.buffer(data);
        }
        String headerLine = headerName + ": " + headerValue + "\r\n";
        // 在请求头与请求体之间的空行前插入，而非追加到报文最后
        int headerEnd = data.indexOf("\r\n\r\n");
        if (headerEnd != -1) {
            data = data.substring(0, headerEnd) + "\r\n" + headerLine + data.substring(headerEnd + 2);
        } else {
            // 未找到空行（无请求体），追加到末尾
            data = data + headerLine;
        }
        log.debug("addHead: {}", data);
        return Buffer.buffer(data);
    }
}
