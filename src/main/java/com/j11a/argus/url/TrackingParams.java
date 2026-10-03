package com.j11a.argus.url;

import java.util.Set;

public final class TrackingParams {

    private static final Set<String> EXACT = Set.of("fbclid", "gclid", "mc_cid", "mc_eid", "cmpid", "ref");
    private static final String UTM_PREFIX = "utm_";
    private static final String AT_PREFIX = "at_";

    private TrackingParams() {
    }

    public static boolean isTracking(String lowercasedName) {
        if (lowercasedName == null || lowercasedName.isEmpty()) {
            return false;
        }
        return EXACT.contains(lowercasedName)
                || lowercasedName.startsWith(UTM_PREFIX)
                || lowercasedName.startsWith(AT_PREFIX);
    }
}
