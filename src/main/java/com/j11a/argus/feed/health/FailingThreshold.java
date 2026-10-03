package com.j11a.argus.feed.health;

/** The consecutive failures at which a feed counts as failing; a bean so that no package needs the poll settings. */
public record FailingThreshold(int value) {
}
