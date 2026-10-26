package com.canteen.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应结构
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    public static <T> PageResult<T> of(IPage<T> p) {
        PageResult<T> r = new PageResult<>();
        r.records = p.getRecords();
        r.total = p.getTotal();
        r.page = p.getCurrent();
        r.size = p.getSize();
        r.pages = p.getPages();
        return r;
    }

    public static <E, T> PageResult<T> of(IPage<E> p, Function<E, T> mapper) {
        PageResult<T> r = new PageResult<>();
        r.records = p.getRecords().stream().map(mapper).toList();
        r.total = p.getTotal();
        r.page = p.getCurrent();
        r.size = p.getSize();
        r.pages = p.getPages();
        return r;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> r = new PageResult<>();
        r.records = records;
        r.total = total;
        r.page = page;
        r.size = size;
        r.pages = size == 0 ? 0 : (total + size - 1) / size;
        return r;
    }
}
