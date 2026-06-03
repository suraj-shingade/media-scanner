package com.mediascanner.model;

public class IgnoreRule {

    public enum Source { DEFAULT, USER_DEFINED }

    private String pattern;
    private Source source;

    public IgnoreRule() {}

    public IgnoreRule(String pattern, Source source) {
        this.pattern = pattern;
        this.source = source;
    }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
}
