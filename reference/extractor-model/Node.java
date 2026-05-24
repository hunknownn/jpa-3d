package com.analyzer.extractor.model;

import java.util.List;

/**
 * @param sourceFile ClassFile 의 SourceFile 속성 (예: "ClassIndexer.java"). 없으면 null.
 *                   inner class 라도 outer 의 source 파일을 가리킴.
 */
public record Node(
        String id,
        String name,
        String pkg,
        String kind,
        List<String> stereotypes,
        List<MethodInfo> methods,
        List<FieldInfo> fields,
        String sourceFile,
        EntityInfo entity
) {
    public Node(String id, String name, String pkg, String kind,
                List<String> stereotypes, List<MethodInfo> methods,
                List<FieldInfo> fields, String sourceFile) {
        this(id, name, pkg, kind, stereotypes, methods, fields, sourceFile, null);
    }
}
