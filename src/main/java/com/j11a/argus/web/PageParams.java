package com.j11a.argus.web;

import com.j11a.argus.web.error.ApiException;

/**
 * Paging limits shared by the list endpoints. Spring Data's Pageable resolver would clamp an oversized size instead of
 * rejecting it, so the controllers take plain page and size parameters and validate them here.
 */
public final class PageParams {

    public static final int MAX_PAGE_SIZE = 100;
    private static final String PAGE_TOO_DEEP = "page is too large for this size";

    private PageParams() {
    }

    /** The row offset (page * size) must fit the int that the queries take. */
    public static void requireReachable(int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("page", PAGE_TOO_DEEP);
        }
    }
}
