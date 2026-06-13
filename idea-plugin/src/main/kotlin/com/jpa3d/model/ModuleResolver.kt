package com.jpa3d.model

/**
 * 노드를 논리 "모듈" 로 그루핑하는 순수 로직 (Platform 의존 없음 — 단위 테스트 대상).
 *
 * 모듈 단위는 추상화된 "그루핑 키" 다. 두 단계 폴백으로 산출한다:
 *  1. IntelliJ/Gradle 모듈명(정규화) — 멀티모듈 모놀리스 / MSA 처럼 모듈이 둘 이상일 때.
 *  2. 공통 패키지 이후의 도메인 세그먼트 — 단일 모듈인데 패키지로 나눈 모놀리스
 *     (예: `com.example.shop.order`, `com.example.shop.payment` → `order` / `payment`).
 *
 * Platform 접근([com.intellij.openapi.roots.ProjectFileIndex])은 호출 측([Jpa3dAnalyzer])이
 * 담당하고, 여기서는 그 결과(fqn→ide모듈명)와 패키지(fqn→pkg)만 받아 순수하게 계산한다.
 */
object ModuleResolver {

    /** 모듈명/패키지 어느 쪽으로도 모듈을 못 정한 노드의 기본값. */
    const val DEFAULT_MODULE = "default"

    /**
     * IntelliJ Gradle 모듈명의 소스셋 접미사 — `<root>.<sub>.main` 형태에서 떼어낸다.
     * 떼어낸 뒤 마지막 세그먼트가 논리 모듈명(예: `order-service`).
     */
    private val SOURCE_SET_SUFFIXES = setOf(
        "main", "test", "integrationTest", "testFixtures", "intTest", "e2eTest"
    )

    /**
     * IntelliJ Gradle 모듈명을 논리 모듈명으로 정규화.
     *  - 소스셋 접미사 제거: `ecommerce-msa.order-service.main` → `ecommerce-msa.order-service`
     *  - 리프 세그먼트 채택: → `order-service`
     *
     * 점이 없는 단순 이름(`order-service`)은 그대로 반환. 빈 문자열은 [DEFAULT_MODULE].
     */
    fun normalizeModuleName(raw: String): String {
        if (raw.isBlank()) return DEFAULT_MODULE
        val tail = raw.substringAfterLast('.', "")
        val base = if (tail in SOURCE_SET_SUFFIXES) raw.substringBeforeLast('.') else raw
        return base.substringAfterLast('.').ifBlank { DEFAULT_MODULE }
    }

    /**
     * 패키지 목록의 최장 공통 접두 (점 세그먼트 경계 기준).
     * 예: ["com.example.shop.order", "com.example.shop.payment"] → "com.example.shop".
     * 빈 패키지는 무시. 공통이 없으면 "".
     */
    fun commonPackagePrefix(pkgs: Collection<String>): String {
        val segLists = pkgs.filter { it.isNotEmpty() }.map { it.split('.') }
        if (segLists.isEmpty()) return ""
        val first = segLists.first()
        var prefixLen = first.size
        for (segs in segLists) {
            var i = 0
            while (i < prefixLen && i < segs.size && segs[i] == first[i]) i++
            prefixLen = i
            if (prefixLen == 0) break
        }
        return first.take(prefixLen).joinToString(".")
    }

    /**
     * 패키지에서 공통 접두 [base] 이후의 첫 세그먼트를 도메인 모듈로 추출.
     *  - `com.example.shop.order.repository`, base=`com.example.shop` → `order`
     *  - pkg == base (구분 세그먼트 없음) → base 의 리프 세그먼트
     *  - 빈 패키지 → [DEFAULT_MODULE]
     */
    fun packageModule(pkg: String, base: String): String {
        if (pkg.isEmpty()) return DEFAULT_MODULE
        val rest = when {
            base.isEmpty() -> pkg
            pkg == base -> ""
            pkg.startsWith("$base.") -> pkg.substring(base.length + 1)
            else -> pkg // base 밖의 패키지 — 통째로 첫 세그먼트 사용
        }
        if (rest.isEmpty()) return base.substringAfterLast('.').ifBlank { DEFAULT_MODULE }
        return rest.substringBefore('.')
    }

    /**
     * fqn → 모듈명 맵을 산출한다.
     *
     *  - 서로 다른 IDE 모듈이 **2개 이상**이면 → IDE 모듈(정규화) 우선,
     *    IDE 모듈을 못 얻은 노드만 패키지 세그먼트로 폴백.
     *  - 그 외(IDE 모듈 0~1개) → 전부 패키지 세그먼트 기준 (단일 모듈/모놀리스).
     *
     * @param ideModuleByFqn fqn → IntelliJ 모듈명(정규화 전). 미상이면 null.
     * @param pkgByFqn       fqn → 패키지.
     */
    fun assignModules(
        ideModuleByFqn: Map<String, String?>,
        pkgByFqn: Map<String, String>
    ): Map<String, String> {
        val base = commonPackagePrefix(pkgByFqn.values)
        val distinctIde = ideModuleByFqn.values.asSequence()
            .filterNotNull()
            .map { normalizeModuleName(it) }
            .toSet()

        if (distinctIde.size >= 2) {
            return pkgByFqn.mapValues { (fqn, pkg) ->
                ideModuleByFqn[fqn]?.let { normalizeModuleName(it) }
                    ?: packageModule(pkg, base)
            }
        }
        return pkgByFqn.mapValues { (_, pkg) -> packageModule(pkg, base) }
    }
}
