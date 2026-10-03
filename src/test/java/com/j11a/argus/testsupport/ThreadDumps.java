package com.j11a.argus.testsupport;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Lists live threads by name, virtual ones included, which Thread.getAllStackTraces() does not. */
public final class ThreadDumps {

    private static final Pattern NAME = Pattern.compile("\"name\":\\s*\"([^\"]*)\"");

    private ThreadDumps() {
    }

    public static List<String> namesStartingWith(String prefix) {
        try {
            Path dump = Files.createTempFile("thread-dump", ".json");
            Files.delete(dump);
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(dump.toString(), ThreadDumpFormat.JSON);
            try (Stream<String> lines = Files.lines(dump)) {
                return lines.flatMap(line -> {
                    Matcher matcher = NAME.matcher(line);
                    return matcher.find() ? Stream.of(matcher.group(1)) : Stream.empty();
                }).filter(name -> name.startsWith(prefix)).toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
