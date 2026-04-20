package com.gazsik.lookupinvideo.domain.model;

import java.util.ArrayList;
import java.util.List;

public final class ColorQuery {

    private final boolean wantsRed;
    private final boolean wantsGreen;
    private final boolean wantsBlue;

    private ColorQuery(boolean wantsRed, boolean wantsGreen, boolean wantsBlue) {
        this.wantsRed = wantsRed;
        this.wantsGreen = wantsGreen;
        this.wantsBlue = wantsBlue;
    }

    public static ColorQuery fromNormalizedQuery(String normalizedQuery) {
        boolean red = normalizedQuery.contains("piros") || normalizedQuery.contains("red");
        boolean green = normalizedQuery.contains("zold") || normalizedQuery.contains("green");
        boolean blue = normalizedQuery.contains("kek") || normalizedQuery.contains("blue");

        if (!red && !green && !blue) {
            red = true;
        }

        return new ColorQuery(red, green, blue);
    }

    public static ColorQuery none() {
        return new ColorQuery(false, false, false);
    }

    public boolean wantsRed() {
        return wantsRed;
    }

    public boolean wantsGreen() {
        return wantsGreen;
    }

    public boolean wantsBlue() {
        return wantsBlue;
    }

    public String displayName() {
        List<String> names = new ArrayList<>();
        if (wantsRed) {
            names.add("piros");
        }
        if (wantsGreen) {
            names.add("zold");
        }
        if (wantsBlue) {
            names.add("kek");
        }
        return names.isEmpty() ? "n/a" : String.join("/", names);
    }
}
