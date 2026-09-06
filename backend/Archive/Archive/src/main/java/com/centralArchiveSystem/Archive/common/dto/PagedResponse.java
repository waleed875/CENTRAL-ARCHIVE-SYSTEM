package com.centralArchiveSystem.Archive.common.dto;

import java.util.List;

public class PagedResponse<T> {
    private final List<T> data;
    private final int page;
    private final int limit;
    private final long total;
    private final int totalPages;

    public PagedResponse(List<T> data, int page, int limit, long total, int totalPages) {
        this.data = data;
        this.page = page;
        this.limit = limit;
        this.total = total;
        this.totalPages = totalPages;
    }

    public static <T> PagedResponse<T> of(List<T> data, PageRequest pageRequest, long total) {
        int totalPages = (int) Math.ceil((double) total / pageRequest.getLimit());
        return new PagedResponse<>(data, pageRequest.getPage(), pageRequest.getLimit(), total, totalPages);
    }

    public static <T> PagedResponse<T> fromSpring(org.springframework.data.domain.Page<T> page, int page1Based, int limit) {
        List<T> data = page.getContent();
        return new PagedResponse<>(data, page1Based, limit, page.getTotalElements(), page.getTotalPages());
    }

    public List<T> getData() { return data; }
    public int getPage() { return page; }
    public int getLimit() { return limit; }
    public long getTotal() { return total; }
    public int getTotalPages() { return totalPages; }
}
