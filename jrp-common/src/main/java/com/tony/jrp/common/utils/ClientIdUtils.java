package com.tony.jrp.common.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

public class ClientIdUtils {
    private static final String FILE_PATH =
            System.getProperty("user.home") + File.separator + ".jrp-client" + File.separator + "client.id";
    private static volatile String cached;

    public static String getClientId() {
        if (cached != null) return cached;
        synchronized (ClientIdUtils.class) {
            if (cached != null) return cached;
            File f = new File(FILE_PATH);
            if (f.exists()) {
                try {
                    String id = Files.readAllLines(f.toPath()).stream()
                            .findFirst().map(String::trim).orElse("");
                    if (!id.isEmpty()) { cached = id; return id; }
                } catch (IOException ignored) { }
            }
            String uuid = UUID.randomUUID().toString();
            try {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), uuid.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
            cached = uuid;
            return uuid;
        }
    }
}
