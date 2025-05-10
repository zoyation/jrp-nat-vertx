package com.tony.jrp.common.query;

import java.util.Collections;
import java.util.Map;

public class NullQueryExp implements IQueryExp {
    private static final long serialVersionUID = -5690656271650491000L;

    /**
     * Basic Constructor.
     */
    public NullQueryExp() {
    }

    /**
     * Returns the string representing the object.
     */
    @Override
    public String toString() {
        return null;
    }


    @Override
    public Map<String, ?> paramMap() {
        return Collections.emptyMap();
    }

}
