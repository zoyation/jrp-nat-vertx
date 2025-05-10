package com.tony.jrp.client.service;

import com.tony.jrp.common.query.IQueryExp;

import java.util.List;
import java.util.Set;

public interface IBaseService<T> {
    String add(T data);

    int delete(Set<String> ids);

    int update(T data);

    T detail(String id);

    List<T> list(IQueryExp queryExp);
}
