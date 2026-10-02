package com.j11a.argus.testsupport;

import java.util.ArrayList;
import java.util.List;

/** Helpers shared by the tests that boot the application with deliberately broken configuration. */
public final class StartupTestSupport {

    public static final String KEY_32 = "0123456789abcdef0123456789abcdef";

    private StartupTestSupport() {
    }

    /** Defaults with each "name=value" override replacing the default of the same name; entries get the prefix. */
    public static List<String> withOverrides(List<String> defaults, String prefix, String... overrides) {
        List<String> merged = new ArrayList<>(defaults);
        for (String override : overrides) {
            String name = override.substring(0, override.indexOf('='));
            merged.removeIf(entry -> entry.startsWith(name + "="));
            merged.add(override);
        }
        return merged.stream().map(entry -> prefix + entry).toList();
    }

    public static String causeChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
