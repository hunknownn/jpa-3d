package com.jpa3d

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.jpa3d.analyzer.Jpa3dAnalysisCache
import com.jpa3d.model.GraphData
import com.jpa3d.model.GraphLink
import com.jpa3d.model.GraphNode
import com.jpa3d.model.Relation

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
     *  - seed: FQN (scope=seed 일 때 BFS 시작점)
     *  - depth: BFS 깊이 (scope=seed 일 때만)
     *  - level: 1 (관계만) / 2 (+컬럼) / 3 (+Repository)
     *
     * 필터링 순서:
     *  1. level<3 이면 Repository 노드와 USES_ENTITY 엣지 제거
     *  2. scope=seed 면 seed 에서 BFS 로 depth 단계까지 도달 가능한 노드만 유지
     *  3. level<2 이면 entity 의 컬럼을 비움 (entity 메타는 유지해 카드 헤더 색은 보존)
     *  4. 양 끝 노드가 살아남은 엣지만 유지
     */
    private fun handleErd(args: Map<String, Any?>?): String {
        if (DumbService.isDumb(project)) {
            log.info("dumb mode — returning indexing placeholder")
            return INDEXING_ERD_JSON
        }
        val scope = (args?.get("scope") as? String) ?: "all"
        val seed = args?.get("seed") as? String
        val depth = (args?.get("depth") as? Number)?.toInt() ?: 2
        val level = (args?.get("level") as? Number)?.toInt()?.coerceIn(1, 3) ?: 1
        val showExtends = (args?.get("showExtends") as? Boolean) ?: true

        val graph = project.service<Jpa3dAnalysisCache>().getGraphData()
        val filtered = filterGraph(graph, scope, seed, depth, level, showExtends)
        return mapper.writeValueAsString(filtered)
    }

    private fun filterGraph(
        g: GraphData,
        scope: String,
        seed: String?,
        depth: Int,
        level: Int,
        showExtends: Boolean
    ): GraphData {
        // 1) level<3 면 Repository 노드/엣지 제거
        var nodes = if (level < 3) g.nodes.filter { it.entity != null } else g.nodes
        var links = if (level < 3) g.links.filter { it.relation != Relation.USES_ENTITY } else g.links

        // 1-1) 상속 끄면 EXTENDS 엣지 제거. MappedSuperclass 노드는 그 결과로 고립되면
        // 자동으로 시각화에서 외톨이가 되긴 하지만, 일관성을 위해 함께 제거.
        if (!showExtends) {
            links = links.filter { it.relation != Relation.EXTENDS }
            nodes = nodes.filter { it.entity?.kind != "mappedSuperclass" }
        }

        // 2) scope=seed → BFS
        if (scope == "seed" && !seed.isNullOrBlank()) {
            val nodeIds = nodes.map { it.id }.toSet()
            if (seed in nodeIds) {
                val reachable = bfs(seed, links, depth)
                nodes = nodes.filter { it.id in reachable }
            } else {
                // seed 가 필터로 제거됐으면 그래프 비움
                return GraphData(seed = seed, depth = depth, nodes = emptyList(), links = emptyList())
            }
        }

        // 3) level<2 면 컬럼 제거 (entity 메타는 유지 — 카드 색/테이블명 등)
        if (level < 2) {
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

    /** seed 에서 시작해 undirected 로 [maxDepth] 까지 도달 가능한 노드 id 집합. */
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
     */
    private fun handleNavigate(args: Map<String, Any?>?): String {
        val fqn = (args?.get("fqn") as? String)?.trim().orEmpty()
        if (fqn.isEmpty()) return "{\"ok\":false}"
        if (DumbService.isDumb(project)) return "{\"ok\":false,\"reason\":\"dumb\"}"

        // PSI 접근은 read action 안에서. navigate 호출은 EDT 에서.
        val psiClass = com.intellij.openapi.application.ReadAction.compute<com.intellij.psi.PsiClass?, RuntimeException> {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } ?: return "{\"ok\":false,\"reason\":\"not_found\"}"

        ApplicationManager.getApplication().invokeLater {
            if (psiClass.canNavigate()) {
                psiClass.navigate(true)
            }
        }
        return "{\"ok\":true}"
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
