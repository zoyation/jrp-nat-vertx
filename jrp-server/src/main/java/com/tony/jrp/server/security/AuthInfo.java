package com.tony.jrp.server.security;

import lombok.Data;

@Data
public class AuthInfo {
    public String username;
    public String realm;
    public String nonce;
    public String url;
    public String response;
    public String qop;
    public String nc;
    public String cnonce;
}
