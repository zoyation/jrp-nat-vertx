package com.tony.jrp.server.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Random;

public class TokenUtils {
    public static String runtimeToken = generateToken();
    private TokenUtils() {
    }
    public static String generateToken() {
        String s = String.valueOf(System.currentTimeMillis() + new Random().nextInt());

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("md5");
            byte[] digest = messageDigest.digest(s.getBytes());

            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException();
        }
    }

    public static String MD5(String inStr) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            return "";
        }

        char[] charArray = inStr.toCharArray();
        byte[] byteArray = new byte[charArray.length];

        for (int i = 0; i < charArray.length; i++) {
            byteArray[i] = (byte) charArray[i];
        }

        byte[] md5Bytes = md5.digest(byteArray);
        StringBuilder hexValue = new StringBuilder();

        for (byte md5Byte : md5Bytes) {
            int val = ((int) md5Byte) & 0xff;
            if (val < 16)
                hexValue.append("0");
            hexValue.append(Integer.toHexString(val));
        }

        return hexValue.toString();
    }
}
