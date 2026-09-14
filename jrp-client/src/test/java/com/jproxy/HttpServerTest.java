package com.jproxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

public class HttpServerTest {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpServer httpServer = vertx.createHttpServer();
        Router router = Router.router(vertx);
        router.route(HttpMethod.GET, "/test").handler(routingContext -> {
            routingContext.response()
                    .putHeader("Content-Type", "text/html")
                    .end("<h1>Hello, World!</h1>");
        });
        httpServer.requestHandler(router).listen(8081);
    }
}
