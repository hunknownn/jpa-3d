package com.jpa3d.analyzer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.jpa3d.model.ColumnInfo
import com.jpa3d.model.EntityInfo
import com.jpa3d.model.EntityKind
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import com.jpa3d.model.ArchitectureDetector
import com.jpa3d.model.EdgeBoundary
import com.jpa3d.model.InheritanceInfo
import com.jpa3d.model.ModuleResolver
import com.jpa3d.model.Relation
import com.jpa3d.model.SoftRefHeuristic
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

    private companion object {
        /** resolve 실패 시 컬렉션 판정용 raw simple name (java.util 기준). */
        val COLLECTION_SIMPLE_NAMES = setOf(
            "Collection", "List", "Set", "Map", "Iterable",
            "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet"
        )
    }

    /**
     * 전체 프로젝트 스캔은 수 초가 걸릴 수 있어 **취소 가능한 non-blocking read action** 으로 돈다.
     * 그냥 [ReadAction.compute] 로 감싸면 read 락을 길게 쥐어, 그 사이 EDT 가 write-intent
     * (예: 에디터 클릭)을 요청하면 분석이 끝날 때까지 UI 가 얼어붙는다(수 초 freeze).
     * nonBlocking 은 write 요청이 들어오면 분석을 취소·재시도해 EDT 를 막지 않는다.
     *
     * 항상 백그라운드 스레드(pooled / Backgroundable / JCEF 콜백)에서 호출된다 — EDT 에서
     * [NonBlockingReadAction.executeSynchronously] 를 부르면 예외가 나므로 호출 측이 EDT 가
     * 아님을 전제한다(캐시 [Jpa3dAnalysisCache] 경유).
     */
    fun analyze(): GraphData = ReadAction.nonBlocking<GraphData> {
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
        // soft-ref 추출은 모든 엔티티 컬럼이 모인 뒤(PK 타입 비교) 돌아야 하므로 컬럼을 보관한다.
        val columnsByFqn = HashMap<String, List<ColumnInfo>>()

        for ((fqn, rec) in entityClasses) {
            val extraction = extractFieldsAndRelations(rec.uClass, entityClasses.keys)
            columnsByFqn[fqn] = extraction.columns
            nodes.add(toEntityNode(rec, extraction.columns, extraction.constraints))
            rec.uClass.javaPsi.superClass?.qualifiedName?.let { superFqn ->
                if (entityClasses.containsKey(superFqn)) {
                    rawLinks.add(GraphLink(fqn, superFqn, Relation.EXTENDS, 1, null))
                }
            }
            rawLinks.addAll(extraction.relations)
        }

        for ((fqn, rec) in repositoryClasses) {
            nodes.add(toRepositoryNode(rec))
            rawLinks.add(GraphLink(fqn, rec.targetEntity, Relation.USES_ENTITY, 1, null))
        }

        // 약한 ID 참조(`userId: Long` → User) 를 SOFT_REF 엣지로 — 진짜 FK 가 없는 MSA 경계 참조 포착.
        rawLinks.addAll(SoftRefHeuristic.extractLinks(columnsByFqn, entityClasses.keys))

        // mappedBy 측 엣지(label 에 "mappedBy=") 는 비주인 측이라 제거
        val links = rawLinks.filterNot { it.label?.contains("mappedBy=") == true }

        // 노드에 논리 모듈 부여 — IDE/Gradle 모듈명 우선, 단일 모듈이면 패키지 세그먼트 폴백.
        val (nodesWithModule, realModuleCount) = assignModules(nodes, entityClasses, repositoryClasses)
        val moduleByFqn = nodesWithModule.associate { it.id to it.module }

        // 엣지 경계 3종 분류 (INTRA / CROSS_FK / CROSS_SOFT).
        val classified = links.map { l ->
            l.copy(boundary = ArchitectureDetector.classifyBoundary(moduleByFqn[l.source], moduleByFqn[l.target], l.relation))
        }
        // 약한 참조는 기본적으로 모듈 경계를 넘는 것(CROSS_SOFT)만 노출 — intra/미상은 노이즈라 숨긴다.
        val linksWithBoundary = classified.filterNot {
            it.relation == Relation.SOFT_REF && it.boundary != EdgeBoundary.CROSS_SOFT
        }

        // 아키텍처 모드 추론.
        val moduleNames = nodesWithModule.mapNotNull { it.module }.distinct().sorted()
        val hasCrossModuleJpaEdge = linksWithBoundary.any {
            it.boundary == EdgeBoundary.CROSS_FK && ArchitectureDetector.isRealJpaRelation(it.relation)
        }
        val architecture = ArchitectureDetector.detect(
            moduleCount = moduleNames.size,
            realModuleCount = realModuleCount,
            settingsFileCount = countGradleSettingsFiles(),
            hasCrossModuleJpaEdge = hasCrossModuleJpaEdge
        )

        GraphData(
            seed = "", depth = 0,
            nodes = nodesWithModule, links = linksWithBoundary,
            architecture = architecture, modules = moduleNames
        )
    }.executeSynchronously()

    /**
     * 프로젝트 내 `settings.gradle(.kts)` 파일 수 — 아키텍처 감지의 1차 신호(빌드 경계 프록시).
     * 별도 settings 가 둘 이상이면 별도 빌드/배포 단위(= MSA), 하나면 단일 빌드(`include`)로 본다.
     * Gradle 플러그인 API 의존 없이 인덱스로만 센다 (build/ 등 제외된 디렉터리는 projectScope 에서 빠짐).
     */
    private fun countGradleSettingsFiles(): Int {
        val scope = GlobalSearchScope.projectScope(project)
        return listOf("settings.gradle", "settings.gradle.kts")
            .sumOf { FilenameIndex.getVirtualFilesByName(it, scope).size }
    }

    /** [assignModules] 결과 — 모듈 부여된 노드 + 실제 IDE/빌드 모듈 수(아키텍처 판정 신호). */
    private data class ModuleAssignment(val nodes: List<GraphNode>, val realModuleCount: Int)

    /**
     * [ModuleResolver] 로 각 노드의 [GraphNode.module] 를 채운다.
     *
     * IDE 모듈명은 클래스의 소스 파일이 속한 [Module] 에서 얻는다 (라이브러리/생성 소스 등으로
     * VirtualFile 이 없으면 null → 패키지 세그먼트 폴백). read action 안에서 호출돼야 한다.
     *
     * 함께 산출하는 [ModuleAssignment.realModuleCount] 는 패키지 폴백을 제외한 "진짜" IDE/빌드 모듈
     * 수로, 패키지로만 나뉜 모놀리스를 모듈러 모놀리스로 오판하지 않도록 [ArchitectureDetector] 에 넘긴다.
     */
    private fun assignModules(
        nodes: List<GraphNode>,
        entityClasses: Map<String, EntityRecord>,
        repositoryClasses: Map<String, RepositoryRecord>
    ): ModuleAssignment {
        val fileIndex = ProjectFileIndex.getInstance(project)
        fun ideModuleName(u: UClass): String? {
            val vFile = u.javaPsi.containingFile?.virtualFile ?: return null
            return fileIndex.getModuleForFile(vFile)?.name
        }

        val ideModuleByFqn = HashMap<String, String?>()
        for ((fqn, rec) in entityClasses) ideModuleByFqn[fqn] = ideModuleName(rec.uClass)
        for ((fqn, rec) in repositoryClasses) ideModuleByFqn[fqn] = ideModuleName(rec.uClass)
        val pkgByFqn = nodes.associate { it.id to it.pkg }

        val moduleByFqn = ModuleResolver.assignModules(ideModuleByFqn, pkgByFqn)
        val realModuleCount = ModuleResolver.distinctRealModules(ideModuleByFqn).size
        return ModuleAssignment(nodes.map { it.copy(module = moduleByFqn[it.id]) }, realModuleCount)
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
        val relations: List<GraphLink>,
        val constraints: RawTableConstraints
    )

    /**
     * Kotlin 주생성자 프로퍼티(`class X(@Id val id: Long)`)는 use-site target 미지정 시 JPA 어노테이션이
     * backing field 가 아니라 **주생성자 파라미터** 에 실린다. `UClass.fields` 만 보면 통째로 놓치므로
     * 파라미터명 → 어노테이션 매핑을 만들어 field 어노테이션과 병합한다.
     * (Java / 일반 Kotlin 바디 필드는 어노테이션 있는 생성자 파라미터가 없어 빈 맵 → 무영향.)
     */
    private fun constructorParamAnnotations(c: UClass): Map<String, List<UAnnotation>> =
        c.methods.asSequence()
            .filter { it.isConstructor }
            .flatMap { it.uastParameters.asSequence() }
            .filter { it.uAnnotations.isNotEmpty() }
            .groupBy({ it.name }, { it.uAnnotations })
            .mapValues { (_, lists) -> lists.flatten() }

    /** field 자체 어노테이션 + (Kotlin 주생성자 프로퍼티면) 매칭 파라미터 어노테이션. */
    private fun UField.effectiveAnnotations(paramAnns: Map<String, List<UAnnotation>>): List<UAnnotation> =
        uAnnotations + paramAnns[name].orEmpty()

    private fun extractFieldsAndRelations(
        c: UClass,
        knownEntityFqns: Set<String>
    ): FieldExtraction {
        val constraints = extractTableConstraints(c)
        val owner = c.qualifiedName ?: return FieldExtraction(emptyList(), emptyList(), constraints)
        val columns = mutableListOf<ColumnInfo>()
        val relations = mutableListOf<GraphLink>()

        // 클래스 레벨 @Table 의 indexes / uniqueConstraints 의 단일 컬럼 그룹만 lowercase set 으로 변환 —
        // 그룹에는 원본 케이스로 저장돼 있어 buildColumn 매칭 시점에 정규화.
        val indexedCols = constraints.singleColumnIndexes()
        val uniqueCols = constraints.singleColumnUniques()
        // @SequenceGenerator 모음 — buildColumn 에서 generator name 매칭에 사용.
        val sequenceGenerators = collectSequenceGenerators(c)

        // Kotlin 주생성자 프로퍼티의 어노테이션은 파라미터에 실리므로 미리 모아 field 와 병합한다.
        val paramAnns = constructorParamAnnotations(c)
        for (f in c.fields) {
            // static (= Java static / Kotlin companion 등) 은 컬럼 아님
            val psiField = f.javaPsi as? PsiField
            if (psiField?.hasModifierProperty(PsiModifier.STATIC) == true) continue
            val anns = f.effectiveAnnotations(paramAnns)
            if (anns.any { it.qualifiedName in JpaAnnotations.TRANSIENT }) continue
            // @ElementCollection 은 별도 컬렉션 테이블로 매핑됨 — 호스트 테이블 컬럼이 아니므로 스킵.
            // (컬렉션 테이블 자체는 아직 미모델링. 최소한 잘못된 VARCHAR 컬럼이 생기지 않게.)
            if (anns.any { it.qualifiedName in JpaAnnotations.ELEMENT_COLLECTION }) continue
            // @EmbeddedId — @Embeddable PK 클래스의 필드들을 복합 PK 컬럼으로 펼침.
            if (anns.any { it.qualifiedName in JpaAnnotations.EMBEDDED_ID }) {
                columns.addAll(expandEmbedded(f, indexedCols, uniqueCols, sequenceGenerators, asPrimaryKey = true))
                continue
            }
            // @Embedded — @Embeddable 의 필드들을 호스트 테이블에 인라인으로 펼침.
            if (anns.any { it.qualifiedName in JpaAnnotations.EMBEDDED }) {
                columns.addAll(expandEmbedded(f, indexedCols, uniqueCols, sequenceGenerators))
                continue
            }

            val relAnnotation = anns.firstOrNull { it.qualifiedName in JpaAnnotations.RELATION_ANNOTATIONS }
            if (relAnnotation != null) {
                val link = buildRelationLink(owner, f, anns, relAnnotation, knownEntityFqns)
                if (link != null) {
                    relations.add(link)
                    // owning side ManyToOne/OneToOne 은 실제 DB 에 FK 컬럼이 생기므로 표 표기용
                    // ColumnInfo 도 함께 emit. (OneToMany mappedBy / ManyToMany join table 은 제외)
                    buildFkColumn(f, anns, relAnnotation, link)?.let { columns.add(it) }
                }
                continue
            }
            columns.add(buildColumn(f, anns, indexedCols, uniqueCols, sequenceGenerators))
        }
        return FieldExtraction(columns, relations, constraints)
    }

    /**
     * owning side relation 필드를 FK 컬럼으로 변환.
     *
     *  - ManyToOne: 항상 owning. FK 가 이쪽에.
     *  - OneToOne: mappedBy 없으면 owning. (mappedBy 있으면 inverse 라 FK 반대편)
     *  - OneToMany / ManyToMany: 이쪽에 FK 컬럼이 안 생기므로 null.
     *
     * 컬럼명: `@JoinColumn(name=...)` 명시값 → 없으면 JPA 기본 `fieldName + "_id"`.
     * nullable / unique 는 @JoinColumn 속성 그대로.
     */
    private fun buildFkColumn(f: UField, annotations: List<UAnnotation>, relAnn: UAnnotation, link: GraphLink): ColumnInfo? {
        val qn = relAnn.qualifiedName
        val owningRelation = when {
            qn in JpaAnnotations.MANY_TO_ONE -> true
            qn in JpaAnnotations.ONE_TO_ONE -> relAnn.stringAttr("mappedBy").isNullOrBlank()
            else -> false
        }
        if (!owningRelation) return null

        val joinCol = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_COLUMN }
        // @JoinColumn(name="") (name 미지정) 도 빈 문자열 → null 정규화 후 JPA 기본 `필드명_id`.
        val explicitName = joinCol?.stringAttr("name")?.takeIf { it.isNotBlank() }
        val fkName = explicitName ?: "${f.name}_id"
        // 명시적 @JoinColumn(nullable=...) 이 최우선. 미지정이면 관계 필드 타입의 null 가능성으로 추론.
        val nullable = joinCol?.declaredBoolAttr("nullable") ?: !isNonNullType(f)
        val unique = joinCol?.boolAttr("unique") ?: (qn in JpaAnnotations.ONE_TO_ONE)

        return ColumnInfo(
            fieldName = f.name,
            columnName = fkName,
            // 명시 @JoinColumn(name) 은 verbatim, 파생 `필드명_id` 는 snake_case 변환 대상.
            columnNameExplicit = explicitName != null,
            javaType = f.type.canonicalText,
            primaryKey = false,
            nullable = nullable,
            unique = unique,
            indexed = false,
            foreignKey = true,
            fkTarget = link.target,
            length = null,
            generatedValue = null
        )
    }

    /**
     * @Table 의 indexes / uniqueConstraints 에서 컬럼 그룹을 보존한 형태로 추출.
     *
     *  - indexes: `@Index(columnList="email" | "a, b, c desc")` → [["email"], ["a","b","c"]]
     *  - uniqueConstraints: `@UniqueConstraint(columnNames={"email"})` 또는 `{"a","b"}` 의 배열
     *
     * 단일 컬럼 그룹은 ColumnInfo.indexed/unique 플래그로도 표시 (column-level 표기용),
     * 다중 컬럼 그룹은 EntityInfo.compositeIndexes/compositeUniques 로 별도 보존.
     */
    private data class RawTableConstraints(
        val indexes: List<List<String>>,
        val uniqueConstraints: List<List<String>>
    ) {
        /** 단일 컬럼 인덱스 그룹의 컬럼명만 lowercase set 으로 — column-level 플래그 매칭용. */
        fun singleColumnIndexes(): Set<String> =
            indexes.filter { it.size == 1 }.mapTo(mutableSetOf()) { it[0].lowercase() }
        fun singleColumnUniques(): Set<String> =
            uniqueConstraints.filter { it.size == 1 }.mapTo(mutableSetOf()) { it[0].lowercase() }
        /** 다중 컬럼 그룹만 — EntityInfo composite 필드용. 원본 케이스 그대로. */
        fun compositeIndexes(): List<List<String>> = indexes.filter { it.size > 1 }
        fun compositeUniques(): List<List<String>> = uniqueConstraints.filter { it.size > 1 }
    }

    private fun extractTableConstraints(c: UClass): RawTableConstraints {
        val tableAnn = c.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.TABLE }
        val psi = tableAnn?.javaPsi as? PsiAnnotation ?: return RawTableConstraints(emptyList(), emptyList())

        val indexes = mutableListOf<List<String>>()
        val uniques = mutableListOf<List<String>>()

        forEachNestedAnnotation(psi.findAttributeValue("indexes")) { ann ->
            val colList = (ann.findAttributeValue("columnList") as? PsiLiteralExpression)?.value as? String
                ?: return@forEachNestedAnnotation
            val cols = parseColumnList(colList)
            if (cols.isNotEmpty()) indexes.add(cols)
        }

        forEachNestedAnnotation(psi.findAttributeValue("uniqueConstraints")) { ann ->
            val cols = mutableListOf<String>()
            collectStringList(ann.findAttributeValue("columnNames"), cols)
            if (cols.isNotEmpty()) uniques.add(cols)
        }

        return RawTableConstraints(indexes, uniques)
    }

    /**
     * 클래스 / 필드 레벨 `@SequenceGenerator` 를 모아 (generator name → sequenceName) 맵을 만든다.
     * generator name 이 비어있으면 sequenceName 자체를 키로 사용.
     */
    private fun collectSequenceGenerators(c: UClass): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fun consume(ann: UAnnotation) {
            val name = ann.stringAttr("name") ?: return
            val seq = ann.stringAttr("sequenceName") ?: name
            map[name] = seq
        }
        for (ann in c.uAnnotations) {
            if (ann.qualifiedName in JpaAnnotations.SEQUENCE_GENERATOR) consume(ann)
        }
        for (f in c.fields) {
            for (ann in f.uAnnotations) {
                if (ann.qualifiedName in JpaAnnotations.SEQUENCE_GENERATOR) consume(ann)
            }
        }
        return map
    }

    /** 배열 또는 단일 어노테이션 어느 쪽이든 element 어노테이션을 순회. */
    private inline fun forEachNestedAnnotation(value: PsiAnnotationMemberValue?, block: (PsiAnnotation) -> Unit) {
        when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.forEach { (it as? PsiAnnotation)?.let(block) }
            is PsiAnnotation -> block(value)
            else -> Unit
        }
    }

    /**
     * 배열 또는 단일 문자열을 원본 케이스 그대로 순서 유지하며 [out] 에 추가.
     * 케이스 정규화는 비교 시점(buildColumn)에서만 수행 — 그래야 복합 그룹의 원본 케이스가 보존되어
     * DDL 의 snake_case 변환이 컬럼명과 일관되게 적용됨.
     */
    private fun collectStringList(value: PsiAnnotationMemberValue?, out: MutableList<String>) {
        when (value) {
            is PsiArrayInitializerMemberValue -> value.initializers.forEach {
                ((it as? PsiLiteralExpression)?.value as? String)?.let { s -> out.add(s) }
            }
            is PsiLiteralExpression -> (value.value as? String)?.let { out.add(it) }
            else -> Unit
        }
    }

    /** `"a, b DESC, c"` → ["a", "b", "c"] — 원본 케이스 보존. */
    private fun parseColumnList(s: String): List<String> =
        s.split(",")
            .map { it.trim().substringBefore(' ') }
            .filter { it.isNotEmpty() }

    /**
     * `@Embedded` / `@EmbeddedId` 필드를 그 [Embeddable] 타입의 컬럼들로 펼친다 (호스트 테이블에 인라인).
     * embeddable 안의 필드도 `@Column` / `@Enumerated` 등 일반 컬럼 규칙을 그대로 따르므로
     * [buildColumn] 을 재사용. static / `@Transient` 필드는 제외. (@AttributeOverride 는 미지원.)
     *
     * @param asPrimaryKey `@EmbeddedId` 면 true — 펼친 컬럼 전부를 복합 PK(primaryKey, NOT NULL)로 표시.
     */
    private fun expandEmbedded(
        f: UField,
        indexedCols: Set<String>,
        uniqueCols: Set<String>,
        sequenceGenerators: Map<String, String>,
        asPrimaryKey: Boolean = false
    ): List<ColumnInfo> {
        val embeddable = (f.type as? PsiClassType)?.resolve()?.toUElementOfType<UClass>() ?: return emptyList()
        // @Embeddable 도 주생성자 프로퍼티를 쓸 수 있으므로 동일하게 파라미터 어노테이션을 병합.
        val embeddableParamAnns = constructorParamAnnotations(embeddable)
        return embeddable.fields.mapNotNull { ef ->
            val psi = ef.javaPsi as? PsiField
            if (psi?.hasModifierProperty(PsiModifier.STATIC) == true) return@mapNotNull null
            val efAnns = ef.effectiveAnnotations(embeddableParamAnns)
            if (efAnns.any { it.qualifiedName in JpaAnnotations.TRANSIENT }) return@mapNotNull null
            val col = buildColumn(ef, efAnns, indexedCols, uniqueCols, sequenceGenerators)
            if (asPrimaryKey) col.copy(primaryKey = true, nullable = false) else col
        }
    }

    private fun buildColumn(
        f: UField,
        annotations: List<UAnnotation>,
        indexedCols: Set<String>,
        uniqueCols: Set<String>,
        sequenceGenerators: Map<String, String>
    ): ColumnInfo {
        val isPk = annotations.any { it.qualifiedName in JpaAnnotations.ID }
        val columnAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.COLUMN }
        val generatedAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.GENERATED_VALUE }

        // @Column(name="") (name 미지정) 은 빈 문자열로 들어오므로 null 로 정규화 — JPA 규약상 필드명 사용.
        val columnName = columnAnn?.stringAttr("name")?.takeIf { it.isNotBlank() }
        // 명시적 @Column(nullable=...) 이 최우선. 미지정이면 필드 타입의 null 가능성으로 추론.
        val nullable = columnAnn?.declaredBoolAttr("nullable") ?: !isNonNullType(f)
        val columnUnique = columnAnn?.boolAttr("unique") ?: false
        val length = columnAnn?.intAttr("length")
        val precision = columnAnn?.intAttr("precision")
        val scale = columnAnn?.intAttr("scale")
        val columnDefinition = columnAnn?.stringAttr("columnDefinition")?.takeIf { it.isNotBlank() }
        val isLob = annotations.any { it.qualifiedName in JpaAnnotations.LOB }

        // enum 컬럼 — @Enumerated 명시값 우선, 없어도 필드 타입이 enum 이면 JPA 기본 ORDINAL.
        val enumAnn = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.ENUMERATED }
        val enumType: String? = when {
            enumAnn != null -> enumAnn.findAttributeValue("value")?.let { extractEnumName(it) } ?: "ORDINAL"
            isEnumType(f.type) -> "ORDINAL"
            else -> null
        }
        // @Temporal — java.util.Date/Calendar 의 DATE/TIME/TIMESTAMP 구분.
        val temporalType: String? = annotations.firstOrNull { it.qualifiedName in JpaAnnotations.TEMPORAL }
            ?.findAttributeValue("value")?.let { extractEnumName(it) }

        // GeneratedValue.strategy 는 enum reference — UReferenceExpression 의 resolvedName 사용
        val strategy = generatedAnn?.findAttributeValue("strategy")?.let { extractEnumName(it) }

        // SEQUENCE 전략일 때 generator 이름으로 @SequenceGenerator 룩업. 없으면 Hibernate 기본 이름.
        val sequenceName: String? = if (strategy?.uppercase() == "SEQUENCE") {
            val generatorName = generatedAnn?.stringAttr("generator")
            if (!generatorName.isNullOrBlank()) sequenceGenerators[generatorName] ?: generatorName
            else "hibernate_sequence"
        } else null

        val dbName = (columnName ?: f.name).lowercase()
        val tableUnique = dbName in uniqueCols
        val indexed = dbName in indexedCols

        return ColumnInfo(
            fieldName = f.name,
            columnName = columnName ?: f.name,
            // 명시 @Column(name) 여부 — DDL 에서 명시 이름은 verbatim, 파생(fieldName)은 snake_case 변환.
            columnNameExplicit = columnName != null,
            javaType = f.type.canonicalText,
            primaryKey = isPk,
            nullable = nullable,
            unique = columnUnique || tableUnique,
            indexed = indexed,
            length = length,
            generatedValue = strategy,
            precision = precision,
            scale = scale,
            lob = isLob,
            sequenceName = sequenceName,
            enumType = enumType,
            temporalType = temporalType,
            columnDefinition = columnDefinition
        )
    }

    /** 필드 타입이 enum 클래스인지 — `@Enumerated` 없는 enum 의 기본 매핑(ORDINAL) 판단용. */
    private fun isEnumType(type: PsiType): Boolean =
        (type as? PsiClassType)?.resolve()?.isEnum == true

    private fun buildRelationLink(
        owner: String,
        f: UField,
        annotations: List<UAnnotation>,
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

        val target = relationTargetFqn(f, relAnn, knownEntityFqns) ?: return null
        if (target !in knownEntityFqns) return null

        val mappedBy = relAnn.stringAttr("mappedBy")
        val label = buildRelationLabel(f, mappedBy)

        // MANY_TO_MANY owning side(mappedBy 없음)에서만 조인 테이블 메타를 실어 DDL 이 한 번만 생성.
        val owningManyToMany = relation == Relation.MANY_TO_MANY && mappedBy.isNullOrBlank()
        val joinTableAnn = if (owningManyToMany) {
            annotations.firstOrNull { it.qualifiedName in JpaAnnotations.JOIN_TABLE }
        } else null
        val joinTableName = joinTableAnn?.stringAttr("name")?.takeIf { it.isNotBlank() }
        val joinColumnName = firstJoinColumnName(joinTableAnn, "joinColumns")
        val inverseJoinColumnName = firstJoinColumnName(joinTableAnn, "inverseJoinColumns")

        return GraphLink(
            owner, target, relation, 1, label,
            owningManyToMany, joinTableName, joinColumnName, inverseJoinColumnName
        )
    }

    /** `@JoinTable` 의 joinColumns / inverseJoinColumns 배열에서 첫 `@JoinColumn(name=...)` 을 추출. */
    private fun firstJoinColumnName(joinTableAnn: UAnnotation?, attr: String): String? {
        val psi = joinTableAnn?.javaPsi as? PsiAnnotation ?: return null
        var name: String? = null
        forEachNestedAnnotation(psi.findAttributeValue(attr)) { jc ->
            if (name == null) name = (jc.findAttributeValue("name") as? PsiLiteralExpression)?.value as? String
        }
        return name?.takeIf { it.isNotBlank() }
    }

    private fun relationTargetFqn(f: UField, relAnn: UAnnotation, known: Set<String>): String? {
        // targetEntity = Foo.class 가 명시되면 우선
        val targetClassLit = relAnn.findAttributeValue("targetEntity") as? UClassLiteralExpression
        val targetType = targetClassLit?.type as? PsiClassType
        targetType?.resolve()?.qualifiedName?.let { fqn ->
            if (fqn != "java.lang.Void" && fqn != "void") return fqn
        }

        val type = f.type as? PsiClassType ?: return null
        // 컬렉션이면 첫 type argument(요소 타입), 아니면 필드 타입 자체.
        val elementType: PsiClassType = if (isCollectionLikeType(type)) {
            type.parameters.firstOrNull() as? PsiClassType ?: return null
        } else {
            type
        }
        // 정상 resolve 우선. 실패 시(상호참조 forward reference 등) short name 으로 known 보강.
        elementType.resolve()?.qualifiedName?.let { return it }
        val short = elementType.className ?: return null
        return known.firstOrNull { it.substringAfterLast('.') == short }
    }

    /**
     * 필드 타입이 컬렉션류인지. resolve 성공 시 [isCollectionLike] 로 정확 판정,
     * 실패 시(mockJDK 에서 java.util.* 미해결 등) raw simple name 으로 보강 판정.
     */
    private fun isCollectionLikeType(type: PsiClassType): Boolean {
        type.resolve()?.let { return isCollectionLike(it) }
        return type.className in COLLECTION_SIMPLE_NAMES
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

    private fun toEntityNode(
        rec: EntityRecord,
        columns: List<ColumnInfo>,
        constraints: RawTableConstraints
    ): GraphNode {
        val u = rec.uClass
        val psi = u.javaPsi
        val tableAnn = u.uAnnotations.firstOrNull { it.qualifiedName in JpaAnnotations.TABLE }
        // name/schema 미지정은 빈 문자열로 들어오므로 null 정규화 (JPA 규약상 각각 기본값 사용).
        val tableName = tableAnn?.stringAttr("name")?.takeIf { it.isNotBlank() }
        val schema = tableAnn?.stringAttr("schema")?.takeIf { it.isNotBlank() }
        return GraphNode(
            id = u.qualifiedName ?: u.name ?: "",
            name = u.name ?: psi.name ?: "",
            pkg = packageOf(psi),
            kind = if (psi.isInterface) "interface" else "class",
            stereotypes = emptyList(),
            entity = EntityInfo(
                kind = rec.kind.jsonValue,
                tableName = tableName,
                schema = schema,
                columns = columns,
                inheritance = extractInheritance(u),
                compositeIndexes = constraints.compositeIndexes(),
                compositeUniques = constraints.compositeUniques()
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

    /**
     * **명시적으로 선언된** boolean 속성만 읽는다 (annotation default 와 구분).
     *
     * `findAttributeValue` 는 미선언 시 정의부 default 를 돌려주므로 `@Column(nullable=false)`
     * 와 "@Column 만 붙고 nullable 미지정" 을 구분할 수 없다. nullability 추론(아래 [isNonNullType])
     * 은 사용자가 직접 nullable 을 적었을 때만 그 값을 따라야 하므로 declared 값이 필요하다.
     */
    private fun UAnnotation.declaredBoolAttr(name: String): Boolean? =
        findDeclaredAttributeValue(name)?.evaluate() as? Boolean

    /**
     * 필드 타입이 non-null 이라 NOT NULL 로 봐야 하는가.
     *
     *  - JVM primitive (Java `int`/`long`…, Kotlin non-null `Int`/`Long`…) 은 본질적으로 non-null.
     *  - Kotlin non-null 참조 타입(`String`) 은 light class 가 타입에 `@NotNull` 을 부여한다.
     *  - Java 참조 타입 / Kotlin nullable(`String?`) 은 `@NotNull` 이 없어 nullable 로 본다.
     */
    private fun isNonNullType(f: UField): Boolean {
        val type = f.type
        if (type is PsiPrimitiveType) return true
        val typeAnns = type.annotations.asSequence()
        val fieldAnns = (f.javaPsi as? PsiField)?.annotations?.asSequence() ?: emptySequence()
        return (typeAnns + fieldAnns).any { it.qualifiedName?.substringAfterLast('.') == "NotNull" }
    }

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
