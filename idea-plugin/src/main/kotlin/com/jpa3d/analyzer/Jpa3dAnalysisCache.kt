package com.jpa3d.analyzer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

    /** 백그라운드 재계산이 이미 진행 중인지 — 논블로킹 조회의 중복 트리거 방지. */
    private val computing = AtomicBoolean(false)

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

    /**
     * 논블로킹 조회. **분석 락을 절대 기다리지 않는다** — JCEF 브리지 콜백은 macOS 에서
     * AppKit(네이티브 UI) 스레드에서 실행되므로, 여기서 [getGraphData] 의 무거운 분석을 동기로
     * 기다리면 AppKit 이 막히고, 그걸 필요로 하는 EDT 포커스 호출(NSWindow.isKeyWindow)까지
     * 멈춰 UI 가 프리즈된다(실측 12s). 그래서:
     *  - 캐시가 신선하면 즉시 반환.
     *  - 아니면 백그라운드 풀 스레드에서 (한 번만) 재계산을 트리거하고 즉시 null 을 반환한다.
     * 호출 측(bridge)은 null 이면 indexing 플래그로 응답하고, viewer 가 3초마다 재시도해
     * 준비된 결과를 받는다.
     */
    fun getGraphDataOrNull(): GraphData? {
        val current = cached
        if (current != null && !dirty.get()) return current
        // 아직 계산 중이 아니면 백그라운드에서 시작 (EDT/AppKit 이 아닌 풀 스레드).
        if (computing.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    getGraphData()
                } catch (e: Exception) {
                    log.warn("background analyze failed: ${e.message}", e)
                } finally {
                    computing.set(false)
                }
            }
        }
        return null
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
