package com.tony.jrp.common.query;


public class NumericValueExp implements IValueExp {
    private Number val = 0.0;
    public NumericValueExp() {
    }

    /** Creates a new NumericValue representing the numeric literal @{code val}.*/
    NumericValueExp(Number val)
    {
        this.val = val;
    }

    /**
     * Returns a double numeric value
     */
    public double doubleValue()  {
        if (val instanceof Long || val instanceof Integer)
        {
            return (double)(val.longValue());
        }
        return val.doubleValue();
    }

    /**
     * Returns a long numeric value
     */
    public long longValue()  {
        if (val instanceof Long || val instanceof Integer)
        {
            return val.longValue();
        }
        return (long)(val.doubleValue());
    }

    /**
     * Returns true is if the numeric value is a long, false otherwise.
     */
    public boolean isLong()  {
        return (val instanceof Long || val instanceof Integer);
    }

    /**
     * Returns the string representing the object
     */
    public String toString()  {
        if (val == null)
            return "null";
        if (val instanceof Long || val instanceof Integer)
        {
            return Long.toString(val.longValue());
        }
        double d = val.doubleValue();
        if (Double.isInfinite(d))
            return (d > 0) ? "(1.0 / 0.0)" : "(-1.0 / 0.0)";
        if (Double.isNaN(d))
            return "(0.0 / 0.0)";
        return Double.toString(d);
    }
}
