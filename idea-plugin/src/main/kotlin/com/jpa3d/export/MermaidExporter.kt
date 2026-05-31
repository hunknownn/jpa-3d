package com.jpa3d.export

/**
 * Mermaid `erDiagram` 텍스트 생성.
 *
 * 형식 참고: https://mermaid.js.org/syntax/entityRelationshipDiagram.html
 *   - 카디널리티 표기:
 *       ONE_TO_ONE     → ||--||
 *       ONE_TO_MANY    → ||--o{
 *       MANY_TO_ONE    → }o--||
 *       MANY_TO_MANY   → }o--o{
 *       EXTENDS / IMPLEMENTS → ||..||  (점선: 비-엔티티 관계)
 *   - 컬럼 타입은 자바 simple-name 을 그대로 노출 (Mermaid 는 자유 식별자 허용).
 */
object MermaidExporter {

    fun render(model: ExportModel): String {
        val sb = StringBuilder()
        sb.append("erDiagram\n")

        // entity 블록
        for (e in model.entities.filter { it.kind == "entity" }) {
            sb.append("    ").append(safeId(e.name)).append(" {\n")
            for (c in e.columns) {
                val type = c.javaType.substringAfterLast('.').ifEmpty { "string" }
                val markers = buildList {
                    if (c.primaryKey) add("PK")
                    if (c.foreignKey) add("FK")
                    if (c.unique) add("UK")
                }.joinToString(",")
                val suffix = if (markers.isNotEmpty()) " $markers" else ""
                sb.append("        ").append(type).append(" ").append(c.columnName).append(suffix).append("\n")
            }
            sb.append("    }\n")
        }

        // 관계 — entity 끼리만.
        val entityIds = model.entities.map { it.fqn }.toSet()
        for (r in model.relations) {
            if (r.source !in entityIds || r.target !in entityIds) continue
            val sourceName = model.entities.first { it.fqn == r.source }.name
            val targetName = model.entities.first { it.fqn == r.target }.name
            val connector = connector(r.type)
            val label = r.label?.let { " : \"$it\"" } ?: " : \"\""
            sb.append("    ").append(safeId(sourceName)).append(" ").append(connector)
                .append(" ").append(safeId(targetName)).append(label).append("\n")
        }

        return sb.toString()
    }

    private fun connector(type: String): String = when (type) {
        "ONE_TO_ONE" -> "||--||"
        "ONE_TO_MANY" -> "||--o{"
        "MANY_TO_ONE" -> "}o--||"
        "MANY_TO_MANY" -> "}o--o{"
        "EXTENDS", "IMPLEMENTS" -> "||..||"
        else -> "||--||"
    }

    /** Mermaid 식별자는 알파벳/숫자/_ 만 허용 — 안전하게 변환. */
    private fun safeId(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
}
