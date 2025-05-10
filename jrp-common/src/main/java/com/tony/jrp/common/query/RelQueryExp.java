package com.tony.jrp.common.query;

import java.util.Map;

public class RelQueryExp implements IQueryExp {
    private static final long serialVersionUID = -5690656271650491000L;
    /**
     * @serial The operator
     */
    private int relOp;

    /**
     * @serial The first value
     */
    private IValueExp exp1;

    /**
     * @serial The second value
     */
    private IValueExp exp2;


    /**
     * Basic Constructor.
     */
    public RelQueryExp() {
    }

    public RelQueryExp(int op, IValueExp v1, IValueExp v2) {
        relOp = op;
        exp1 = v1;
        exp2 = v2;
    }

    public int getOperator() {
        return relOp;
    }

    public IValueExp getLeft() {
        return exp1;
    }

    public IValueExp getRight() {
        return exp2;
    }


    @Override
    public String toString() {
        return "(" + exp1 + ") " + relOpString() + " (" + exp2 + ")";
    }

    private String relOpString() {
        switch (relOp) {
            case Query.GT:
                return ">";
            case Query.LT:
                return "<";
            case Query.GE:
                return ">=";
            case Query.LE:
                return "<=";
            case Query.EQ:
                return "=";
        }

        return "=";
    }

    @Override
    public Map<String, ?> paramMap() {
        return null;
    }
}
