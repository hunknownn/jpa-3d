package com.jpa3d.export

import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import java.time.Instant

/**
 * 내부 [GraphData] → 외부 [ExportModel] 변환.
 *
 * 변환 시 결정사항:
 *   - Repository / 인터페이스(entity 가 null 인 노드)는 export 에서 제외. ERD/DDL 의미가 없다.
 *   - columnName 누락 시 fieldName 그대로 사용 (JPA 기본 매핑과 동일).
 *   - tableName 누락 시 entity simple name 을 그대로 사용.
 */
object ExportConverter {

    private const val SCHEMA_VERSION = 1

    /**
     * scope/seed/depth 를 적용해 부분 그래프를 만든 뒤 [ExportModel] 로 매핑.
     *
     * BFS 는 [com.jpa3d.Jpa3dRequestHandler.bfs] 와 동일한 방향성 무시 알고리즘.
     */
    fun toExportModel(
        graph: GraphData,
        scope: ExportScope,
        seed: String,
        depth: Int
    ): ExportModel {
        val filtered = filter(graph, scope, seed, depth)

        // entity 가 있는 노드만 export — Repository/일반 인터페이스 제외.
        val entityNodes = filtered.nodes.filter { it.entity != null }
        val entityIds = entityNodes.map { it.id }.toSet()

        val entities = entityNodes.map(::toExportEntity)
        val relations = filtered.links
            .filter { it.source in entityIds && it.target in entityIds }
            .map(::toExportRelation)

        return ExportModel(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = Instant.now().toString(),
            entities = entities,
            relations = relations
        )
    }

    private fun filter(g: GraphData, scope: ExportScope, seed: String, depth: Int): GraphData {
        if (scope == ExportScope.ALL || seed.isBlank()) return g
        val nodeIds = g.nodes.map { it.id }.toSet()
        if (seed !in nodeIds) return g.copy(nodes = emptyList(), links = emptyList())
        val reachable = bfs(seed, g.links, depth)
        val nodes = g.nodes.filter { it.id in reachable }
        val keep = nodes.map { it.id }.toSet()
        val links = g.links.filter { it.source in keep && it.target in keep }
        return g.copy(nodes = nodes, links = links)
    }

    private fun bfs(seed: String, links: List<GraphLink>, maxDepth: Int): Set<String> {
        if (maxDepth < 0) return setOf(seed)
        val adj = HashMap<String, MutableList<String>>()
        for (l in links) {
            adj.getOrPut(l.source) { mutableListOf() }.add(l.target)
            adj.getOrPut(l.target) { mutableListOf() }.add(l.source)
        }
        val visited = mutableSetOf(seed)
        var frontier = listOf(seed)
        repeat(maxDepth) {
            val next = mutableListOf<String>()
            for (id in frontier) {
                for (n in adj[id].orEmpty()) {
                    if (visited.add(n)) next.add(n)
                }
            }
            if (next.isEmpty()) return visited
            frontier = next
        }
        return visited
    }

    private fun toExportEntity(node: GraphNode): ExportEntity {
        val e = node.entity!!
        return ExportEntity(
            fqn = node.id,
            name = node.name,
            `package` = node.pkg,
            kind = e.kind,
            tableName = e.tableName ?: node.name,
            schema = e.schema,
            inheritance = e.inheritance?.let {
                ExportInheritance(
                    strategy = it.strategy,
                    discriminatorColumn = it.discriminatorColumn,
                    discriminatorValue = it.discriminatorValue
                )
            },
            columns = e.columns.map { c ->
                ExportColumn(
                    name = c.fieldName,
                    columnName = c.columnName ?: c.fieldName,
                    javaType = c.javaType,
                    primaryKey = c.primaryKey,
                    nullable = c.nullable,
                    unique = c.unique,
                    indexed = c.indexed,
                    foreignKey = c.foreignKey,
                    fkTarget = c.fkTarget,
                    length = c.length,
                    generatedValue = c.generatedValue,
                    precision = c.precision,
                    scale = c.scale,
                    lob = c.lob,
                    sequenceName = c.sequenceName,
                    enumType = c.enumType,
                    temporalType = c.temporalType,
                    columnDefinition = c.columnDefinition
                )
            },
            compositeIndexes = e.compositeIndexes,
            compositeUniques = e.compositeUniques
        )
    }

    private fun toExportRelation(link: GraphLink): ExportRelation = ExportRelation(
        source = link.source,
        target = link.target,
        type = link.relation.name,
        label = link.label,
        manyToManyOwning = link.manyToManyOwning,
        joinTableName = link.joinTableName,
        joinColumnName = link.joinColumnName,
        inverseJoinColumnName = link.inverseJoinColumnName
    )
}
