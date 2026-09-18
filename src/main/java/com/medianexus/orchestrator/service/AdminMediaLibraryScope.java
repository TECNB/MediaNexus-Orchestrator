package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.util.StringUtils;

public enum AdminMediaLibraryScope {
    MOVIES("movies", "Movies", "Movie"),
    TV("tv", "TV", "Series"),
    VARIETY("variety", "综艺", "Series"),
    ANIME("anime", "Anime", "Series"),
    ADULT_OTHER("adult-other", "Adult - Other", "Movie"),
    ADULT_JAV("adult-jav", "Adult-JAV", "Movie");

    private final String requestValue;
    private final String embyName;
    private final String itemType;

    AdminMediaLibraryScope(String requestValue, String embyName, String itemType) {
        this.requestValue = requestValue;
        this.embyName = embyName;
        this.itemType = itemType;
    }

    public String requestValue() {
        return requestValue;
    }

    public String embyName() {
        return embyName;
    }

    public String itemType() {
        return itemType;
    }

    public String listingItemType() {
        return this == ADULT_OTHER ? "BoxSet" : itemType;
    }

    public boolean episodic() {
        return "Series".equals(itemType);
    }

    public static AdminMediaLibraryScope fromRequest(String library) {
        String normalized = StringUtils.hasText(library)
                ? library.trim().toLowerCase(Locale.ROOT)
                : "";
        return Arrays.stream(values())
                .filter(candidate -> candidate.requestValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "媒体库只能是 movies、tv、variety、anime、adult-other 或 adult-jav"
                ));
    }
}
