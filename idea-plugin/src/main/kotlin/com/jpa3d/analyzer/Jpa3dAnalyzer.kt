package com.jpa3d.analyzer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteral
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import com.jpa3d.model.ColumnInfo
import com.jpa3d.model.EntityInfo
import com.jpa3d.model.EntityKind
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import com.jpa3d.model.Relation

/**
 * 프로젝트 PSI 를 훑어 JPA Entity / Repository 그래프를 만든다.
 *
 * 동작:
 * 1. 프로젝트 production 스코프의 모든 `PsiClass` 순회
 * 2. `@Entity` / `@MappedSuperclass` / `@Embeddable` 이 붙은 클래스를 모은다
 * 3. 각 Entity 의 필드를 컬럼/관계로 분류
 * 4. Spring Data Repository (직접 상속) → Entity 매핑
 * 5. 양방향 관계 mappedBy 정리 — 비주인 측 엣지는 라벨에 `mappedBy=` 를 남기고
 *    호출자가 후처리로 제거할 수 있게 한다 (현 구현은 이 분석기에서 바로 제거).
 *
 * 모든 PSI 접근은 read action 안에서. 호출 측이 DumbAware 보호를 책임진다.
 */
class Jpa3dAnalyzer(private val project: Project) {

    private val log = logger<Jpa3dAnalyzer>()

    fun analyze(): GraphData = ReadAction.compute<GraphData, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        val allClasses = mutableListOf<PsiClass>()
        AllClassesSearch.search(scope, project).forEach { c ->
            allClasses.add(c)
            true
        }

        val entityClasses = mutableMapOf<String, EntityRecord>()
        val repositoryClasses = mutableMapOf<String, RepositoryRecord>()

        for (c in allClasses) {
            val fqn = c.qualifiedName ?: continue
            val kind = entityKindOf(c)
            if (kind != null) {
                entityClasses[fqn] = EntityRecord(c, kind)
                continue
            }
            val targetEntity = repositoryTargetEntity(c)
            if (targetEntity != null) {
                repositoryClasses[fqn] = RepositoryRecord(c, targetEntity)
            }
        }

        log.info("found ${entityClasses.size} entities, ${repositoryClasses.size} repositories")

        val nodes = mutableListOf<GraphNode>()
        val rawLinks = mutableListOf<GraphLink>()

        // Entity 노드 + 컬럼/관계 추출
        for ((fqn, rec) in entityClasses) {
            val (columns, relations) = extractFieldsAndRelations(rec.psi, entityClasses.keys)
            nodes.add(toEntityNode(rec, columns))
            // EXTENDS — 상위가 다른 Entity / MappedSuperclass 인 경우만
            rec.psi.superClass?.qualifiedName?.let { superFqn ->
                if (entityClasses.containsKey(superFqn)) {
                    rawLinks.add(GraphLink(fqn, superFqn, Relation.EXTENDS, 1, null))
                }
            }
            rawLinks.addAll(relations)
        }

        // Repository 노드 + USES_ENTITY 엣지
        for ((fqn, rec) in repositoryClasses) {
            nodes.add(toRepositoryNode(rec))
            rawLinks.add(GraphLink(fqn, rec.targetEntity, Relation.USES_ENTITY, 1, null))
        }

        // 양방향 mappedBy 정리: label 에 "mappedBy=" 가 들어간 엣지는 비주인 측이므로 드롭
        val links = rawLinks.filterNot { it.label?.contains("mappedBy=") == true }

