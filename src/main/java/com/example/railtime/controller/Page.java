package com.example.railtime.controller;

import java.util.Arrays;
import java.util.Optional;

/**
 * The pages of the web app. Each has its own URL, so direct links, refresh and back/forward work;
 * all are rendered by the same template (app.js then switches between them without reloading).
 * The {@code kind} is the calculator colour identity used for the active state (null = neutral).
 */
public enum Page {

    DASHBOARD("dashboard", "/", "main", null, "grid"),
    GENERAL("general", "/general", "calculators", "general", "calendar"),
    TATKAL("tatkal", "/tatkal", "calculators", "tatkal", "zap"),
    REFUND("refund", "/refund", "calculators", "refund", "receipt"),
    CHART("chart", "/chart", "calculators", "chart", "clipboard"),
    STATUS("status", "/status", "tools", null, "clock"),
    REMINDERS("reminders", "/reminders", "tools", null, "bell"),
    RULES("rules", "/rules", "tools", null, "info");

    /** Every page path, for the security allow-list, ETag filter and language switching. */
    public static final String[] PATHS = Arrays.stream(values()).map(Page::path).toArray(String[]::new);

    private final String key;
    private final String path;
    private final String group;
    private final String kind;
    private final String icon;

    Page(String key, String path, String group, String kind, String icon) {
        this.key = key;
        this.path = path;
        this.group = group;
        this.kind = kind;
        this.icon = icon;
    }

    /** Sprite icon id (without the {@code i-} prefix). */
    public String icon() {
        return icon;
    }

    /** Short id, used for message keys ({@code page.<key>.title}) and {@code data-view}. */
    public String key() {
        return key;
    }

    public String path() {
        return path;
    }

    /** Sidebar group: main, calculators or tools. */
    public String group() {
        return group;
    }

    /** Calculator colour identity (general, tatkal, refund, chart), or null. */
    public String kind() {
        return kind;
    }

    public boolean isCalculator() {
        return kind != null;
    }

    public static Optional<Page> fromPath(String path) {
        return Arrays.stream(values()).filter(page -> page.path.equals(path)).findFirst();
    }
}
