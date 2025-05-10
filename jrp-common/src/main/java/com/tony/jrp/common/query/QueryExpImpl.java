package com.tony.jrp.common.query;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class QueryExpImpl implements IQueryExp {
    private IQueryExp exp1;

    private int op;
    private IQueryExp exp2;
    private IQueryExp exp3;


    public QueryExpImpl(int op, IQueryExp exp1, IQueryExp exp2) {
        this.op = op;
        this.exp1 = exp1;
        this.exp2 = exp2;
    }
    public IQueryExp getLeftExp() {
        return exp1;
    }

    public IQueryExp getRightExp() {
        return exp2;
    }

    @Override
    public String toString() {
        if (!Objects.isNull(exp1.toString()) && !Objects.isNull(exp2.toString())) {
            return "(" + exp1 + ") and (" + exp2 + ")";
        }
        String rightStr = Objects.isNull(exp2.toString()) ? "" : exp2.toString();
        return Objects.isNull(exp1.toString()) ? rightStr : exp1.toString();
    }

    @Override
    public Map<String, ?> paramMap() {
        HashMap<String, Object> params = new HashMap<>(exp1.paramMap());
        params.putAll(exp2.paramMap());
        return params;
    }


}
