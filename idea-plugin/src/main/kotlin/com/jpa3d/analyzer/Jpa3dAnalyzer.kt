package com.jpa3d.analyzer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.jpa3d.model.ColumnInfo
import com.jpa3d.model.EntityInfo
import com.jpa3d.model.EntityKind
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import com.jpa3d.model.InheritanceInfo
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
 * 인덱스 기반 — `AllClassesSearch` 처럼 전체 클래스를 PSI 로 로드하지 않고,
 * 어노테이션/상속 인덱스로 후보를 직접 조회한다.
 *  - Entity 류: [AnnotatedElementsSearch] 로 `@Entity` / `@MappedSuperclass` / `@Embeddable`
 *  - Repository: 각 Spring Data 마커에 대해 [DirectClassInheritorsSearch]
 *
 * UAST 기반 — Java/Kotlin 의 어노테이션 사이트 타깃 차이를 통합 추상화.
 *
 * 모든 PSI/UAST 접근은 read action 안에서.
 */
class Jpa3dAnalyzer(private val project: Project) {

    private val log = logger<Jpa3dAnalyzer>()

    fun analyze(): GraphData = ReadAction.compute<GraphData, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        // 어노테이션 PsiClass 를 찾을 때는 라이브러리 jar 도 포함해야 함 (jakarta.persistence.* 가 거기 있음).
        val classpathScope = GlobalSearchScope.allScope(project)

        val entityClasses = mutableMapOf<String, EntityRecord>()
        val repositoryClasses = mutableMapOf<String, RepositoryRecord>()

        // === Entity 후보 수집 ===
        scanAnnotated(JpaAnnotations.ENTITY, facade, classpathScope, scope, EntityKind.ENTITY, entityClasses)
        scanAnnotated(JpaAnnotations.MAPPED_SUPERCLASS, facade, classpathScope, scope, EntityKind.MAPPED_SUPERCLASS, entityClasses)
        scanAnnotated(JpaAnnotations.EMBEDDABLE, facade, classpathScope, scope, EntityKind.EMBEDDABLE, entityClasses)

