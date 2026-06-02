package com.jpa3d.model

/**
 * seed 기반 부분 그래프 산출의 공유 로직.
 *
 * 라이브 뷰어([com.jpa3d.Jpa3dRequestHandler])와 export([com.jpa3d.export.ExportConverter]) 양쪽이
 * 같은 규칙으로 seed 를 해석하고 BFS 하도록 한 곳에 모았다.
 *
 * seed 는 단일 노드가 아니라 **노드 집합** 으로 일반화된다 — FQN seed 는 singleton, package seed 는
 * 해당 패키지(+하위 패키지)의 모든 엔티티가 동시에 출발점이 되는 멀티 소스 BFS.
 */
object GraphScope {

    const val SEED_TYPE_FQN = "fqn"
    const val SEED_TYPE_PACKAGE = "package"

    /**
     * seed selector 를 출발 노드 id 집합으로 해석한다.
     *
     *  - [SEED_TYPE_FQN]: 정확히 그 FQN (노드 목록에 존재할 때만).
     *  - [SEED_TYPE_PACKAGE]: pkg 가 [seed] 와 같거나 그 하위 패키지(`seed.` 접두)인 모든 노드.
     *
     * 매칭이 없으면 빈 집합 → 호출 측에서 "빈 그래프" 로 처리.
     */
    fun resolveSeeds(nodes: List<GraphNode>, seedType: String, seed: String): Set<String> {
        if (seed.isBlank()) return emptySet()
        return when (seedType) {
            SEED_TYPE_PACKAGE ->
                nodes.asSequence()
                    .filter { it.pkg == seed || it.pkg.startsWith("$seed.") }
                    .map { it.id }.toSet()
            else -> // SEED_TYPE_FQN (기본)
                nodes.asSequence().filter { it.id == seed }.map { it.id }.toSet()
        }
    }

    /**
     * [seeds] 에서 시작해 방향 무시(undirected)로 [maxDepth] 단계까지 도달 가능한 노드 id 집합.
     * 반환값은 seeds 자체를 포함한다. seeds 가 비면 빈 집합, maxDepth<0 이면 seeds 그대로.
     */
    fun reachable(seeds: Set<String>, links: List<GraphLink>, maxDepth: Int): Set<String> {
        if (seeds.isEmpty()) return emptySet()
        if (maxDepth < 0) return seeds
        val adj = HashMap<String, MutableList<String>>()
        for (l in links) {
            adj.getOrPut(l.source) { mutableListOf() }.add(l.target)
            adj.getOrPut(l.target) { mutableListOf() }.add(l.source)
        }
        val visited = seeds.toMutableSet()
        var frontier = seeds.toList()
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
}
