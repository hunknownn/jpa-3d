package com.jpa3d.analyzer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
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
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.toUElementOfType

/**
 * 프로젝트를 훑어 JPA Entity / Repository 그래프를 만든다.
 *
 * UAST 기반 — Java/Kotlin 양쪽의 어노테이션 사이트 타깃 차이를 통합 추상화해
 * Kotlin 의 `@field:`, property/getter/setter 어노테이션 모두 한 곳에서 본다.
 *
 * 동작:
 * 1. 프로젝트 production 스코프의 모든 `PsiClass` → `UClass` 로 변환
 * 2. 클래스 어노테이션에서 `@Entity` / `@MappedSuperclass` / `@Embeddable` 식별
 * 3. 각 Entity 의 `UField` 를 컬럼/관계로 분류 — Kotlin property 어노테이션도 통합 조회
 * 4. Spring Data Repository (직접 상속) → 첫 generic 인자 (Entity) 매핑
 * 5. 양방향 mappedBy 측 엣지는 라벨에 `mappedBy=` 를 남기고 본 분석기 내에서 제거
 *
 * 모든 PSI/UAST 접근은 read action 안에서.
 */
class Jpa3dAnalyzer(private val project: Project) {

    private val log = logger<Jpa3dAnalyzer>()

    fun analyze(): GraphData = ReadAction.compute<GraphData, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        val classes = mutableListOf<UClass>()
        AllClassesSearch.search(scope, project).forEach { c: PsiClass ->
            c.toUElementOfType<UClass>()?.let(classes::add)
            true
        }

        val entityClasses = mutableMapOf<String, EntityRecord>()
        val repositoryClasses = mutableMapOf<String, RepositoryRecord>()

        for (c in classes) {
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

        for ((fqn, rec) in entityClasses) {
            val (columns, relations) = extractFieldsAndRelations(rec.uClass, entityClasses.keys)
            nodes.add(toEntityNode(rec, columns))
            rec.uClass.javaPsi.superClass?.qualifiedName?.let { superFqn ->
                if (entityClasses.containsKey(superFqn)) {
                    rawLinks.add(GraphLink(fqn, superFqn, Relation.EXTENDS, 1, null))
                }
            }
            rawLinks.addAll(relations)
        }

        for ((fqn, rec) in repositoryClasses) {
            nodes.add(toRepositoryNode(rec))
            rawLinks.add(GraphLink(fqn, rec.targetEntity, Relation.USES_ENTITY, 1, null))
        }

        // mappedBy 측 엣지(label 에 "mappedBy=") 는 비주인 측이라 제거
        val links = rawLinks.filterNot { it.label?.contains("mappedBy=") == true }

        GraphData(seed = "", depth = 0, nodes = nodes, links = links)
    }

    // === 어노테이션 식별 ===

    private fun entityKindOf(c: UClass): EntityKind? {
        for (ann in c.uAnnotations) {
            val q = ann.qualifiedName ?: continue
            if (q in JpaAnnotations.ENTITY) return EntityKind.ENTITY
            if (q in JpaAnnotations.MAPPED_SUPERCLASS) return EntityKind.MAPPED_SUPERCLASS
            if (q in JpaAnnotations.EMBEDDABLE) return EntityKind.EMBEDDABLE
        }
        return null
    }

    /**
     * Spring Data Repository 직접 상속만 검사 (예: `interface Foo : JpaRepository<User, Long>`).
     * 첫 번째 generic 인자(Entity FQN) 를 돌려준다.
     */
    private fun repositoryTargetEntity(c: UClass): String? {
        val psi = c.javaPsi
        if (!psi.isInterface) return null
        for (superType in psi.extendsListTypes + psi.implementsListTypes) {
            val superClass = superType.resolve() ?: continue
            val q = superClass.qualifiedName ?: continue
            if (q !in SpringDataRepositories.MARKERS) continue
            val parameters = superType.parameters
            if (parameters.isEmpty()) continue
            val first = parameters[0] as? PsiClassType ?: continue
            return first.resolve()?.qualifiedName
        }
        return null
    }

    // === 필드 -> 컬럼/관계 ===

    private data class FieldExtraction(
        val columns: List<ColumnInfo>,
        val relations: List<GraphLink>
    )

