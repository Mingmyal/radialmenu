package com.mingmyal.radialmenu.ui.screen.iconpicker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class IconSearch {

    private static final int EXACT = 0;
    private static final int PREFIX = 1;
    private static final int WORD_START = 2;
    private static final int NAME_CONTAINS = 3;
    private static final int OTHER_CONTAINS = 4;

    private IconSearch() {}

    /**
     * Filters and orders icons for a query: exact name matches first, then name prefixes, word starts inside the
     * name, anywhere in the name, and finally matches only in the wider search text (ids, tags). Ties keep their
     * original order.
     *
     * @param query      the lowercase query
     * @param name       lowercase display name of an icon
     * @param searchText lowercase text that also counts as a match, e.g. ids and tags
     */
    public static <T> List<T> rank(
            List<T> items, String query, Function<T, String> name, Function<T, String> searchText) {
        if (query.isEmpty()) {
            return items;
        }

        List<List<T>> buckets = new ArrayList<>(5);
        for (int i = 0; i <= OTHER_CONTAINS; i++) {
            buckets.add(new ArrayList<>());
        }

        for (T item : items) {
            int score = score(name.apply(item), searchText.apply(item), query);
            if (score >= 0) {
                buckets.get(score).add(item);
            }
        }

        List<T> results = new ArrayList<>();
        buckets.forEach(results::addAll);
        return results;
    }

    private static int score(String name, String searchText, String query) {
        int index = name.indexOf(query);

        if (index >= 0) {
            if (index == 0) {
                return name.length() == query.length() ? EXACT : PREFIX;
            }

            for (; index > 0; index = name.indexOf(query, index + 1)) {
                if (!Character.isLetterOrDigit(name.charAt(index - 1))) {
                    return WORD_START;
                }
            }

            return NAME_CONTAINS;
        }

        return searchText.contains(query) ? OTHER_CONTAINS : -1;
    }
}
