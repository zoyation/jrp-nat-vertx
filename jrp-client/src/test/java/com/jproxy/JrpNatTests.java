package com.jproxy;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.Router;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

//@SpringBootTest(classes = ProxyApplication.class)
@Slf4j
class JrpNatTests {
    @Test
    void contextLoads() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        Router router = Router.router(vertx);

        // 使用正则表达式匹配所有以"/api/"开头的路径
        router.routeWithRegex("^(/api/)(uas|log)/.*").handler(rct -> {
            System.out.println(rct.request().path());
            System.out.println(rct.request().uri());
            rct.response().end();
        });

        // 部署HTTP服务器
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(8080);
//        vertx.createHttpServer().requestHandler(handler -> handler.response().setStatusCode(302).putHeader("location","/test").end("Hello world!")).listen(888);
        Thread.sleep(Long.MAX_VALUE);

    }


    @Test
    void testTCPProxy() throws InterruptedException {
        NetServer netServer = Vertx.vertx().createNetServer();
        NetClient netClient = Vertx.vertx().createNetClient();
        netServer.connectHandler(readStream -> {
            log.info("客户端 {}:{} 创建连接", readStream.remoteAddress().host(), readStream.remoteAddress().port());
            netClient.connect(22, "192.168.1.23", result -> {
                if (result.succeeded()) {
                    NetSocket writeStream = result.result();
                    log.info("代理连接成功");

                    Pump.pump(readStream, writeStream).start();
                    Pump.pump(writeStream, readStream).start();
                    writeStream.closeHandler(event -> log.info("代理连接关闭"));
                } else {
                    log.error("代理连接失败");
                }
                readStream.closeHandler(event -> log.info("客户端 {}:{} 断开连接", readStream.remoteAddress().host(), readStream.remoteAddress().port()));
            });
        });
        log.info("开始监听 3000");
        netServer.listen(3000);
        Thread.sleep(Long.MAX_VALUE);
    }

    @Test
    void testUDPProxy() throws InterruptedException {
        Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(200).setEventLoopPoolSize(1000));
        DatagramSocket originServer = vertx.createDatagramSocket();
        originServer.handler(handler -> {
            Buffer data = handler.data();
            System.out.println("client data：" + data);
            String backMessage = "Hello world " + data + "!";
            System.out.println("server return：" + backMessage);
            originServer.send(backMessage, handler.sender().port(), handler.sender().host());
        });
        int total = 10000;
        CountDownLatch countDownLatch = new CountDownLatch(total);
        originServer.listen(844, "127.0.0.1").onSuccess((r) -> {
            ExecutorService executorService = Executors.newFixedThreadPool(100);
            for (int i = 0; i < total; i++) {
                final String iStr = String.valueOf(i);
                executorService.execute(() -> {
                    DatagramSocket client = vertx.createDatagramSocket(new DatagramSocketOptions().setReusePort(true));
                    client.handler(handler -> {
                        System.out.println("Client [" + client.localAddress().toString() + "-" + iStr + "] receive data：" + handler.data());
                        countDownLatch.countDown();
                    });
                    System.out.println("send data：" + iStr);
                    client.send(iStr, 1844, "127.0.0.1");
                });
            }
        });
        countDownLatch.await(600, TimeUnit.SECONDS);
        System.out.println("all done");
    }

    @Test
    void testSocketProxy() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        HttpServer originServer1 = vertx.createHttpServer();
        originServer1.webSocketHandler(handler -> {
            handler.handler(receiveData -> {
                System.out.println("originServer1 receive data:" + receiveData);
            });
            handler.write(Buffer.buffer("I'm server 1!"));
        }).listen(851, "127.0.0.1");
        HttpServer originServer2 = vertx.createHttpServer();
        originServer2.webSocketHandler(handler -> {
            handler.handler(receiveData -> {
                System.out.println("originServer2 receive data:" + receiveData);
            });
            handler.write(Buffer.buffer("I'm server 2!"));
        }).listen(852, "127.0.0.1");

        ExecutorService executorService = Executors.newFixedThreadPool(50);
        for (int i = 0; i < 1000; i++) {
            final String iStr = String.valueOf(i);
            executorService.execute(() -> {
                HttpClient client = vertx.createHttpClient();
                client.webSocket(85, "127.0.0.1", "/").onSuccess(clientSocket -> {
                    clientSocket.write(Buffer.buffer(iStr));
                    clientSocket.handler(backMsg -> {
                        System.out.println("client " + iStr + " receive message:" + backMsg);
                    });
                });
            });
        }
        Thread.sleep(Long.MAX_VALUE);
    }
}