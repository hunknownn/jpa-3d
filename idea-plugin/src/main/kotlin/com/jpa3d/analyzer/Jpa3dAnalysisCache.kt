package com.jpa3d.analyzer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.jpa3d.model.GraphData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트별로 한 번만 분석을 돌리고 결과를 캐싱한다.
 *
 * - 같은 결과를 여러 bridge 요청이 공유 → 첫 호출 이후엔 즉시 응답.
 * - [PsiTreeChangeAdapter] 로 Java/Kotlin 소스 변경을 감지해 cache 무효화.
 * - 무효화는 dirty flag 만 세움 — 실제 재계산은 다음 [getGraphData] 호출 시 lazy 수행.
 * - 자동 push 는 하지 않는다 (EDT 블로킹 회피). viewer 측 sync 버튼으로 사용자가
 *   원할 때만 재 fetch.
 */
@Service(Service.Level.PROJECT)
class Jpa3dAnalysisCache(private val project: Project) : Disposable {

    private val log = logger<Jpa3dAnalysisCache>()
    private val analyzer = Jpa3dAnalyzer(project)
    private val dirty = AtomicBoolean(true)

    @Volatile
    private var cached: GraphData? = null

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(SourceChangeListener(), this)
    }

    /**
     * 캐시가 stale 이면 재계산. 같은 동기화 블록 안에서 한 스레드만 분석을 돌리고,
     * 그 사이 들어온 다른 호출은 결과를 기다렸다 공유받는다.
     */
    @Synchronized
    fun getGraphData(): GraphData {
        val current = cached
        if (current != null && !dirty.get()) {
            return current
        }
        log.info("cache miss — recomputing")
        val started = System.currentTimeMillis()
        val result = analyzer.analyze()
        val elapsed = System.currentTimeMillis() - started
        log.info("analyze took ${elapsed}ms")
        cached = result
        dirty.set(false)
        return result
    }

    override fun dispose() {
        cached = null
    }

    /**
     * Java/Kotlin 소스 변경 시 dirty 표시. push 는 하지 않는다 —
     * 사용자가 viewer 의 sync 버튼을 누르면 다음 getGraphData 가 재계산.
     */
    private inner class SourceChangeListener : PsiTreeChangeAdapter() {
        override fun childrenChanged(event: PsiTreeChangeEvent) = markIfRelevant(event)
        override fun childAdded(event: PsiTreeChangeEvent) = markIfRelevant(event)
        override fun childRemoved(event: PsiTreeChangeEvent) = markIfRelevant(event)
        override fun childReplaced(event: PsiTreeChangeEvent) = markIfRelevant(event)
        override fun childMoved(event: PsiTreeChangeEvent) = markIfRelevant(event)
        override fun propertyChanged(event: PsiTreeChangeEvent) = markIfRelevant(event)

        private fun markIfRelevant(event: PsiTreeChangeEvent) {
            val file = event.file ?: return
            val name = file.name
            if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")) {
                dirty.set(true)
            }
        }
    }
}
