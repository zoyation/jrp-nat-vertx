package com.tony.jrp.common.query;


public class StringValueExp implements IValueExp {
    private static final long serialVersionUID = -3256390509806284044L;

    private String val;

    public StringValueExp() {
    }


    public StringValueExp(String val) {
        this.val = val;
    }


    public String getValue() {
        return val;
    }


    public String toString() {
        return "'" + val.replace("'", "''") + "'";
    }

}
