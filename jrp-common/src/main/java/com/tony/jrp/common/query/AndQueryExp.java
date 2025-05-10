package com.tony.jrp.common.query;

import java.util.Map;

public class AndQueryExp  implements IQueryExp{

    /* Serial version */
    private static final long serialVersionUID = -1081892073854801359L;

    private IQueryExp exp1;

    private IQueryExp exp2;

    public AndQueryExp() {
    }

    public AndQueryExp(IQueryExp q1, IQueryExp q2) {
        exp1 = q1;
        exp2 = q2;
    }

    public IQueryExp getLeftExp()  {
        return exp1;
    }

    public IQueryExp getRightExp()  {
        return exp2;
    }


    @Override
    public String toString() {
        return "(" + exp1 + ") and (" + exp2 + ")";
    }

    @Override
    public Map<String, ?> paramMap() {
        return null;
    }
}
