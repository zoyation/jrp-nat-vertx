package com.tony.jrp.common.query;

import java.util.Collections;
import java.util.Map;

public class BetweenQueryExp implements IQueryExp {
    private static final long serialVersionUID = -2933597532866307444L;

    private IValueExp exp1;

    private IValueExp exp2;

    private IValueExp exp3;

    public BetweenQueryExp() {
    }

    public BetweenQueryExp(IValueExp v1, IValueExp v2, IValueExp v3) {
        exp1 = v1;
        exp2 = v2;
        exp3 = v3;
    }

    public IValueExp getCheckedValue() {
        return exp1;
    }


    public IValueExp getLowerBound() {
        return exp2;
    }

    public IValueExp getUpperBound() {
        return exp3;
    }


    @Override
    public String toString() {
        return "(" + exp1 + ") between (" + exp2 + ") and (" + exp3 + ")";
    }

    @Override
    public Map<String, ?> paramMap() {
        return Collections.emptyMap();
    }
}
