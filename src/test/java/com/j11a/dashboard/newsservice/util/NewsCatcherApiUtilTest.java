package com.j11a.dashboard.newsservice.util;

import com.j11a.dashboard.newsservice.model.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
class NewsCatcherApiUtilTest {

    @InjectMocks
    private NewsCatcherApiUtil newsCatcherApiUtil;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(newsCatcherApiUtil, "host", "localhost");
        ReflectionTestUtils.setField(newsCatcherApiUtil, "pathLatestHeadlines", "headlines");

    }

    @Test
    void testGetLatestHeadlines_AllParamsNull() {
        final URI url = newsCatcherApiUtil.getLatestHeadlinesUrl(null, null, null, null, null);

        assertNotNull(url);
        assertEquals("localhost", url.getHost());
        assertEquals("https", url.getScheme());
        assertEquals("/headlines", url.getPath());
        assertEquals("lang=en", url.getQuery());
    }

    @Test
    void testGetLatestHeadlines_AllParams() {
        final URI url = newsCatcherApiUtil.getLatestHeadlinesUrl("7d", new String[]{"CA", "ZM"}, Topic.ECONOMICS, 23, 2);

        assertNotNull(url);
        assertEquals("localhost", url.getHost());
        assertEquals("https", url.getScheme());
        assertEquals("/headlines", url.getPath());
        assertEquals("lang=en&when=7d&countries=CA,ZM&topic=economics&page_size=23&page=2", url.getQuery());
    }
}