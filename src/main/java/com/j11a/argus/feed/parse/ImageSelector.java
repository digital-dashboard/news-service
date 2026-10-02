package com.j11a.argus.feed.parse;

import com.rometools.modules.mediarss.MediaEntryModule;
import com.rometools.modules.mediarss.MediaModule;
import com.rometools.modules.mediarss.types.MediaContent;
import com.rometools.modules.mediarss.types.MediaGroup;
import com.rometools.modules.mediarss.types.Metadata;
import com.rometools.modules.mediarss.types.Thumbnail;
import com.rometools.modules.mediarss.types.UrlReference;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jspecify.annotations.Nullable;

/** Picks one image per entry: media:thumbnail, media:content image, image enclosure, then the first HTML img. */
public final class ImageSelector {

    private static final String IMAGE_MEDIUM = "image";
    private static final String IMAGE_TYPE_PREFIX = "image/";

    private ImageSelector() {
    }

    public static @Nullable String select(SyndEntry entry, URI base) {
        List<String> candidates = new ArrayList<>();
        MediaEntryModule media = entry.getModule(MediaModule.URI) instanceof MediaEntryModule module ? module : null;
        if (media != null) {
            candidates.addAll(thumbnails(media));
            candidates.addAll(imageContents(media));
        }
        candidates.addAll(imageEnclosures(entry));
        return Stream.concat(candidates.stream(), htmlImages(entry))
                .map(raw -> HttpUrls.resolveHttp(base, raw))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static List<String> thumbnails(MediaEntryModule media) {
        List<Metadata> sources = new ArrayList<>();
        sources.add(media.getMetadata());
        for (MediaContent content : allContents(media)) {
            sources.add(content.getMetadata());
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .flatMap(metadata -> Stream.of(metadata.getThumbnail()))
                .map(Thumbnail::getUrl)
                .filter(Objects::nonNull)
                .map(URI::toString)
                .toList();
    }

    private static List<String> imageContents(MediaEntryModule media) {
        return allContents(media).stream()
                .filter(ImageSelector::isImage)
                .map(MediaContent::getReference)
                .filter(UrlReference.class::isInstance)
                .map(reference -> ((UrlReference) reference).getUrl())
                .filter(Objects::nonNull)
                .map(URI::toString)
                .toList();
    }

    private static List<MediaContent> allContents(MediaEntryModule media) {
        List<MediaContent> contents = new ArrayList<>(List.of(media.getMediaContents()));
        for (MediaGroup group : media.getMediaGroups()) {
            contents.addAll(List.of(group.getContents()));
        }
        return contents;
    }

    private static boolean isImage(MediaContent content) {
        String medium = content.getMedium();
        String type = content.getType();
        return IMAGE_MEDIUM.equalsIgnoreCase(medium)
                || (type != null && type.toLowerCase(Locale.ROOT).startsWith(IMAGE_TYPE_PREFIX));
    }

    private static List<String> imageEnclosures(SyndEntry entry) {
        return entry.getEnclosures().stream()
                .filter(enclosure -> enclosure.getType() != null
                        && enclosure.getType().toLowerCase(Locale.ROOT).startsWith(IMAGE_TYPE_PREFIX))
                .map(SyndEnclosure::getUrl)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Stream<String> htmlImages(SyndEntry entry) {
        return Stream.concat(Stream.ofNullable(entry.getDescription()), entry.getContents().stream())
                .map(SyndContent::getValue)
                .filter(Objects::nonNull)
                .flatMap(html -> Jsoup.parseBodyFragment(html).select("img[src]").stream())
                .map((Element img) -> img.attr("src"));
    }
}
