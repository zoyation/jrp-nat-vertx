package com.tony.jrp.common.query;

import java.util.Map;

public class MatchQueryExp implements IQueryExp {

    /* Serial version */
    private static final long serialVersionUID = -7156603696948215014L;

    /**
     * @serial The attribute value to be matched
     */
    private AttributeValueExp exp;

    /**
     * @serial The pattern to be matched
     */
    private String pattern;


    /**
     * Basic Constructor.
     */
    public MatchQueryExp() {
    }

    /**
     * Creates a new MatchQueryExp where the specified AttributeValueExp matches
     * the specified pattern StringValueExp.
     */
    public MatchQueryExp(AttributeValueExp a, StringValueExp s) {
        exp = a;
        pattern = s.getValue();
    }


    /**
     * Returns the attribute of the query.
     */
    public AttributeValueExp getAttribute() {
        return exp;
    }

    /**
     * Returns the pattern of the query.
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Returns the string representing the object
     */
    public String toString() {
        return exp + " like " + new StringValueExp(pattern);
    }

    @Override
    public Map<String, ?> paramMap() {
        return null;
    }
}
