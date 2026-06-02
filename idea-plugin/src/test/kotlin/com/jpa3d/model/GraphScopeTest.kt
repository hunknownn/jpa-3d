package com.jpa3d.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * seed selector → 노드 집합 해석 + 멀티 소스 BFS 의 순수 단위 테스트 (Platform 의존 없음).
 */
class GraphScopeTest {

    private fun node(fqn: String): GraphNode =
        GraphNode(id = fqn, name = fqn.substringAfterLast('.'), pkg = fqn.substringBeforeLast('.'),
            kind = "class", stereotypes = emptyList(), entity = null)

    private fun link(a: String, b: String): GraphLink =
        GraphLink(source = a, target = b, relation = Relation.MANY_TO_ONE, weight = 1, label = null)

    // 패키지 트리:
    //   com.app.order.Order, com.app.order.OrderItem, com.app.order.detail.Shipment
    //   com.app.user.User
    private val nodes = listOf(
        node("com.app.order.Order"),
        node("com.app.order.OrderItem"),
        node("com.app.order.detail.Shipment"),
        node("com.app.user.User"),
    )

    // === resolveSeeds ===

    @Test fun fqnSeedResolvesSingleton() {
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_FQN, "com.app.order.Order")
        assertEquals(setOf("com.app.order.Order"), seeds)
    }

    @Test fun fqnSeedMissingResolvesEmpty() {
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_FQN, "com.app.none.Ghost")
        assertEquals(emptySet(), seeds)
    }

    @Test fun packageSeedIncludesSubPackages() {
        // com.app.order 는 order 직속 + 하위 detail 까지 포함, user 는 제외.
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_PACKAGE, "com.app.order")
        assertEquals(
            setOf("com.app.order.Order", "com.app.order.OrderItem", "com.app.order.detail.Shipment"),
            seeds
        )
    }

    @Test fun packageSeedExactPackageOnly() {
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_PACKAGE, "com.app.user")
        assertEquals(setOf("com.app.user.User"), seeds)
    }

    @Test fun packageSeedNoPrefixFalseMatch() {
        // "com.app.order" 가 "com.app.ordering" 같은 형제를 잘못 먹지 않아야 (점 경계).
        val withSibling = nodes + node("com.app.ordering.Thing")
        val seeds = GraphScope.resolveSeeds(withSibling, GraphScope.SEED_TYPE_PACKAGE, "com.app.order")
        assertEquals(false, seeds.contains("com.app.ordering.Thing"))
    }

    @Test fun blankSeedResolvesEmpty() {
        assertEquals(emptySet(), GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_PACKAGE, ""))
    }

    // === reachable (멀티 소스 BFS) ===

    @Test fun packageSeedMultiSourceBfsDepth1() {
        // Order→User, OrderItem→Shipment(외부 아님), User 단독.
        // 패키지 seed = order 패키지 전체. depth=1 이면 그들과 직접 연결된 User 까지 포함.
        val links = listOf(
            link("com.app.order.Order", "com.app.user.User"),
            link("com.app.order.OrderItem", "com.app.order.Order"),
        )
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_PACKAGE, "com.app.order")
        val reached = GraphScope.reachable(seeds, links, maxDepth = 1)
        assertEquals(
            setOf(
                "com.app.order.Order", "com.app.order.OrderItem", "com.app.order.detail.Shipment",
                "com.app.user.User"
            ),
            reached
        )
    }

    @Test fun depthZeroKeepsOnlySeeds() {
        val links = listOf(link("com.app.order.Order", "com.app.user.User"))
        val seeds = GraphScope.resolveSeeds(nodes, GraphScope.SEED_TYPE_PACKAGE, "com.app.user")
        val reached = GraphScope.reachable(seeds, links, maxDepth = 0)
        assertEquals(setOf("com.app.user.User"), reached)
    }

    @Test fun emptySeedsReachableEmpty() {
        assertEquals(emptySet(), GraphScope.reachable(emptySet(), emptyList(), 3))
    }
}
