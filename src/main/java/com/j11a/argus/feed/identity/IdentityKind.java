package com.j11a.argus.feed.identity;

public enum IdentityKind {
    ENTERED("entered"),
    REDIRECT("redirect"),
    SELF_LINK("self_link");

    private final String tag;

    IdentityKind(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