        GraphData(
            seed = "",
            depth = 0,
            nodes = nodes,
            links = links
        )
    }

    // === 어노테이션 식별 ===

    private fun entityKindOf(c: PsiClass): EntityKind? {
        for (ann in c.annotations) {
            val q = ann.qualifiedName ?: continue
            if (q in JpaAnnotations.ENTITY) return EntityKind.ENTITY
            if (q in JpaAnnotations.MAPPED_SUPERCLASS) return EntityKind.MAPPED_SUPERCLASS
            if (q in JpaAnnotations.EMBEDDABLE) return EntityKind.EMBEDDABLE
        }
        return null
    }

    /**
     * Spring Data Repository 인터페이스이면, 첫 번째 generic 인자 (Entity FQN) 반환.
     * 마커가 아니거나 generic 이 없으면 null.
     *
     * 직접 상속만 검사 (예: `extends JpaRepository<User, Long>`).
     * 다단계 상속은 미지원 (사용자 정의 BaseRepo<T, ID> 같은 경우).
     */
    private fun repositoryTargetEntity(c: PsiClass): String? {
        if (!c.isInterface) return null
        for (superType in c.extendsListTypes + c.implementsListTypes) {
            val superClass = superType.resolve() ?: continue
            val q = superClass.qualifiedName ?: continue
            if (q !in SpringDataRepositories.MARKERS) continue

            // 첫 번째 type parameter (T) 추출
            val parameters = superType.parameters
            if (parameters.isEmpty()) continue
            val first = parameters[0] as? PsiClassType ?: continue
            return first.resolve()?.qualifiedName
        }
        return null
    }

    // === 필드 -> 컬럼/관계 변환 ===

    private data class FieldExtraction(
        val columns: List<ColumnInfo>,
        val relations: List<GraphLink>
    )

    private fun extractFieldsAndRelations(
        c: PsiClass,
        knownEntityFqns: Set<String>
    ): FieldExtraction {
        val owner = c.qualifiedName ?: return FieldExtraction(emptyList(), emptyList())
        val columns = mutableListOf<ColumnInfo>()
        val relations = mutableListOf<GraphLink>()

        for (f in c.fields) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue
            if (f.annotations.any { it.qualifiedName in JpaAnnotations.TRANSIENT }) continue

            val relAnnotation = f.annotations.firstOrNull { it.qualifiedName in JpaAnnotations.RELATION_ANNOTATIONS }
            if (relAnnotation != null) {
                buildRelationLink(owner, f, relAnnotation, knownEntityFqns)?.let { relations.add(it) }
                continue
            }
            columns.add(buildColumn(f))
        }
        return FieldExtraction(columns, relations)
    }

    private fun buildColumn(f: PsiField): ColumnInfo {
        val isPk = f.annotations.any { it.qualifiedName in JpaAnnotations.ID }
        val columnAnn = f.annotations.firstOrNull { it.qualifiedName in JpaAnnotations.COLUMN }
        val generatedAnn = f.annotations.firstOrNull { it.qualifiedName in JpaAnnotations.GENERATED_VALUE }

        val columnName = columnAnn?.let { stringAttr(it, "name") }
        val nullable = columnAnn?.let { boolAttr(it, "nullable") } ?: true
        val unique = columnAnn?.let { boolAttr(it, "unique") } ?: false
        val length = columnAnn?.let { intAttr(it, "length") }

        // GeneratedValue 의 strategy 는 enum reference — name 만 끄집어내면 충분
        val strategy = generatedAnn?.findAttributeValue("strategy")?.text
            ?.substringAfterLast('.')

        return ColumnInfo(
            fieldName = f.name,
            columnName = columnName ?: f.name,
            javaType = f.type.canonicalText,
            primaryKey = isPk,
            nullable = nullable,
            unique = unique,
            length = length,
            generatedValue = strategy
        )
    }

    /**
     * 관계 어노테이션을 GraphLink 로 변환.
     *
     * - target Entity: 필드 타입 (단일) 또는 컬렉션 generic 의 첫 인자.
     * - 양방향 비주인 측 (mappedBy 명시) 은 라벨에 `mappedBy=...` 를 박아 호출자가 제거.
     * - 주인 측은 `필드명 (join: 테이블명)` 식 라벨.
     */
    private fun buildRelationLink(
        owner: String,
        f: PsiField,
        relAnn: PsiAnnotation,
        knownEntityFqns: Set<String>
    ): GraphLink? {
        val relation = when (relAnn.qualifiedName) {
            in JpaAnnotations.ONE_TO_MANY -> Relation.ONE_TO_MANY
            in JpaAnnotations.MANY_TO_ONE -> Relation.MANY_TO_ONE
            in JpaAnnotations.ONE_TO_ONE -> Relation.ONE_TO_ONE
            in JpaAnnotations.MANY_TO_MANY -> Relation.MANY_TO_MANY
            else -> return null
        }

        val target = relationTargetFqn(f, relAnn) ?: return null
        if (target !in knownEntityFqns) return null  // 프로젝트 외 타입은 무시

        val mappedBy = stringAttr(relAnn, "mappedBy")
        val label = buildRelationLabel(f, relAnn, mappedBy)

        return GraphLink(owner, target, relation, 1, label)
    }

    private fun relationTargetFqn(f: PsiField, relAnn: PsiAnnotation): String? {
        // targetEntity 가 명시되면 우선
        val targetEntityAttr = relAnn.findAttributeValue("targetEntity")?.text
        if (!targetEntityAttr.isNullOrBlank() && targetEntityAttr != "void.class" && targetEntityAttr != "Void.class") {
            // "com.example.Foo.class" 형태에서 FQN 추출
            return targetEntityAttr.removeSuffix(".class")
        }

        val type = f.type as? PsiClassType ?: return null
        val resolved = type.resolve()
        // 컬렉션이면 generic 첫 인자
        if (resolved != null && isCollectionLike(resolved)) {
            val params = type.parameters
            if (params.isEmpty()) return null
            return (params[0] as? PsiClassType)?.resolve()?.qualifiedName
        }
        return resolved?.qualifiedName
    }

    private fun isCollectionLike(c: PsiClass): Boolean {
        val fqn = c.qualifiedName ?: return false
        return fqn == "java.util.Collection" ||
            fqn == "java.util.List" ||
            fqn == "java.util.Set" ||
            fqn == "java.util.Map" ||
            // 상위가 Collection 인 경우까지 잡기 위해 InheritanceUtil 을 쓸 수도 있지만
            // 우선은 빠른 매칭으로 시작
            c.supers.any { isCollectionLike(it) }
    }

    private fun buildRelationLabel(f: PsiField, relAnn: PsiAnnotation, mappedBy: String?): String {
        if (!mappedBy.isNullOrBlank()) {
            return "${f.name} (mappedBy=$mappedBy)"
        }
        val joinTable = f.annotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_TABLE }
        val joinTableName = joinTable?.let { stringAttr(it, "name") }
        if (joinTableName != null) {
            return "${f.name} (join: $joinTableName)"
        }
        return f.name
    }

    // === 노드 변환 ===

    private fun toEntityNode(rec: EntityRecord, columns: List<ColumnInfo>): GraphNode {
        val c = rec.psi
        val tableName = c.annotations.firstOrNull { it.qualifiedName in JpaAnnotations.TABLE }
            ?.let { stringAttr(it, "name") }
        val pkg = (c.containingFile as? com.intellij.psi.PsiJavaFile)?.packageName ?: ""
        return GraphNode(
            id = c.qualifiedName ?: c.name ?: "",
            name = c.name ?: "",
            pkg = pkg,
            kind = if (c.isInterface) "interface" else "class",
            stereotypes = emptyList(),
            entity = EntityInfo(
                kind = rec.kind.jsonValue,
                tableName = tableName,
                columns = columns
            )
        )
    }

    private fun toRepositoryNode(rec: RepositoryRecord): GraphNode {
        val c = rec.psi
        val pkg = (c.containingFile as? com.intellij.psi.PsiJavaFile)?.packageName ?: ""
        return GraphNode(
            id = c.qualifiedName ?: c.name ?: "",
            name = c.name ?: "",
            pkg = pkg,
            kind = "interface",
            stereotypes = listOf("Repository"),
            entity = null
        )
    }

    // === 어노테이션 속성 helper ===

    private fun stringAttr(ann: PsiAnnotation, name: String): String? {
        val v = ann.findAttributeValue(name) as? PsiLiteral ?: return null
        return v.value as? String
    }

    private fun boolAttr(ann: PsiAnnotation, name: String): Boolean? {
        val v = ann.findAttributeValue(name) as? PsiLiteral ?: return null
        return v.value as? Boolean
    }

    private fun intAttr(ann: PsiAnnotation, name: String): Int? {
        val v = ann.findAttributeValue(name) as? PsiLiteral ?: return null
        return (v.value as? Int)
    }

    private data class EntityRecord(val psi: PsiClass, val kind: EntityKind)
    private data class RepositoryRecord(val psi: PsiClass, val targetEntity: String)
}