        // === Repository 후보 수집 ===
        // ClassInheritorsSearch(checkDeep=true) 로 다단계 상속도 포착.
        // 예: UserRepo extends MyBaseRepo<User>, MyBaseRepo<T> extends JpaRepository<T, ID>
        for (markerFqn in SpringDataRepositories.MARKERS) {
            val marker = facade.findClass(markerFqn, classpathScope) ?: continue
            ClassInheritorsSearch.search(marker, scope, true).forEach { psi ->
                val uClass = psi.toUElementOfType<UClass>() ?: return@forEach
                val fqn = uClass.qualifiedName ?: return@forEach
                if (entityClasses.containsKey(fqn) || repositoryClasses.containsKey(fqn)) return@forEach
                // 제네릭 베이스 (예: MyBaseRepo<T>) 는 첫 type arg 가 PsiTypeParameter 로 풀려 null 반환 → 스킵
                val target = repositoryTargetEntity(uClass) ?: return@forEach
                repositoryClasses[fqn] = RepositoryRecord(uClass, target)
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

    /**
     * 주어진 어노테이션 FQN 집합(jakarta + javax) 으로 어노테이션된 클래스를 인덱스에서
     * 직접 조회해 [out] 에 채운다.
     *
     * - 어노테이션 PsiClass 가 classpath 에 없으면 (의존성 미포함) 그냥 스킵.
     * - 같은 클래스가 jakarta 와 javax 양쪽에 잡힐 일은 거의 없지만 중복 방지를 위해
     *   먼저 본 entryKind 가 우선.
     */
    private fun scanAnnotated(
        annotationFqns: Set<String>,
        facade: JavaPsiFacade,
        classpathScope: GlobalSearchScope,
        searchScope: GlobalSearchScope,
        kind: EntityKind,
        out: MutableMap<String, EntityRecord>
    ) {
        for (annFqn in annotationFqns) {
            val annClass = facade.findClass(annFqn, classpathScope) ?: continue
            AnnotatedElementsSearch.searchPsiClasses(annClass, searchScope).forEach { psi ->
                val uClass = psi.toUElementOfType<UClass>() ?: return@forEach
                val fqn = uClass.qualifiedName ?: return@forEach
                out.putIfAbsent(fqn, EntityRecord(uClass, kind))
            }
        }
    }

    /**
     * Spring Data Repository 의 대상 Entity FQN.
     *
     * 다단계 상속 지원:
     *   `interface UserRepo : MyBaseRepo<User>`,
     *   `interface MyBaseRepo<T> : JpaRepository<T, Long>`
     *   → UserRepo 의 대상은 User.
     *
     * 슈퍼타입을 BFS 로 따라가며 [PsiSubstitutor] 로 타입 매개변수 치환을 누적한다.
     * 마커 인터페이스에 도달하면 그 시점의 substitutor 로 marker 의 첫 type parameter 를
     * 풀어 entity FQN 으로 변환.
     *
     * 풀린 타입이 여전히 [PsiTypeParameter] 라면 (제네릭 베이스 자체) null 을 돌려 호출측에서 스킵.
     */
    private fun repositoryTargetEntity(c: UClass): String? {
        val psi = c.javaPsi
        if (!psi.isInterface) return null
        val seen = mutableSetOf<String>()
        return resolveRepositoryTarget(psi, PsiSubstitutor.EMPTY, seen)
    }

    private fun resolveRepositoryTarget(
        c: PsiClass,
        sub: PsiSubstitutor,
        seen: MutableSet<String>
    ): String? {
        val fqn = c.qualifiedName
        if (fqn != null && !seen.add(fqn)) return null

        for (superType in c.extendsListTypes + c.implementsListTypes) {
            val superClass = superType.resolve() ?: continue
            val superFqn = superClass.qualifiedName ?: continue

            // 1) superType 의 type args 에 현재 substitutor 를 적용해 풀어낸다.
            // substitute 가 null 을 줄 수 있어 원본 type 으로 폴백.
            val resolvedArgs: Array<PsiType> = superType.parameters
                .map { sub.substitute(it) ?: it }
                .toTypedArray()

            // 2) marker 에 도달했으면 첫 인자가 대상 entity
            if (superFqn in SpringDataRepositories.MARKERS) {
                val first = resolvedArgs.firstOrNull() as? PsiClassType ?: continue
                val resolved = first.resolve() ?: continue
                if (resolved is PsiTypeParameter) continue  // 제네릭 베이스 → 미해결
                return resolved.qualifiedName
            }

            // 3) 마커 아니면 더 깊이 — superClass 의 type param 을 풀린 인자로 매핑한 substitutor 로 재귀
            val nextSub = PsiSubstitutor.EMPTY.putAll(superClass, resolvedArgs)
            resolveRepositoryTarget(superClass, nextSub, seen)?.let { return it }
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

        // 클래스 레벨 @Table 의 indexes / uniqueConstraints 를 미리 모아둬서 buildColumn 에서 참조.
        // 매칭은 db 컬럼명 lowercase 기준.
        val (indexedCols, uniqueCols) = extractTableConstraints(c)

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
            columns.add(buildColumn(f, indexedCols, uniqueCols))
        }
        return FieldExtraction(columns, relations)
    }

    /**
     * @Table 의 indexes / uniqueConstraints 에서 컬럼명 집합을 추출.
     *
     * UAnnotation 의 nested 배열 어노테이션 접근은 PSI 가 더 직접적이라 PSI 로 내려가서 읽는다.
     *
     * indexes: `@Index(columnList="email" | "a, b, c desc")` — 쉼표 분리, ASC/DESC 토큰 제거.
     * uniqueConstraints: `@UniqueConstraint(columnNames={"email"})` — 문자열 배열.
     *
     * @return (indexedColumns, uniqueColumns) — 모두 lowercase 컬럼명 set
     */
    private fun extractTableConstraints(c: UClass): Pair<Set<String>, Set<String>> {
        val tableAnn = c.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.TABLE }
        val psi = tableAnn?.javaPsi as? PsiAnnotation ?: return Pair(emptySet(), emptySet())

        val indexed = mutableSetOf<String>()
        val unique = mutableSetOf<String>()

        forEachNestedAnnotation(psi.findAttributeValue("indexes")) { ann ->
            val colList = (ann.findAttributeValue("columnList") as? PsiLiteralExpression)?.value as? String ?: return@forEachNestedAnnotation
            for (col in parseColumnList(colList)) indexed.add(col)
        }

        forEachNestedAnnotation(psi.findAttributeValue("uniqueConstraints")) { ann ->
            collectStringArray(ann.findAttributeValue("columnNames"), unique)
        }

        return Pair(indexed, unique)
    }

    /** 배열 또는 단일 어노테이션 어느 쪽이든 element 어노테이션을 순회. */
    private inline fun forEachNestedAnnotation(value: PsiAnnotationMemberValue?, block: (PsiAnnotation) -> Unit) {
        when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.forEach { (it as? PsiAnnotation)?.let(block) }
            is PsiAnnotation -> block(value)
            else -> Unit
        }
    }

