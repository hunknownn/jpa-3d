package com.jpa3d

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import javax.swing.SwingConstants
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphNode
import com.jpa3d.model.GraphScope
import com.jpa3d.model.Relation
import com.jpa3d.settings.Jpa3dSettings

/**
 * viewer → plugin 요청 처리기. JSON 문자열 in → JSON 문자열 out.
 *
 * 요청 종류:
 *  - kind="erd" : 프로젝트 전체 ERD (현재는 scope/level 무시, 전체 그래프 반환)
 *  - kind="search" : 노드 이름 / fqn 부분 매칭 (대소문자 무시)
 *
 * 분석은 [Jpa3dAnalyzer] 가 read action 안에서 수행. dumb mode 에서는 빈 결과를 즉시 반환해
 * UI 가 멈추지 않게 한다.
 */
class Jpa3dRequestHandler(private val project: Project) {

    private val log = logger<Jpa3dRequestHandler>()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

    // === Cmd/Ctrl+클릭 2×2 사분면 그리드 상태 (EDT 에서만 접근) ===
    // 클릭 순서대로 좌상 → 우상 → 좌하 → 우하 4칸을 채우고,
    // 4칸이 차면 같은 순서로 처음(좌상)부터 순환 재사용한다.
    private val gridWindows = arrayOfNulls<EditorWindow>(4) // [0]=좌상 [1]=우상 [2]=좌하 [3]=우하
    private var gridFilled = 0       // 채운 칸 수 (0..4)
    private var gridRoundRobin = 0   // 4칸이 찬 뒤 순환 재사용 인덱스 (0..3)

    fun handle(payload: String): String {
        log.info("bridge request: $payload")
        return try {
            val req: BridgeRequest = mapper.readValue(payload, BridgeRequest::class.java)
            when (req.kind) {
                "erd" -> handleErd(req.args)
                "search" -> handleSearch(req.args)
                "navigate" -> handleNavigate(req.args)
                else -> "[]"
            }
        } catch (e: Exception) {
            log.warn("handler failed: ${e.message}", e)
            EMPTY_ERD_JSON
        }
    }

    /**
     * ERD 그래프 빌드.
     *
     * args:
     *  - scope: "all" | "seed"
     *  - seed: scope=seed 일 때 BFS 시작점 (seedType 에 따라 FQN 또는 패키지)
     *  - seedType: "fqn" (기본) | "package"
     *  - depth: BFS 깊이 (scope=seed 일 때만)
     *  - showColumns: 컬럼 표시 여부 (관계는 항상 표시되는 기본값)
     *  - showRepository: Repository 노드/USES_ENTITY 엣지 표시 여부 (컬럼과 독립)
     *
     * 필터링 순서:
     *  1. showRepository=false 면 Repository 노드와 USES_ENTITY 엣지 제거
     *  2. scope=seed 면 seed(들)에서 BFS 로 depth 단계까지 도달 가능한 노드만 유지
     *  3. showColumns=false 면 entity 의 컬럼을 비움 (entity 메타는 유지해 카드 헤더 색은 보존)
     *  4. 양 끝 노드가 살아남은 엣지만 유지
     */
    private fun handleErd(args: Map<String, Any?>?): String {
        if (DumbService.isDumb(project)) {
            log.info("dumb mode — returning indexing placeholder")
            return INDEXING_ERD_JSON
        }
        val scope = (args?.get("scope") as? String) ?: "all"
        val seed = args?.get("seed") as? String
        val seedType = (args?.get("seedType") as? String) ?: GraphScope.SEED_TYPE_FQN
        val depth = (args?.get("depth") as? Number)?.toInt() ?: 2
        // 관계는 기본 표시. 컬럼/리포지토리는 각각 독립 토글 (둘 다 기본 false).
        val showColumns = (args?.get("showColumns") as? Boolean) ?: false
        val showRepository = (args?.get("showRepository") as? Boolean) ?: false
        val showExtends = (args?.get("showExtends") as? Boolean) ?: true

        val graph = project.service<Jpa3dAnalysisCache>().getGraphData()
        val filtered = filterGraph(graph, scope, seed, seedType, depth, showColumns, showRepository, showExtends)
        return mapper.writeValueAsString(filtered)
    }

