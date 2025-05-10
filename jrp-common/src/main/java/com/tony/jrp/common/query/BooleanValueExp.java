package com.tony.jrp.common.query;

public class BooleanValueExp implements IValueExp{
    private boolean val = false;

    /** Creates a new BooleanValueExp representing the boolean literal {@code val}.*/
    BooleanValueExp(boolean val) {
        this.val = val;
    }

    /**Creates a new BooleanValueExp representing the Boolean object {@code val}.*/
    BooleanValueExp(Boolean val) {
        this.val = val.booleanValue();
    }


    /** Returns the  Boolean object representing the value of the BooleanValueExp object.*/
    public Boolean getValue()  {
        return Boolean.valueOf(val);
    }

    /**
     * Returns the string representing the object.
     */
    public String toString()  {
        return String.valueOf(val);
    }
}
