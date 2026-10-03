package com.j11a.argus.feed.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class FeedIdentityTelemetryTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FeedIdentityTelemetry telemetry = new FeedIdentityTelemetry(registry);

    @Test
    void bindingRegistersEveryConflictKindAtZero() {
        telemetry.bindTo(registry);

        for (IdentityKind kind : IdentityKind.values()) {
            assertThat(registry.get("argus.feed.identity.conflict").tag("kind", kind.tag()).counter().count())
                    .isZero();
        }
    }

    @Test
    void conflictCountsUnderItsKindTag() {
        telemetry.conflict(IdentityKind.SELF_LINK);
        telemetry.conflict(IdentityKind.SELF_LINK);

        assertThat(registry.get("argus.feed.identity.conflict").tag("kind", "self_link").counter().count())
                .isEqualTo(2);
        assertThat(registry.find("argus.feed.identity.conflict").tag("kind", "entered").counter()).isNull();
    }

    @Test
    void redirectCountsUnderSourceAndOutcomeTags() {
        telemetry.redirect("bbc.co.uk", FeedIdentityTelemetry.PERMANENT_APPLIED);

        assertThat(registry.get("argus.feed.redirect")
                .tags("source", "bbc.co.uk", "outcome", "permanent_applied").counter().count()).isOne();
    }

    @Test
    void kindTagsAreTheDocumentedValues() {
        assertThat(IdentityKind.values()).extracting(IdentityKind::tag)
                .containsExactly("entered", "redirect", "self_link");
    }
}
