package com.tony.jrp.common.query;
public class AttributeValueExp implements IValueExp {
    private static final long serialVersionUID = -7768025046539163385L;
    private String attr;

    public AttributeValueExp(String attr) {
        this.attr = attr;
    }

    public String getAttributeName() {
        return attr;
    }

    @Override
    public String toString() {
        return attr;
    }
}
