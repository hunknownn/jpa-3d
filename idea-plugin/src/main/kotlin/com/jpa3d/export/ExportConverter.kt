package com.jpa3d.export

import com.jpa3d.model.ColumnInfo
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import com.jpa3d.model.GraphScope
import com.jpa3d.model.Relation
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
        depth: Int,
        seedType: String = GraphScope.SEED_TYPE_FQN
    ): ExportModel {
        val filtered = filter(graph, scope, seed, depth, seedType)

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

    /**
     * 단일 엔티티 + 그 상속 조상(EXTENDS 체인)만 담은 [ExportModel].
     *
     * 에디터 상단 "이 엔티티 SQL 추출" 버튼이 쓰는 좁은 범위 변환. 일반 BFS([toExportModel])와 달리
     * @ManyToOne 등 연관 엔티티는 끌어오지 않고 **상속 부모만** 따라간다 — @MappedSuperclass /
     * 상위 엔티티의 컬럼이 [DdlExporter] 의 머지 단계에서 합쳐져 정확한 CREATE TABLE 이 나오도록.
     *
     * seed FQN 이 그래프에 없으면(아직 미분석/비엔티티) entities 가 빈 모델을 돌려준다 — 호출 측이 안내.
     */
    fun toSingleEntityModel(graph: GraphData, fqn: String): ExportModel {
        val keep = collectWithAncestors(graph, fqn)
        val nodes = graph.nodes.filter { it.id in keep }
        // 보존한 노드들 사이의 링크만 — 실질적으로 EXTENDS 상속 엣지 (DDL 머지가 참조).
        val links = graph.links.filter { it.source in keep && it.target in keep }
        val model = toExportModel(graph.copy(nodes = nodes, links = links), ExportScope.ALL, "", -1)

        // FK 가 가리키는 추출 범위 밖 엔티티를 "참조 전용" 스텁으로 더한다. 그러면 [DdlExporter] 가
        //  - FK 컬럼 타입을 대상 PK 에서 정확히 빌려오고 (타입 불일치로 인한 SQL 오류 방지),
        //  - 대상 테이블이 이미 존재한다고 가정하고 올바른 FK 제약(ALTER … REFERENCES)을 내보낸다.
        // CREATE TABLE 자체는 referenceOnly 라 생성하지 않는다.
        val references = buildReferenceTargets(graph, model, keep)
        return model.copy(entities = model.entities + references)
    }

    /**
     * 모델 안 엔티티들의 FK 가 가리키지만 [keep] 밖에 있는 대상들을 참조 전용 [ExportEntity] 스텁으로.
     * 각 스텁은 대상의 **유효 PK** 한 컬럼만 담아 FK 타입/대상 컬럼명 해석에 쓰인다.
     */
    private fun buildReferenceTargets(
        graph: GraphData,
        model: ExportModel,
        keep: Set<String>
    ): List<ExportEntity> {
        val byFqn = graph.nodes.associateBy { it.id }
        val parentOf = graph.links.asSequence()
            .filter { it.relation == Relation.EXTENDS }
            .associate { it.source to it.target }

        val targetFqns = model.entities.asSequence()
            .flatMap { it.columns.asSequence() }
            .filter { it.foreignKey }
            .mapNotNull { it.fkTarget }
            .filter { it !in keep } // 이미 모델에 실 테이블로 있는 대상은 제외
            .distinct()

        return targetFqns.mapNotNull { target ->
            val node = byFqn[target] ?: return@mapNotNull null
            val pk = effectivePk(target, byFqn, parentOf) ?: return@mapNotNull null
            referenceEntity(node, pk)
        }.toList()
    }

    /** FK 대상 해석에만 쓰이는 참조 전용 스텁 — 대상 테이블명 + 유효 PK 컬럼만 담는다. */
    private fun referenceEntity(node: GraphNode, pk: ColumnInfo): ExportEntity {
        val e = node.entity
        return ExportEntity(
            fqn = node.id,
            name = node.name,
            `package` = node.pkg,
            kind = "entity",
            tableName = e?.tableName ?: node.name,
            tableNameExplicit = e?.tableName != null,
            schema = e?.schema,
            inheritance = null,
            columns = listOf(toExportColumn(pk)),
            referenceOnly = true
        )
    }

    /** [fqn] 부터 EXTENDS 체인을 올라가며 PK 컬럼을 가진 첫 노드의 PK 를 찾는다 (상속 PK 포함). */
    private fun effectivePk(
        fqn: String,
        byFqn: Map<String, GraphNode>,
        parentOf: Map<String, String>
    ): ColumnInfo? {
        var cur: String? = fqn
        val seen = mutableSetOf<String>()
        while (cur != null && seen.add(cur)) {
            byFqn[cur]?.entity?.columns?.firstOrNull { it.primaryKey }?.let { return it }
            cur = parentOf[cur]
        }
        return null
    }

    /** [fqn] 과 그 위로 이어지는 EXTENDS 부모 체인의 FQN 집합. seed 가 없으면 빈 집합. */
    private fun collectWithAncestors(graph: GraphData, fqn: String): Set<String> {
        if (graph.nodes.none { it.id == fqn }) return emptySet()
        // EXTENDS 는 source=자식, target=부모 ([Jpa3dAnalyzer] 규약).
        val parentsOf = graph.links.asSequence()
            .filter { it.relation == Relation.EXTENDS }
            .groupBy({ it.source }, { it.target })
        val result = linkedSetOf(fqn)
        val queue = ArrayDeque(listOf(fqn))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (parent in parentsOf[cur].orEmpty()) {
                if (result.add(parent)) queue.add(parent)
            }
        }
        return result
    }

    private fun filter(g: GraphData, scope: ExportScope, seed: String, depth: Int, seedType: String): GraphData {
        if (scope == ExportScope.ALL || seed.isBlank()) return g
        val seeds = GraphScope.resolveSeeds(g.nodes, seedType, seed)
        if (seeds.isEmpty()) return g.copy(nodes = emptyList(), links = emptyList())
        val reachable = GraphScope.reachable(seeds, g.links, depth)
        val nodes = g.nodes.filter { it.id in reachable }
        val keep = nodes.map { it.id }.toSet()
        val links = g.links.filter { it.source in keep && it.target in keep }
        return g.copy(nodes = nodes, links = links)
    }

    private fun toExportEntity(node: GraphNode): ExportEntity {
        val e = node.entity!!
        return ExportEntity(
            fqn = node.id,
            name = node.name,
            `package` = node.pkg,
            kind = e.kind,
            tableName = e.tableName ?: node.name,
            tableNameExplicit = e.tableName != null,
            schema = e.schema,
            inheritance = e.inheritance?.let {
                ExportInheritance(
                    strategy = it.strategy,
                    discriminatorColumn = it.discriminatorColumn,
                    discriminatorValue = it.discriminatorValue
                )
            },
            columns = e.columns.map(::toExportColumn),
            compositeIndexes = e.compositeIndexes,
            compositeUniques = e.compositeUniques
        )
    }

    private fun toExportColumn(c: ColumnInfo): ExportColumn = ExportColumn(
        name = c.fieldName,
        columnName = c.columnName ?: c.fieldName,
        columnNameExplicit = c.columnNameExplicit,
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