    /** 배열 또는 단일 문자열을 lowercase 로 [out] 에 추가. */
    private fun collectStringArray(value: PsiAnnotationMemberValue?, out: MutableSet<String>) {
        when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.forEach {
                ((it as? PsiLiteralExpression)?.value as? String)?.let { s -> out.add(s.lowercase()) }
            }
            is PsiLiteralExpression -> (value.value as? String)?.let { out.add(it.lowercase()) }
            else -> Unit
        }
    }

    /** `"a, b DESC, c"` → ["a", "b", "c"] (lowercase). */
    private fun parseColumnList(s: String): List<String> =
        s.split(",")
            .map { it.trim().substringBefore(' ').lowercase() }
            .filter { it.isNotEmpty() }

    private fun buildColumn(f: UField, indexedCols: Set<String>, uniqueCols: Set<String>): ColumnInfo {
        val annotations = f.uAnnotations
        val isPk = annotations.any { it.qualifiedName in JpaAnnotations.ID }
        val columnAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.COLUMN }
        val generatedAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.GENERATED_VALUE }

        val columnName = columnAnn?.stringAttr("name")
        val nullable = columnAnn?.boolAttr("nullable") ?: true
        val columnUnique = columnAnn?.boolAttr("unique") ?: false
        val length = columnAnn?.intAttr("length")

        // GeneratedValue.strategy 는 enum reference — UReferenceExpression 의 resolvedName 사용
        val strategy = generatedAnn?.findAttributeValue("strategy")?.let { extractEnumName(it) }

        val dbName = (columnName ?: f.name).lowercase()
        val tableUnique = dbName in uniqueCols
        val indexed = dbName in indexedCols

        return ColumnInfo(
            fieldName = f.name,
            columnName = columnName ?: f.name,
            javaType = f.type.canonicalText,
            primaryKey = isPk,
            nullable = nullable,
            unique = columnUnique || tableUnique,
            indexed = indexed,
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
        // inverse side (mappedBy) 는 FK 가 반대편에 있어 우리 entity 에는 @JoinColumn 이 없는 게 정상
        if (!mappedBy.isNullOrBlank()) {
            return "${f.name} (mappedBy=$mappedBy)"
        }
        // ManyToMany 등 join table 을 별도로 두는 경우
        val joinTable = f.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_TABLE }
        val joinTableName = joinTable?.stringAttr("name")
        if (joinTableName != null) {
            return "${f.name} (join: $joinTableName)"
        }
        // 단일 @JoinColumn → FK 컬럼명. (다중 컬럼 PK 케이스는 @JoinColumns 로 따로 처리)
        val joinColumn = f.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_COLUMN }
            ?.stringAttr("name")
        if (joinColumn != null) {
            return "${f.name} → $joinColumn"
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
                columns = columns,
                inheritance = extractInheritance(u)
            )
        )
    }

    /**
     * @Inheritance 정보 추출.
     *
     * JPA 규칙:
     *  - @Inheritance 는 베이스 entity 에만 붙는다. 자식 entity 는 부모로부터 strategy 를 물려받는다.
     *  - @DiscriminatorColumn 도 베이스에만 (SINGLE_TABLE/JOINED).
     *  - @DiscriminatorValue 는 자식 entity 마다.
     *
     * 그러므로:
     *  - 클래스 본인에 @Inheritance 가 있으면 베이스. strategy + discriminatorColumn 채움.
     *  - 자식이면 슈퍼클래스를 따라 올라가며 @Inheritance 를 찾아 strategy 만 상속,
     *    discriminatorColumn 은 부모의 것을 그대로 넘기고, discriminatorValue 는 자기 것.
     *  - 둘 다 못 찾으면 null.
     */
    private fun extractInheritance(u: UClass): InheritanceInfo? {
        val self = u.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.INHERITANCE }
        val ownDisc = u.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.DISCRIMINATOR_COLUMN }
            ?.stringAttr("name")
        val discValue = u.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.DISCRIMINATOR_VALUE }
            ?.stringAttr("value")

        if (self != null) {
            // 명시되지 않으면 JPA 기본 SINGLE_TABLE
            val strategy = self.findAttributeValue("strategy")?.let { extractEnumName(it) } ?: "SINGLE_TABLE"
            return InheritanceInfo(strategy, ownDisc, discValue)
        }

        // 부모에서 @Inheritance 상속받는지 확인
        var sc: PsiClass? = u.javaPsi.superClass
        val visited = mutableSetOf<String>()
        while (sc != null) {
            val q = sc.qualifiedName ?: break
            if (!visited.add(q)) break
            if (q == "java.lang.Object") break
            val parentU = sc.toUElementOfType<UClass>()
            val parentInh = parentU?.uAnnotations?.firstOrNull { it.qualifiedName in JpaAnnotations.INHERITANCE }
            if (parentInh != null) {
                val strategy = parentInh.findAttributeValue("strategy")?.let { extractEnumName(it) } ?: "SINGLE_TABLE"
                val parentDisc = parentU.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.DISCRIMINATOR_COLUMN }
                    ?.stringAttr("name")
                return InheritanceInfo(strategy, parentDisc, discValue)
            }
            sc = sc.superClass
        }

        // @Inheritance 가 없어도 @DiscriminatorValue 만 있을 수 있으나 (단독으로는 의미 없음) 무시.
        return null
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