    private fun filterGraph(
        g: GraphData,
        scope: String,
        seed: String?,
        seedType: String,
        depth: Int,
        showColumns: Boolean,
        showRepository: Boolean,
        showExtends: Boolean
    ): GraphData {
        // 1) Repository 표시 안 하면 Repository 노드/USES_ENTITY 엣지 제거
        var nodes = if (!showRepository) g.nodes.filter { it.entity != null } else g.nodes
        var links = if (!showRepository) g.links.filter { it.relation != Relation.USES_ENTITY } else g.links

        // 1-1) 상속 끄면 EXTENDS 엣지 제거. MappedSuperclass 노드는 그 결과로 고립되면
        // 자동으로 시각화에서 외톨이가 되긴 하지만, 일관성을 위해 함께 제거.
        if (!showExtends) {
            links = links.filter { it.relation != Relation.EXTENDS }
            nodes = nodes.filter { it.entity?.kind != "mappedSuperclass" }
        }

        // 1-2) 설정(설정 → 도구 → JPA 3D)의 분석 대상 패키지 필터 적용.
        // 요청 시점에 적용하므로 설정 변경이 다음 동기화/요청에 즉시 반영된다(캐시 불변).
        val settings = Jpa3dSettings.getInstance().state
        nodes = applyPackageFilter(nodes, settings.includePackages, settings.excludePackages)

        // 2) scope=seed → 멀티 소스 BFS (FQN=단일, package=해당 패키지 엔티티 전부가 출발점)
        if (scope == "seed" && !seed.isNullOrBlank()) {
            val seeds = GraphScope.resolveSeeds(nodes, seedType, seed)
            if (seeds.isEmpty()) {
                // seed 매칭 노드가 없으면(필터로 제거됐거나 빈 패키지) 그래프 비움
                return GraphData(seed = seed, depth = depth, nodes = emptyList(), links = emptyList())
            }
            val reachable = GraphScope.reachable(seeds, links, depth)
            nodes = nodes.filter { it.id in reachable }
        }

        // 3) 컬럼 표시 안 하면 컬럼 제거 (entity 메타는 유지 — 카드 색/테이블명 등)
        if (!showColumns) {
            nodes = nodes.map { n ->
                val e = n.entity
                if (e == null) n else n.copy(entity = e.copy(columns = emptyList()))
            }
        }

        // 4) 양 끝 노드가 살아남은 엣지만
        val keep = nodes.map { it.id }.toSet()
        links = links.filter { it.source in keep && it.target in keep }

        return GraphData(seed = seed.orEmpty(), depth = depth, nodes = nodes, links = links)
    }

    /**
     * include/exclude 패키지 패턴으로 노드를 거른다(접두 매칭, [GraphScope] 와 동일 규칙).
     *  - include 가 비면 전체 허용, 아니면 매칭되는 노드만.
     *  - exclude 매칭은 include 보다 우선해 항상 제거.
     * 두 패턴이 모두 비면 입력을 그대로 돌려준다.
     */
    private fun applyPackageFilter(
        nodes: List<GraphNode>,
        include: String,
        exclude: String
    ): List<GraphNode> {
        val inc = parsePackagePatterns(include)
        val exc = parsePackagePatterns(exclude)
        if (inc.isEmpty() && exc.isEmpty()) return nodes
        fun matches(pkg: String, p: String) = pkg == p || pkg.startsWith("$p.")
        return nodes.filter { n ->
            val included = inc.isEmpty() || inc.any { matches(n.pkg, it) }
            val excluded = exc.any { matches(n.pkg, it) }
            included && !excluded
        }
    }

    /** 줄바꿈/쉼표로 구분된 패키지 목록을 trim + 빈 줄 제거해 파싱. */
    private fun parsePackagePatterns(raw: String): List<String> =
        raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun handleSearch(args: Map<String, Any?>?): String {
        if (DumbService.isDumb(project)) return "[]"
        val q = (args?.get("q") as? String)?.trim().orEmpty()
        if (q.isEmpty()) return "[]"
        val includeRepositories = (args?.get("includeRepositories") as? Boolean) ?: true

        val graph: GraphData = project.service<Jpa3dAnalysisCache>().getGraphData()
        val needle = q.lowercase()
        val hits: List<GraphNode> = graph.nodes.filter { n ->
            (includeRepositories || n.entity != null) &&
                (n.name.lowercase().contains(needle) || n.id.lowercase().contains(needle))
        }
        return mapper.writeValueAsString(hits)
    }

