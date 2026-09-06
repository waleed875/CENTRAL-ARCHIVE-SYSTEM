package com.centralArchiveSystem.Archive.common.dto;

public class PageRequest {
    private final int page;
    private final int limit;
    private final String sort;
    private final String order;

    public PageRequest(int page, int limit, String sort, String order) {
        this.page = Math.max(1, page);
        this.limit = Math.min(100, Math.max(1, limit));
        this.sort = sort != null ? sort : "createdAt";
        this.order = order != null ? order : "DESC";
    }

    public static PageRequest of(int page, int limit, String sort, String order) {
        return new PageRequest(page, limit, sort, order);
    }

    public int getPage() { return page; }
    public int getLimit() { return limit; }
    public String getSort() { return sort; }
    public String getOrder() { return order; }
}
