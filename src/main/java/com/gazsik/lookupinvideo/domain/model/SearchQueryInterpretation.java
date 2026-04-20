package com.gazsik.lookupinvideo.domain.model;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;

public final class SearchQueryInterpretation {

    private final String originalQuery;
    private final String normalizedQuery;
    private final QueryIntent intent;
    private final ColorQuery colorQuery;

    public SearchQueryInterpretation(String originalQuery,
                                     String normalizedQuery,
                                     QueryIntent intent,
                                     ColorQuery colorQuery) {
        this.originalQuery = originalQuery;
        this.normalizedQuery = normalizedQuery;
        this.intent = intent;
        this.colorQuery = colorQuery;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public QueryIntent getIntent() {
        return intent;
    }

    public ColorQuery getColorQuery() {
        return colorQuery;
    }

    public boolean isWildlifeIntent() {
        return intent == QueryIntent.WILDLIFE;
    }
}
