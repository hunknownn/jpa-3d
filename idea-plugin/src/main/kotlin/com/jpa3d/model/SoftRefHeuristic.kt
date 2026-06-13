package com.jpa3d.model

/**
 * 약한 ID 참조(soft reference) 엣지 추출의 순수 로직 (Platform 의존 없음 — 단위 테스트 대상).
 *
 * MSA 처럼 모듈 경계를 넘는 참조가 진짜 FK 가 아니라 `userId: Long` 같은 평범한 컬럼인 경우를
 * 잡아 [Relation.SOFT_REF] 엣지로 만든다. 입력은 이미 추출된 엔티티별 [ColumnInfo] 뿐이라
 * PSI 없이 순수하게 계산된다 ([Jpa3dAnalyzer] 가 모든 엔티티 컬럼을 모은 뒤 호출).
 *
 * 오탐(`parentId`, `createdById` 등)을 막기 위한 가드:
 *  1. `<name>Id` / `<name>_id` 패턴만,
 *  2. PK형 스칼라 타입만 (Long/Integer/UUID/String),
 *  3. strip 한 이름이 알려진 엔티티 심플네임과 **정확·유일** 일치,
 *  4. 매칭 엔티티의 실제 @Id 타입과 호환(알 수 있을 때).
 */
object SoftRefHeuristic {

    /**
     * FK 처럼 쓰이는 스칼라 PK 타입 — **심플네임** 기준으로 비교한다.
     * canonicalText 가 환경에 따라 FQN(`java.lang.Long`) 또는 단축명(`Long`) 으로 오므로
     * 마지막 세그먼트만 떼어 매칭해 양쪽을 모두 견고하게 처리한다.
     */
    private val SCALAR_PK_SIMPLE_TYPES = setOf(
        "Long", "long", "Integer", "int", "Short", "short", "UUID", "String"
    )

    /** primitive → boxed 심플네임 정규화 (호환 판정용). */
    private val PRIMITIVE_TO_BOXED = mapOf(
        "long" to "Long", "int" to "Integer", "short" to "Short"
    )

    /** FQN/단축명 어느 쪽이든 마지막 세그먼트(심플 타입명). */
    private fun simpleType(javaType: String): String = javaType.substringAfterLast('.')

    /**
     * `<name>Id` / `<name>_id` 에서 참조 대상 이름(`name`)을 추출. 패턴이 아니면 null.
     * 예: "userId" → "user", "user_id" → "user", "id" → null, "valid" → null(소문자 id).
     */
    fun baseName(fieldName: String): String? {
        return when {
            fieldName.endsWith("_id") && fieldName.length > 3 -> fieldName.dropLast(3)
            fieldName.endsWith("Id") && fieldName.length > 2 -> fieldName.dropLast(2)
            else -> null
        }
    }

    /** PK 처럼 쓸 수 있는 스칼라 타입인가 (심플네임 기준). */
    fun isScalarPkType(javaType: String): Boolean = simpleType(javaType) in SCALAR_PK_SIMPLE_TYPES

    /** 두 타입이 PK 참조로 호환되는가 (FQN/단축명·primitive/boxed 차이는 동일 취급). */
    fun pkTypeCompatible(a: String, b: String): Boolean {
        fun norm(t: String) = simpleType(t).let { PRIMITIVE_TO_BOXED[it] ?: it }
        return norm(a) == norm(b)
    }

    /**
     * FQN 집합에서 심플네임(소문자) → FQN 인덱스. 같은 심플네임이 둘 이상이면(모호) 제외해
     * 잘못된 엔티티로 엣지가 가는 것을 막는다.
     */
    fun buildSimpleNameIndex(fqns: Set<String>): Map<String, String> {
        val bySimple = HashMap<String, MutableList<String>>()
        for (fqn in fqns) {
            val simple = fqn.substringAfterLast('.').lowercase()
            bySimple.getOrPut(simple) { mutableListOf() }.add(fqn)
        }
        return bySimple.asSequence()
            .filter { it.value.size == 1 }
            .associate { it.key to it.value.first() }
    }

    /**
     * 엔티티별 컬럼에서 soft-ref 엣지를 추출.
     *
     * @param columnsByFqn 엔티티 FQN → 컬럼 목록. PK 타입 비교를 위해 전 엔티티가 모인 상태여야 한다.
     * @param knownFqns    엔티티 FQN 집합(심플네임 인덱스 소스).
     */
    fun extractLinks(
        columnsByFqn: Map<String, List<ColumnInfo>>,
        knownFqns: Set<String>
    ): List<GraphLink> {
        val bySimpleName = buildSimpleNameIndex(knownFqns)
        val pkTypeByFqn = columnsByFqn.mapValues { (_, cols) -> cols.firstOrNull { it.primaryKey }?.javaType }

        val links = mutableListOf<GraphLink>()
        for ((ownerFqn, cols) in columnsByFqn) {
            for (col in cols) {
                // 진짜 FK(이미 관계 엣지 존재)나 PK 자신은 제외.
                if (col.foreignKey || col.primaryKey) continue
                if (!isScalarPkType(col.javaType)) continue
                val base = baseName(col.fieldName) ?: continue
                val targetFqn = bySimpleName[base.lowercase()] ?: continue
                if (targetFqn == ownerFqn) continue // 자기 참조는 약한 참조로 보지 않음
                val targetPk = pkTypeByFqn[targetFqn]
                if (targetPk != null && !pkTypeCompatible(col.javaType, targetPk)) continue
                links.add(GraphLink(ownerFqn, targetFqn, Relation.SOFT_REF, weight = 1, label = col.fieldName))
            }
        }
        return links
    }
}
