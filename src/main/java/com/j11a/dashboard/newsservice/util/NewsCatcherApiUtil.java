package com.j11a.dashboard.newsservice.util;

import com.j11a.dashboard.newsservice.model.Topic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@Component
public class NewsCatcherApiUtil {
    @Value("${newscatcher.host}")
    private String host;

    @Value("${newscatcher.pathLatestHeadlines}")
    private String pathLatestHeadlines;

    public URI getLatestHeadlinesUrl(final String period, final String[] countries, final Topic topic, final Integer pageSize, final Integer page) {
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.newInstance()
                .scheme("https")
                .host(this.host)
                .path(this.pathLatestHeadlines)
                .queryParam("lang", "en")
                .queryParam("sources", String.join(",", getDefaultSources()));

        Optional.ofNullable(period).ifPresent(p -> uriComponentsBuilder.queryParam("when", p));
        Optional.ofNullable(countries).ifPresent(c ->uriComponentsBuilder.queryParam("countries", String.join(",", c)));
        Optional.ofNullable(topic).ifPresent(t -> uriComponentsBuilder.queryParam("topic", t.name().toLowerCase()));
        Optional.ofNullable(pageSize).ifPresent(p -> uriComponentsBuilder.queryParam("page_size", p));
        Optional.ofNullable(page).ifPresent(p -> uriComponentsBuilder.queryParam("page", p));

        return uriComponentsBuilder.build().toUri();
    }

    private static String[] getDefaultSources() {
        return new String[] {
                "bnnbloomberg.ca",
                "ctvnews.ca",
                "citynews.ca",
                "theglobeandmail.com",
                "cbc.ca",
                "globalnews.ca",
                "tsn.ca",
                "sportsnet.ca",
                "thescore.com",
                "dailymail.co.uk",
                "bbc.co.uk",
                "bbc.com",
                "independent.co.uk",
                "mirror.co.uk",
                "thesun.co.uk",
                "zambianobserver.com",
                "dailynationzambia.com",
                "lusakatimes.com",
                "zambia24.com",
                "farmersreviewafrica.com",
                "mwebantu.com",
                "zambianbusinesstimes.com",
                "zambianfootball.co.zm",
                "lusakastar.com",
                "rainbownewszambia.com"
        };
    }

}
