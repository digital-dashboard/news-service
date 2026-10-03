package com.j11a.argus.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import org.junit.jupiter.api.Test;

class PageParamsTest {

    @Test
    void maximumPageSizeIsOneHundred() {
        assertThat(PageParams.MAX_PAGE_SIZE).isEqualTo(100);
    }

    @Test
    void anOffsetThatFitsAnIntIsReachable() {
        assertThatCode(() -> PageParams.requireReachable(0, 100)).doesNotThrowAnyException();
        assertThatCode(() -> PageParams.requireReachable(Integer.MAX_VALUE, 1)).doesNotThrowAnyException();
        assertThatCode(() -> PageParams.requireReachable(21_474_836, 100)).doesNotThrowAnyException();
    }

    @Test
    void anOffsetBeyondIntRangeIsRejectedOnThePageField() {
        assertThatThrownBy(() -> PageParams.requireReachable(21_474_837, 100))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties().get("errors").toString()).contains("page");
                });
    }

    @Test
    void theProductIsComputedWithoutIntOverflow() {
        assertThatThrownBy(() -> PageParams.requireReachable(Integer.MAX_VALUE, 100))
                .isInstanceOf(ApiException.class);
    }
}
