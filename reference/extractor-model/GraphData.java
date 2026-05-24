package com.analyzer.extractor.model;

import java.util.List;

public record GraphData(
        String seed,
        int depth,
        List<Node> nodes,
        List<Edge> links
) {
}
