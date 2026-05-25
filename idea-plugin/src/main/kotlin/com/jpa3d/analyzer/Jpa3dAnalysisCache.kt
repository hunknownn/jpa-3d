package com.jpa3d.analyzer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.jpa3d.model.GraphData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트별로 한 번만 분석을 돌리고 결과를 캐싱한다.
 *
 * - 같은 결과를 여러 bridge 요청이 공유 → 첫 호출 이후엔 즉시 응답.
 * - [PsiTreeChangeAdapter] 로 Java/Kotlin 소스 변경을 감지해 cache 무효화.
 * - 무효화는 dirty flag 만 세움 — 실제 재계산은 다음 [getGraphData] 호출 시 lazy 수행.
 *
 * 사용:
 * ```
 * val cache = project.getService(Jpa3dAnalysisCache::class.java)
 * val graph = cache.getGraphData()
 * ```
 */
@Service(Service.Level.PROJECT)
class Jpa3dAnalysisCache(private val project: Project) : Disposable {

    private val log = logger<Jpa3dAnalysisCache>()
    private val analyzer = Jpa3dAnalyzer(project)
    private val dirty = AtomicBoolean(true)
    private val invalidationListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var cached: GraphData? = null

    init {
        // listener 의 lifecycle 을 service(this) 에 묶음 — service dispose 시 자동 해제.
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

    /** 외부에서 강제 무효화. 디버그/테스트 용도. */
    fun invalidate() {
        markDirty()
    }

    /**
     * 캐시 무효화 시점에 호출될 콜백 등록. 등록 해제는 [parentDisposable] 가 dispose 될 때 자동.
     *
     * 콜백은 PSI write 스레드에서 fire 되므로 무거운 작업은 EDT 로 옮길 것.
     * 또한 debounce 가 필요하면 호출 측이 책임.
     */
    fun addInvalidationListener(parentDisposable: Disposable, listener: () -> Unit) {
        invalidationListeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parentDisposable) {
            invalidationListeners.remove(listener)
        }
    }

    private fun markDirty() {
        // 같은 burst 안에서 여러 번 dirty 호출되면 fire 도 여러 번 → 호출 측 debounce 로 흡수.
        val wasClean = !dirty.getAndSet(true)
        if (wasClean) {
            log.debug("cache marked dirty")
        }
        invalidationListeners.forEach { it() }
    }

    override fun dispose() {
        // PsiTreeChangeListener 는 init 에서 this 를 parent disposable 로 넘겼으므로 자동 해제.
        cached = null
    }

    /**
     * Java/Kotlin 소스 변경 시 dirty 표시.
     *
     * 모든 PSI 이벤트를 다 다루지 말고 children 류만 잡아도 충분 — 코드 편집은
     * 결국 childrenChanged 로 떨어진다. file rename/move 는 추가로 잡아둔다.
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
                markDirty()
            }
        }
    }
}
