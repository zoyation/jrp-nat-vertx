package com.tony.jrp.common.query;

import java.util.Map;

public class OrQueryExp implements IQueryExp {

    /* Serial version */
    private static final long serialVersionUID = 2962973084421716523L;

    private IQueryExp exp1;


    private IQueryExp exp2;

    public OrQueryExp() {
    }

    public OrQueryExp(IQueryExp q1, IQueryExp q2) {
        exp1 = q1;
        exp2 = q2;
    }

    public IQueryExp getLeftExp() {
        return exp1;
    }


    public IQueryExp getRightExp() {
        return exp2;
    }

    @Override
    public String toString() {
        return "(" + exp1 + ") or (" + exp2 + ")";
    }

    @Override
    public Map<String, ?> paramMap() {
        return null;
    }
}