    private fun extractFieldsAndRelations(
        c: UClass,
        knownEntityFqns: Set<String>
    ): FieldExtraction {
        val owner = c.qualifiedName ?: return FieldExtraction(emptyList(), emptyList())
        val columns = mutableListOf<ColumnInfo>()
        val relations = mutableListOf<GraphLink>()

        for (f in c.fields) {
            // static (= Java static / Kotlin companion 등) 은 컬럼 아님
            val psiField = f.javaPsi as? PsiField
            if (psiField?.hasModifierProperty(PsiModifier.STATIC) == true) continue
            if (f.uAnnotations.any { it.qualifiedName in JpaAnnotations.TRANSIENT }) continue

            val relAnnotation = f.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.RELATION_ANNOTATIONS }
            if (relAnnotation != null) {
                buildRelationLink(owner, f, relAnnotation, knownEntityFqns)?.let { relations.add(it) }
                continue
            }
            columns.add(buildColumn(f))
        }
        return FieldExtraction(columns, relations)
    }

    private fun buildColumn(f: UField): ColumnInfo {
        val annotations = f.uAnnotations
        val isPk = annotations.any { it.qualifiedName in JpaAnnotations.ID }
        val columnAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.COLUMN }
        val generatedAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.GENERATED_VALUE }

        val columnName = columnAnn?.stringAttr("name")
        val nullable = columnAnn?.boolAttr("nullable") ?: true
        val unique = columnAnn?.boolAttr("unique") ?: false
        val length = columnAnn?.intAttr("length")

        // GeneratedValue.strategy 는 enum reference — UReferenceExpression 의 resolvedName 사용
        val strategy = generatedAnn?.findAttributeValue("strategy")?.let { extractEnumName(it) }

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

    private fun buildRelationLink(
        owner: String,
        f: UField,
        relAnn: UAnnotation,
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
        if (target !in knownEntityFqns) return null

        val mappedBy = relAnn.stringAttr("mappedBy")
        val label = buildRelationLabel(f, mappedBy)
        return GraphLink(owner, target, relation, 1, label)
    }

    private fun relationTargetFqn(f: UField, relAnn: UAnnotation): String? {
        // targetEntity = Foo.class 가 명시되면 우선
        val targetClassLit = relAnn.findAttributeValue("targetEntity") as? UClassLiteralExpression
        val targetType = targetClassLit?.type as? PsiClassType
        targetType?.resolve()?.qualifiedName?.let { fqn ->
            if (fqn != "java.lang.Void" && fqn != "void") return fqn
        }

        val type = f.type as? PsiClassType ?: return null
        val resolved = type.resolve()
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
            c.supers.any { isCollectionLike(it) }
    }

    private fun buildRelationLabel(f: UField, mappedBy: String?): String {
        if (!mappedBy.isNullOrBlank()) {
            return "${f.name} (mappedBy=$mappedBy)"
        }
        val joinTable = f.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_TABLE }
        val joinTableName = joinTable?.stringAttr("name")
        if (joinTableName != null) {
            return "${f.name} (join: $joinTableName)"
        }
        return f.name
    }

    // === 노드 변환 ===

    private fun toEntityNode(rec: EntityRecord, columns: List<ColumnInfo>): GraphNode {
        val u = rec.uClass
        val psi = u.javaPsi
        val tableName = u.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.TABLE }
            ?.stringAttr("name")
        val pkg = packageOf(psi)
        return GraphNode(
            id = u.qualifiedName ?: u.name ?: "",
            name = u.name ?: psi.name ?: "",
            pkg = pkg,
            kind = if (psi.isInterface) "interface" else "class",
            stereotypes = emptyList(),
            entity = EntityInfo(
                kind = rec.kind.jsonValue,
                tableName = tableName,
                columns = columns
            )
        )
    }

    private fun toRepositoryNode(rec: RepositoryRecord): GraphNode {
        val u = rec.uClass
        val psi = u.javaPsi
        return GraphNode(
            id = u.qualifiedName ?: u.name ?: "",
            name = u.name ?: psi.name ?: "",
            pkg = packageOf(psi),
            kind = "interface",
            stereotypes = listOf("Repository"),
            entity = null
        )
    }

    private fun packageOf(psi: PsiClass): String {
        val qn = psi.qualifiedName ?: return ""
        val dot = qn.lastIndexOf('.')
        return if (dot < 0) "" else qn.substring(0, dot)
    }

    // === UAnnotation 속성 helper ===

    private fun UAnnotation.stringAttr(name: String): String? =
        findAttributeValue(name)?.evaluateString()

    private fun UAnnotation.boolAttr(name: String): Boolean? =
        findAttributeValue(name)?.evaluate() as? Boolean

    private fun UAnnotation.intAttr(name: String): Int? =
        (findAttributeValue(name)?.evaluate() as? Number)?.toInt()

    /**
     * enum 참조 (예: `GenerationType.IDENTITY`) 에서 enum constant 이름만 추출.
     * UReferenceExpression 의 resolvedName 이 우선, 없으면 표현식 텍스트의 마지막 dot 이후.
     */
    private fun extractEnumName(expr: UExpression): String? {
        val ref = expr as? UReferenceExpression
        ref?.resolvedName?.let { return it }
        return expr.asSourceString().substringAfterLast('.')
    }

    private data class EntityRecord(val uClass: UClass, val kind: EntityKind)
    private data class RepositoryRecord(val uClass: UClass, val targetEntity: String)
}
