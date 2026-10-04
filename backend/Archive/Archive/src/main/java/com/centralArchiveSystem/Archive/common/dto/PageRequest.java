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

    /**
     * A page request from a route that has historically accepted either name for the page size.
     *
     * <p>Both names are live across this API. The design document and the older controllers
     * ({@code /users}, {@code /roles}, {@code /dashboard}) say {@code limit}; most routes added since
     * say {@code size}. Spring discards a query parameter it does not recognise, so a client that
     * assumes the wrong one gets the default page of 20 and an ordinary 200 — the page is simply
     * smaller than asked for, with nothing to indicate that. That is what a paging control bound to
     * one name does to the three routes that use the other.
     *
     * <p>So both are accepted here, and {@code size} wins when both are present, because it is what
     * the majority of routes read and what the Postman collections and the frontend send. The clamp
     * to 100 already in the constructor is applied either way, so naming both ways cannot be used to
     * ask for an unbounded page.
     */
    public static PageRequest of(int page, Integer size, Integer limit, String sort, String order) {
        int effective = size != null ? size : (limit != null ? limit : 20);
        return new PageRequest(page, effective, sort, order);
    }

    public int getPage() { return page; }
    public int getLimit() { return limit; }
    public String getSort() { return sort; }
    public String getOrder() { return order; }
}
