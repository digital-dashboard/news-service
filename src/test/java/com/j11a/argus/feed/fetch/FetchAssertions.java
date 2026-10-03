package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.fetch.FetchResult.Failed;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;

final class FetchAssertions {

    private FetchAssertions() {
    }

    /** The failure has this reason and status, and always says why. Returns it for further checks. */
    static Failed assertFailed(FetchResult result, FetchFailureReason reason, @Nullable Integer status) {
        return assertThat(result)
                .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
                .returns(reason, Failed::reason)
                .returns(status, Failed::httpStatus)
                .satisfies(failed -> assertThat(failed.error()).isNotNull())
                .actual();
    }
}
