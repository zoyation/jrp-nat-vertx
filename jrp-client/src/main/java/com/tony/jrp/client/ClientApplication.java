package com.tony.jrp.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.tony.jrp.client.config.ProxyClientProperties;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.jackson.DatabindCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

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
                // 禁用自带DNS解析器，使用系统DNS
                options.setAddressResolverOptions(null);

                // ========== 高并发优化配置 ==========

                // 1. Event Loop线程数：默认为CPU核心数*2，高并发场景可适当增加
                int eventLoopPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 2, 8);
                options.setEventLoopPoolSize(eventLoopPoolSize);
                log.info("设置Event Loop线程数: {}", eventLoopPoolSize);

                // 2. Worker线程池大小：处理阻塞任务，根据业务复杂度调整
                int workerPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 4, 20);
                options.setWorkerPoolSize(workerPoolSize);
                log.info("设置Worker线程池大小: {}", workerPoolSize);

                // 3. 内部阻塞线程池：用于executeBlocking等操作
                int internalBlockingPoolSize = Math.max(Runtime.getRuntime().availableProcessors() * 2, 10);
                options.setInternalBlockingPoolSize(internalBlockingPoolSize);
                log.info("设置Internal Blocking线程池大小: {}", internalBlockingPoolSize);

                // 4. 最大Event Loop执行时间警告阈值（纳秒），默认2秒
                // 高并发场景下适当增加，避免频繁警告
                options.setMaxEventLoopExecuteTime(5000000000L); // 5秒

                // 5. 最大Worker执行时间警告阈值（纳秒），默认60秒
                options.setMaxWorkerExecuteTime(120000000000L); // 120秒

                // 6. 警告日志是否打印线程堆栈（性能考虑，生产环境可关闭）
                options.setWarningExceptionTime(5000000000L); // 5秒后打印堆栈

                // 7. 集群相关配置（如果启用集群）
                // options.setClustered(true);
                // options.setClusterHost("localhost");
                // options.setClusterPort(0); // 自动选择端口

                // 8. 文件系统配置
                // options.setFileResolverCachingEnabled(true); // 启用文件缓存

                // 9. HA配置（高可用）
                // options.setHAEnabled(true);
                // options.setHAGroup("my-ha-group");
                // options.setQuorumSize(2);

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
    public void start(Promise<Void> startPromise) throws Exception {
        String property = Charset.defaultCharset().displayName();
        log.info("System file.encoding:{}", property);
        System.setProperty("file.encoding", "UTF-8");
        log.info("set file.encoding to UTF-8");
        //禁用自带dns
        System.setProperty("vertx.disableDnsResolver", "true");

        // 任务11: 读取userMode配置，选择启动模式
        log.info("========================================");
        log.info("JRP 客户端启动中...");
        log.info("========================================");

        vertx.executeBlocking(() -> {
             SpringApplication.run(ClientApplication.class, processArgs().toArray(new String[]{}));
            log.info("JRP 客户端启动成功！");
            startPromise.complete();
            return true;
        });
    }
}