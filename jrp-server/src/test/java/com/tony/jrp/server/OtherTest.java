package com.tony.jrp.server;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

public class OtherTest {
    @Test
    public void testBuffer() throws InterruptedException {
        System.out.println(Buffer.buffer("JR得P0").length());
    }
    private String whiteUrl="^(/apix/)(\\S+)*$";

    @Test
    public void testUriMatch(){
        System.out.println(Pattern.compile(whiteUrl).matcher("/api/").matches());
        System.out.println(Pattern.compile(whiteUrl).matcher("/apix/123").matches());
    }
}
