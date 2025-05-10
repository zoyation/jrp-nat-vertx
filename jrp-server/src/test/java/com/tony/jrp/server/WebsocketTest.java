package com.tony.jrp.server;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocketConnectOptions;
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
}
