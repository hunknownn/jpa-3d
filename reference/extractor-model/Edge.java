package com.analyzer.extractor.model;

public record Edge(
        String source,
        String target,
        Relation relation,
        int weight,
        String label
) {
    public Edge(String source, String target, Relation relation) {
        this(source, target, relation, 1, null);
    }

    public Edge(String source, String target, Relation relation, String label) {
        this(source, target, relation, 1, label);
    }
}
