package com.tony.jrp.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.json.jackson.DatabindCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@Slf4j
@SpringBootApplication
public class ServerApplication extends AbstractVerticle {
    public static final String RUN = "run";
    public static final String START = "start";

    public static void main(String[] args) {
        List<String> list = getVertxArgs(args, ServerApplication.class.getName());
        DatabindCodec.mapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        new Launcher() {
            @Override
            public void beforeStartingVertx(io.vertx.core.VertxOptions options) {
                // 禁用自带DNS解析器，使用系统DNS
                options.setAddressResolverOptions(null);
                
                // ========== 高并发优化配置 ==========
                
                // 1. Event Loop线程数：服务端需要处理更多连接，适当增加
                int eventLoopPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 2, 8);
                options.setEventLoopPoolSize(eventLoopPoolSize);
                log.info("设置Event Loop线程数: {}", eventLoopPoolSize);
                
                // 2. Worker线程池大小：服务端阻塞任务较多，需要更大线程池
                int workerPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 4, 20);
                options.setWorkerPoolSize(workerPoolSize);
                log.info("设置Worker线程池大小: {}", workerPoolSize);
                
                // 3. 内部阻塞线程池
                int internalBlockingPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 2, 10);
                options.setInternalBlockingPoolSize(internalBlockingPoolSize);
                log.info("设置Internal Blocking线程池大小: {}", internalBlockingPoolSize);
                
                // 4. 最大Event Loop执行时间警告阈值（纳秒）
                options.setMaxEventLoopExecuteTime(5000000000L); // 5秒
                
                // 5. 最大Worker执行时间警告阈值（纳秒）
                options.setMaxWorkerExecuteTime(120000000000L); // 120秒
                
                // 6. 警告日志堆栈打印时间
                options.setWarningExceptionTime(5000000000L); // 5秒后打印堆栈
                
                super.beforeStartingVertx(options);
            }
        }.dispatch(list.toArray(new String[0]));
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
    public void start(Promise<Void> startPromise) {
        String property = Charset.defaultCharset().displayName();
        log.info("System file.encoding:{}", property);
        System.setProperty("file.encoding", "UTF-8");
        log.info("set file.encoding to UTF-8");
        vertx.executeBlocking(() -> {
            SpringApplication.run(ServerApplication.class, processArgs().toArray(new String[]{}));
            startPromise.complete();
            return true;
        });
    }
}