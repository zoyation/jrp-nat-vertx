package com.tony.jrp.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.jackson.DatabindCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@SpringBootApplication
@Slf4j
public class ClientApplication extends AbstractVerticle {
    public static final String RUN = "run";
    public static final String START = "start";

    public static void main(String[] args) {
        List<String> list = getVertxArgs(args, ClientApplication.class.getName());
        DatabindCodec.mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        new Launcher() {
            @Override
            public void beforeStartingVertx(VertxOptions options) {
                options.setWorkerPoolSize(100);
                options.setEventLoopPoolSize(200);
                super.beforeStartingVertx(options);
            }
        }.dispatch(list.toArray(new String[]{}));
    }

    /**
     * vertx支持参数适配处理
     *
     * @param args         原参数
     * @param mainVerticle vertx 启动verticle
     * @return 增加vertx
     */
    private static List<String> getVertxArgs(String[] args, String mainVerticle) {
        List<String> list = new LinkedList<>(Arrays.asList(args));
        //jar包方式启动
        if (list.isEmpty()) {
            list.add(RUN);
            list.add(mainVerticle);
        } else if (args.length == 1 && (args[0].equals(START) || args[0].equals(RUN))) {
            list.add(mainVerticle);
        } else if (args.length > 1 && list.stream().anyMatch(r -> r.startsWith("-Dvertx.id="))) {
            list.add(0, RUN);
            list.add(1, mainVerticle);
        }
        return list;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        String property = Charset.defaultCharset().displayName();
        log.info("System file.encoding:{}", property);
        System.setProperty("file.encoding", "UTF-8");
        log.info("set file.encoding to UTF-8");
        vertx.executeBlocking(() -> {
            SpringApplication.run(ClientApplication.class, processArgs().toArray(new String[]{}));
            startPromise.complete();
            return true;
        });
    }
}