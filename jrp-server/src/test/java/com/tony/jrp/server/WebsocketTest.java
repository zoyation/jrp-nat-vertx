package com.tony.jrp.server;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.NetServerOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

public class WebsocketTest {
    @Test
    public void websocket() throws InterruptedException {
        HttpServerOptions serverOptions = new HttpServerOptions();
        serverOptions.setRegisterWebSocketWriteHandlers(true);
        Vertx.vertx().createHttpServer(serverOptions).webSocketHandler(webSocket -> {
            System.out.println("receive textHandlerID:" + webSocket.textHandlerID());
            System.out.println("receive binaryHandlerID:" + webSocket.binaryHandlerID());
            webSocket.handler(buffer -> {
                String textData = buffer.toString();
                System.out.println("textData:" + textData);
            });
            webSocket.closeHandler(handler -> {
                System.out.println("close textHandlerID:" + webSocket.textHandlerID());
            });
        }).listen(80);
        HttpClient httpClient = Vertx.vertx().createHttpClient();
        WebSocketConnectOptions options = new WebSocketConnectOptions();
        options.setHost("127.0.0.1");
        options.setPort(80);
        httpClient.webSocket(options).onSuccess(handler -> {
            handler.write(Buffer.buffer("Hello!"));
        });
        TimeUnit.DAYS.sleep(1);
    }

    @Test
    public void mqttWebsocket() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        HttpServerOptions serverOptions = new HttpServerOptions();
        serverOptions.setReusePort(true);
        serverOptions.addWebSocketSubProtocol("mqtt");
        HttpServer httpServer = vertx.createHttpServer(serverOptions);
        Router router = Router.router(vertx);
        router.route("/ws").handler(ctx -> {
            HttpServerRequest request = ctx.request();
            request.toWebSocket().onSuccess(socket -> {
                WebSocketConnectOptions options = new WebSocketConnectOptions();
                options.setHost("127.0.0.1");
                options.setPort(15675);
                options.setURI("/ws");
                String subProtocol = socket.headers().get("Sec-WebSocket-Protocol");
                MultiMap headers = socket.headers();
                options.setHeaders(headers);
                if (subProtocol != null) {
                    options.addSubProtocol(subProtocol);
                }
                socket.pause();
                vertx.createWebSocketClient().connect(options).onSuccess(target -> {
                    // 设置双向管道，不使用 pause/resume，让 pipeTo 自动处理背压
                    socket.pipeTo(target);
                    target.pipeTo(socket);
                    // 添加关闭处理器，确保一方关闭时另一方也关闭
                    socket.closeHandler(v -> {
                        System.out.println("Socket closed, closing target");
                        target.close();
                    });

                    target.closeHandler(v -> {
                        System.out.println("Target closed, closing socket");
                        socket.close();
                    });

                    // 添加异常处理
                    socket.exceptionHandler(err -> {
                        System.err.println("Socket exception: " + err.getMessage());
                        target.close();
                    });

                    target.exceptionHandler(err -> {
                        System.err.println("Target exception: " + err.getMessage());
                        socket.close();
                    });
                    socket.resume();
                }).onFailure(err -> {
                    System.err.println("Connect to MQTT broker failed: " + err.getMessage());
                    err.printStackTrace();
                    socket.close();
                });
            });
        });
        httpServer.requestHandler(router);
        httpServer.webSocketHandler(socket -> {
            System.out.println(socket.authority().host());
            WebSocketConnectOptions options = new WebSocketConnectOptions();
            options.setHost("127.0.0.1");
            options.setPort(15675);
            options.setURI("/ws");
            String subProtocol = socket.headers().get("Sec-WebSocket-Protocol");
            MultiMap headers = socket.headers();
            options.setHeaders(headers);
            if (subProtocol != null) {
                options.addSubProtocol(subProtocol);
            }
            socket.pause();
            vertx.createWebSocketClient().connect(options).onSuccess(target -> {
                // 设置双向管道，不使用 pause/resume，让 pipeTo 自动处理背压
                socket.binaryMessageHandler(target::writeBinaryMessage);
                target.binaryMessageHandler(socket::writeBinaryMessage);
                // 添加关闭处理器，确保一方关闭时另一方也关闭
                socket.closeHandler(v -> {
                    System.out.println("Socket closed, closing target");
                    target.close();
                });

                target.closeHandler(v -> {
                    System.out.println("Target closed, closing socket");
                    socket.close();
                });

                // 添加异常处理
                socket.exceptionHandler(err -> {
                    System.err.println("Socket exception: " + err.getMessage());
                    target.close();
                });

                target.exceptionHandler(err -> {
                    System.err.println("Target exception: " + err.getMessage());
                    socket.close();
                });
                socket.resume();
            }).onFailure(err -> {
                System.err.println("Connect to MQTT broker failed: " + err.getMessage());
                err.printStackTrace();
                socket.close();
            });
        });
        httpServer.listen(1884);
        NetServerOptions options = new NetServerOptions();
        options.setReusePort(true);
        vertx.createNetServer(options).connectHandler(socket -> {
            System.out.println(socket.localAddress().host());
        }).listen(1884);
        TimeUnit.DAYS.sleep(1);
    }

}