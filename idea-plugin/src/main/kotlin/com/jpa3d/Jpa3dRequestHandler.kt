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
import com.jpa3d.model.GraphNode

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
                "erd" -> handleErd()
                "search" -> handleSearch(req.args)
                "navigate" -> handleNavigate(req.args)
                else -> "[]"
            }
        } catch (e: Exception) {
            log.warn("handler failed: ${e.message}", e)
            EMPTY_ERD_JSON
        }
    }

    private fun handleErd(): String {
        if (DumbService.isDumb(project)) {
            log.info("dumb mode — returning empty graph")
            return EMPTY_ERD_JSON
        }
        val graph = project.service<Jpa3dAnalysisCache>().getGraphData()
        return mapper.writeValueAsString(graph)
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
    }
}
