package com.tony.jrp.common.query;
public class Query {
    public static final int GT = 0;

    public static final int LT = 1;

    public static final int GE = 2;

    public static final int LE = 3;
    public static final int EQ = 4;
    public static final IQueryExp nullQueryExp = new NullQueryExp();

    public static IQueryExp and(IQueryExp left, IQueryExp right) {
        return new AndQueryExp(left, right);
    }
    public static IQueryExp or(IQueryExp q1, IQueryExp q2)  {
        return new OrQueryExp(q1, q2);
    }
    public static IQueryExp match(AttributeValueExp a, StringValueExp s)  {
        return new MatchQueryExp(a, s);
    }
    public static IQueryExp between(IValueExp v1, IValueExp v2, IValueExp v3) {
        return new BetweenQueryExp(v1, v2, v3);
    }
    public static IQueryExp gt(IValueExp v1, IValueExp v2) {
        return new RelQueryExp(GT, v1, v2);
    }

    public static IQueryExp geq(IValueExp v1, IValueExp v2) {
        return new RelQueryExp(GE, v1, v2);
    }

    public static IQueryExp leq(IValueExp v1, IValueExp v2) {
        return new RelQueryExp(LE, v1, v2);
    }


    public static IQueryExp lt(IValueExp v1, IValueExp v2) {
        return new RelQueryExp(LT, v1, v2);
    }


    public static IQueryExp eq(IValueExp v1, IValueExp v2) {
        return new RelQueryExp(EQ, v1, v2);
    }

    public static IQueryExp gt(boolean condition, IValueExp v1, IValueExp v2) {
        if (!condition) {
            return nullQueryExp;
        }
        return new RelQueryExp(GT, v1, v2);
    }

    public static IQueryExp geq(boolean condition, IValueExp v1, IValueExp v2) {
        if (!condition) {
            return nullQueryExp;
        }
        return new RelQueryExp(GE, v1, v2);
    }

    public static IQueryExp leq(boolean condition, IValueExp v1, IValueExp v2) {
        if (!condition) {
            return nullQueryExp;
        }
        return new RelQueryExp(LE, v1, v2);
    }

    public static IQueryExp lt(boolean condition, IValueExp v1, IValueExp v2) {
        if (!condition) {
            return nullQueryExp;
        }
        return new RelQueryExp(LT, v1, v2);
    }

    public static IQueryExp eq(boolean condition, IValueExp v1, IValueExp v2) {
        if (!condition) {
            return nullQueryExp;
        }
        return new RelQueryExp(EQ, v1, v2);
    }

    public static AttributeValueExp attr(String name)  {
        return new AttributeValueExp(name);
    }

    public static StringValueExp value(String val)  {
        return new StringValueExp(val);
    }

    public static IValueExp value(Number val)  {
        return new NumericValueExp(val);
    }


    public static IValueExp value(int val)  {
        return new NumericValueExp((long) val);
    }


    public static IValueExp value(long val)  {
        return new NumericValueExp(val);
    }


    public static IValueExp value(float val)  {
        return new NumericValueExp((double) val);
    }


    public static IValueExp value(double val)  {
        return new NumericValueExp(val);
    }


    public static IValueExp value(boolean val)  {
        return new BooleanValueExp(val);
    }
}
