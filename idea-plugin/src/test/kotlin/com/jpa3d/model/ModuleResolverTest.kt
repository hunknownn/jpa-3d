package com.jpa3d.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ModuleResolver] 순수 로직 단위 테스트 (Platform 의존 없음).
 */
class ModuleResolverTest {

    // === normalizeModuleName ===

    @Test fun stripsSourceSetSuffixAndTakesLeaf() {
        assertEquals("order-service", ModuleResolver.normalizeModuleName("ecommerce-msa.order-service.main"))
        assertEquals("order-service", ModuleResolver.normalizeModuleName("ecommerce-msa.order-service.test"))
    }

    @Test fun nestedGradlePathTakesLeaf() {
        assertEquals("order", ModuleResolver.normalizeModuleName("root.services.order.main"))
    }

    @Test fun plainNameUnchanged() {
        assertEquals("order-service", ModuleResolver.normalizeModuleName("order-service"))
    }

    @Test fun rootModuleWithSourceSet() {
        assertEquals("ecommerce-msa", ModuleResolver.normalizeModuleName("ecommerce-msa.main"))
    }

    @Test fun blankFallsBackToDefault() {
        assertEquals(ModuleResolver.DEFAULT_MODULE, ModuleResolver.normalizeModuleName(""))
    }

    // === commonPackagePrefix ===

    @Test fun commonPrefixAtSegmentBoundary() {
        val pkgs = listOf("com.example.shop.order", "com.example.shop.payment", "com.example.shop.catalog")
        assertEquals("com.example.shop", ModuleResolver.commonPackagePrefix(pkgs))
    }

    @Test fun commonPrefixNoOverlap() {
        assertEquals("", ModuleResolver.commonPackagePrefix(listOf("com.a", "org.b")))
    }

    @Test fun commonPrefixIgnoresEmptyAndHandlesSingle() {
        assertEquals("com.example.shop.order",
            ModuleResolver.commonPackagePrefix(listOf("com.example.shop.order", "")))
    }

    @Test fun commonPrefixNotFooledByPartialSegment() {
        // "com.app.order" 와 "com.app.ordering" 의 공통은 "com.app" — "order" 가 아니다(세그먼트 경계).
        assertEquals("com.app", ModuleResolver.commonPackagePrefix(listOf("com.app.order", "com.app.ordering")))
    }

    // === packageModule ===

    @Test fun domainSegmentAfterBase() {
        assertEquals("order", ModuleResolver.packageModule("com.example.shop.order", "com.example.shop"))
    }

    @Test fun subPackageCollapsesToDomain() {
        assertEquals("order", ModuleResolver.packageModule("com.example.shop.order.repository", "com.example.shop"))
    }

    @Test fun pkgEqualsBaseUsesBaseLeaf() {
        assertEquals("shop", ModuleResolver.packageModule("com.example.shop", "com.example.shop"))
    }

    @Test fun emptyPkgIsDefault() {
        assertEquals(ModuleResolver.DEFAULT_MODULE, ModuleResolver.packageModule("", "com.example"))
    }

    // === assignModules ===

    @Test fun usesIdeModulesWhenTwoOrMore() {
        // 두 개 이상 IDE 모듈 → IDE 모듈(정규화) 우선.
        val ide = mapOf(
            "com.shop.user.User" to "ecommerce-msa.user-service.main",
            "com.shop.order.Order" to "ecommerce-msa.order-service.main",
        )
        val pkg = mapOf(
            "com.shop.user.User" to "com.shop.user",
            "com.shop.order.Order" to "com.shop.order",
        )
        val result = ModuleResolver.assignModules(ide, pkg)
        assertEquals("user-service", result["com.shop.user.User"])
        assertEquals("order-service", result["com.shop.order.Order"])
    }

    @Test fun fallsBackToPackageWhenSingleIdeModule() {
        // 단일 모듈(=IDE 모듈 1종) → 패키지 도메인 세그먼트.
        val ide = mapOf(
            "com.example.shop.order.Order" to "demo.main",
            "com.example.shop.payment.Payment" to "demo.main",
        )
        val pkg = mapOf(
            "com.example.shop.order.Order" to "com.example.shop.order",
            "com.example.shop.payment.Payment" to "com.example.shop.payment",
        )
        val result = ModuleResolver.assignModules(ide, pkg)
        assertEquals("order", result["com.example.shop.order.Order"])
        assertEquals("payment", result["com.example.shop.payment.Payment"])
    }

    @Test fun mixedIdeModulesFallBackPerNode() {
        // IDE 모듈 2종 이상이되 일부 노드는 IDE 모듈 미상(null) → 그 노드만 패키지 폴백.
        val ide = mapOf(
            "com.shop.user.User" to "msa.user-service.main",
            "com.shop.order.Order" to "msa.order-service.main",
            "com.shop.legacy.Thing" to null,
        )
        val pkg = mapOf(
            "com.shop.user.User" to "com.shop.user",
            "com.shop.order.Order" to "com.shop.order",
            "com.shop.legacy.Thing" to "com.shop.legacy",
        )
        val result = ModuleResolver.assignModules(ide, pkg)
        assertEquals("user-service", result["com.shop.user.User"])
        assertEquals("order-service", result["com.shop.order.Order"])
        // base = "com.shop" → legacy
        assertEquals("legacy", result["com.shop.legacy.Thing"])
    }
}