    /**
     * 노드 클릭 시 IDE 의 해당 소스로 점프.
     *
     * - FQN 으로 [PsiClass] 조회 (read action) → EDT 에서 navigate.
     * - dumb mode 에선 인덱스 없이 못 찾을 수 있으니 그 경우 그냥 무시.
     * - args.split == true (Cmd/Ctrl+클릭) 면 에디터를 세로 분할한 새 탭에 연다.
     */
    private fun handleNavigate(args: Map<String, Any?>?): String {
        val fqn = (args?.get("fqn") as? String)?.trim().orEmpty()
        if (fqn.isEmpty()) return "{\"ok\":false}"
        if (DumbService.isDumb(project)) return "{\"ok\":false,\"reason\":\"dumb\"}"
        val split = args?.get("split") == true

        // PSI 접근은 read action 안에서. navigate 호출은 EDT 에서.
        val psiClass = ReadAction.compute<PsiClass?, RuntimeException> {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } ?: return "{\"ok\":false,\"reason\":\"not_found\"}"

        // 분할 모드면 먼저 열어야 할 파일(virtualFile)도 read action 으로 확보.
        val virtualFile: VirtualFile? = if (split) {
            ReadAction.compute<VirtualFile?, RuntimeException> {
                psiClass.containingFile?.virtualFile
            }
        } else null

        ApplicationManager.getApplication().invokeLater {
            if (!psiClass.canNavigate()) return@invokeLater
            if (split && virtualFile != null) {
                // 2×2 사분면 그리드로 배치(분할/순환 재사용) — 내부에서 navigate 까지 수행.
                openInGrid(psiClass, virtualFile)
            } else {
                psiClass.navigate(true)
            }
        }
        return "{\"ok\":true}"
    }

    /**
     * Cmd/Ctrl+클릭 시 2×2 사분면 그리드로 [psiClass] 소스를 배치한다. (EDT 에서 호출)
     *
     * 채우는 순서(클릭순): 좌상 → 우상 → 좌하 → 우하.
     *  - 좌상: 현재 창에 그대로 연다(분할 없음).
     *  - 우상: 좌상을 세로 분할. (세로 분할은 한 번뿐 → 좌/우 폭 50:50 균등)
     *  - 좌하: 좌상을 가로 분할.
     *  - 우하: 우상을 가로 분할 → 2×2 균등 완성.
     * 4칸이 차면 같은 순서(좌상부터)로 분할 없이 순환 재사용한다.
     * 저장된 창이 닫혀 있으면 그리드 상태를 새로 시작한다.
     */
    private fun openInGrid(psiClass: PsiClass, virtualFile: VirtualFile) {
        val fem = FileEditorManagerEx.getInstanceEx(project)
        val live = fem.windows.toHashSet()
        fun alive(w: EditorWindow?): Boolean = w != null && w in live

        // 채운 칸 중 하나라도 닫혔으면 그리드를 리셋하고 처음부터 다시 구성.
        if ((0 until gridFilled).any { !alive(gridWindows[it]) }) resetGrid()

        when (gridFilled) {
            0 -> {
                // 좌상: 현재(또는 새로 생기는) 창에 열고 그 창을 기록.
                psiClass.navigate(true)
                gridWindows[0] = fem.currentWindow
                gridFilled = 1
                return
            }
            1 -> gridWindows[1] = gridWindows[0]?.split(SwingConstants.VERTICAL, true, virtualFile, true)   // 우상
            2 -> gridWindows[2] = gridWindows[0]?.split(SwingConstants.HORIZONTAL, true, virtualFile, true) // 좌하
            3 -> gridWindows[3] = gridWindows[1]?.split(SwingConstants.HORIZONTAL, true, virtualFile, true) // 우하
            else -> {
                // 4칸 꽉 참 → 좌상부터 같은 순서로 순환 재사용.
                val target = gridWindows[gridRoundRobin]
                gridRoundRobin = (gridRoundRobin + 1) % gridWindows.size
                if (alive(target)) fem.currentWindow = target
                psiClass.navigate(true)
                return
            }
        }
        if (gridFilled < 4) gridFilled++
        // 분할로 연 새 창에서 해당 클래스 위치로 스크롤.
        psiClass.navigate(true)
    }

    /** 분할 그리드 상태 초기화. */
    private fun resetGrid() {
        gridWindows.fill(null)
        gridFilled = 0
        gridRoundRobin = 0
    }

    /** 브리지 페이로드: `{"kind":"...","args":{...}}` */
    data class BridgeRequest(
        val kind: String = "",
        val args: Map<String, Any?>? = null
    )

    companion object {
        private const val EMPTY_ERD_JSON = """{"seed":"","depth":0,"nodes":[],"links":[]}"""
        // dumb mode: viewer 가 "분석 중" 표시 + 자동 retry 하도록 indexing 플래그
        private const val INDEXING_ERD_JSON = """{"seed":"","depth":0,"nodes":[],"links":[],"indexing":true}"""
    }
}
