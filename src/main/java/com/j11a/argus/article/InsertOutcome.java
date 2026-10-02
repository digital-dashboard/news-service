package com.j11a.argus.article;

public enum InsertOutcome {
    INSERTED,
    /** The existing row is left as it is; upstream edits are ignored until phase 4. */
    UNCHANGED
}
