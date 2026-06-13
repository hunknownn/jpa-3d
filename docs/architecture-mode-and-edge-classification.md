# 아키텍처 모드 자동 감지 + 엣지 3종 분류 설계

> 상태: 설계(Draft) · 대상 버전: 미정 · 작성 기준 커밋: `dbc8889`

## 1. 배경 / 문제

JPA-3D는 엔티티 그래프를 한 화면에 시각화한다. 그런데 대상 프로젝트의
아키텍처 형태에 따라 "같이 보여줘야 좋은가 / 나눠야 좋은가"가 달라진다.

- **일반 모놀리식**: 단일 모듈. 엔티티 간 진짜 FK 관계가 풍부 → 다 보여줘도 됨.
- **멀티모듈(모듈러) 모놀리식**: 모듈이 여럿이지만 **단일 빌드 · 공유 영속성 컨텍스트**.
  모듈 경계를 넘는 **진짜 JPA 관계(`@ManyToOne` 등)**가 존재하며, 그게 가장 보고 싶은 정보(결합도/경계 침범).
- **MSA**: 모듈마다 **별도 빌드 · 별도 DB**. 모듈 경계를 넘는 참조는 FK가 아니라
  `userId: Long` 같은 **약한 ID 규약 참조**뿐. 진짜 관계 엣지는 모듈 내부에만 존재.

세 형태를 **하나의 뷰어**로 모두 자연스럽게 표현하는 것이 목표다.

### 결론(설계 방향)

> **레이아웃 골격(모듈별 공간 그룹핑 + 다중 토글 필터)은 셋이 공유**하고,
> **엣지 스타일만 아키텍처에 따라 스위칭**한다. 아키텍처는 자동 감지한다.

탭(모듈 배타 표시)은 세 형태 모두에서 비추천이다 — 모듈 경계를 넘는 관계를 가려
시각화의 핵심 가치를 잃는다. flat(전부 평면 나열)도 비추 — 소속·경계 구분이 안 된다.
**공간 그룹핑만이 세 케이스의 공통 최적해**라 이를 골격으로 삼는다.

## 2. 현재 코드 기준선

설계가 기존 코드의 어디에 붙는지 명시한다.

- 데이터 모델: `idea-plugin/.../model/Jpa3dModel.kt` ↔ `viewer/src/types.ts` (1:1 매칭, camelCase, `@JsonInclude(NON_NULL)`).
  - `GraphNode`에는 `pkg`(패키지)만 있고 **`module` 개념이 없다**.
  - `GraphLink`에는 `relation`만 있고 **경계(boundary) 분류가 없다**.
- 분석기: `idea-plugin/.../analyzer/Jpa3dAnalyzer.kt`.
  - 링크는 **JPA 관계 어노테이션이 붙은 필드에서만** 생성된다(`extractFieldsAndRelations`, 관계 분기).
  - 따라서 `userId: Long` 같은 평범한 컬럼은 `ColumnInfo`로만 남고 **엣지가 생기지 않는다**
    → 엣지 3종 중 약한 참조(CROSS_SOFT)는 **신규 분석 로직이 필요**하다.
- 뷰어: `viewer/src/GraphView.tsx`(3D, react-force-graph + d3Force), `ErdView2D.tsx`(2D),
  `Legend.tsx`(범례/필터), `theme.ts`(`RELATION_COLOR`/`RELATION_DASH`).

## 3. 전체 데이터 흐름

```
Jpa3dAnalyzer (PSI/UAST)
  ├─ 1. 노드에 module 부여            ← ProjectFileIndex / 패키지 폴백
  ├─ 2. soft-ref 엣지 추출(신규)       ← <name>Id 컬럼 휴리스틱
  ├─ 3. 아키텍처 모드 감지            ← Gradle 빌드 토폴로지(+JPA 엣지 보조)
  └─ 4. 엣지 boundary 분류(3종)        ← node.module 비교
        ↓ GraphData (+module, +boundary, +architecture, +modules)
viewer
  ├─ 모듈별 공간 그룹핑 (force / z-layer / swimlane)
  ├─ boundary별 엣지 스타일
  └─ 모듈 다중 토글 필터 + 아키텍처 배지
```

## 4. 모듈 식별 (`node.module`)

`GraphNode`에 `module: String?`를 추가한다. 산출은 2단 폴백:

| 우선순위 | 소스 | 대상 케이스 |
|---|---|---|
| 1 | IntelliJ/Gradle 모듈명 | 멀티모듈 모놀리스, MSA |
| 2 | 최상위 도메인 패키지 세그먼트 | 단일 모듈인데 패키지로 나눈 모놀리스 (예: `demo` 의 `shop.order`, `shop.payment`...) |

분석기 산출 스케치:

```kotlin
val vFile = rec.uClass.javaPsi.containingFile?.virtualFile
val ideModule = vFile?.let { ProjectFileIndex.getInstance(project).getModuleForFile(it) }
// "ecommerce-msa.order-service.main" → "order-service"
// (.main/.test 소스셋 접미사, 루트 프로젝트 prefix 제거)
val module = ideModule?.let { normalizeModuleName(it.name) }
    ?: domainPackageSegment(pkg)   // 폴백: pkg 의 도메인 세그먼트
```

> **핵심 결정**: 모듈 단위를 "그루핑 키"로 추상화한다. Gradle 모듈이 여럿이면 그걸 쓰고,
> 한 개면 패키지 세그먼트로 떨어뜨린다. 그래야 일반 모놀리식까지 동일 골격으로 커버된다.

`normalizeModuleName` / `domainPackageSegment` 는 순수 함수로 분리해 단위 테스트 대상으로 둔다.

## 5. 아키텍처 모드 자동 감지 (`GraphData.architecture`)

```kotlin
enum class ArchitectureMode { MONOLITH, MODULAR_MONOLITH, MSA }
```

### 1차 신호 — 빌드 경계 (모놀리스 vs MSA의 정의 그 자체)

- IntelliJ: `GradleSettings.getInstance(project).linkedProjectsSettings` 의 개수
  = 별도 `settings.gradle(.kts)` 루트 수.

판정표:

| 모듈 수 | 별도 Gradle 빌드 수 | → 모드 |
|---|---|---|
| 1 | — | **MONOLITH** |
| N | 1 (단일 빌드 `include(...)`) | **MODULAR_MONOLITH** |
| N | N (빌드/DB 분리) | **MSA** |

### 2차 신호 — 모듈 간 JPA 엣지 (확정/경고 보조)

- MSA로 감지됐는데 모듈 간 진짜 `@ManyToOne` 등이 존재 → **"경계 침범" 경고 배지**
  (공유 라이브러리 엔티티이거나 MSA 분해 누수).
- 1차 신호를 못 읽는 환경에서는 폴백 판정:
  모듈 간 JPA 엣지 존재 → `MODULAR_MONOLITH`, 없음 → `MSA`.

### 가정

MSA 서비스들이 **한 IntelliJ 프로젝트/워크스페이스에 함께 열려 있을 때만** MSA로 감지된다.
한 서비스만 단독으로 열면 그 뷰에서는 `MONOLITH`로 보는 게 맞다(의도된 동작).

## 6. 엣지 3종 분류 (`link.boundary`)

`GraphLink`에 파생 필드를 추가한다:

```kotlin
enum class EdgeBoundary { INTRA, CROSS_FK, CROSS_SOFT }
```

노드 module 확정 후, 모든 링크를 1패스로 분류:

```kotlin
boundary = when {
    src.module == tgt.module            -> INTRA        // 모듈 내부 관계
    relation == Relation.SOFT_REF       -> CROSS_SOFT   // 경계 넘는 약한 ID 참조
    else                                -> CROSS_FK     // 경계 넘는 진짜 JPA 관계
}
```

`EXTENDS`/`IMPLEMENTS`가 경계를 넘는 경우는 드물어 CROSS_FK(구조 엣지) 스타일에 흡수한다.

## 7. Soft-ref 엣지 추출 — 신규 분석 로직 (엣지 3종의 핵심)

`Jpa3dAnalyzer.extractFieldsAndRelations` 의 관계 어노테이션 분기에서 **걸러지지 않은
평범한 컬럼**을 대상으로 휴리스틱을 추가한다.

```
SOFT_REF 링크 emit 조건 (전부 만족 시):
  - 필드명이 <name>Id / <name>_id 패턴
  - 타입이 PK형 스칼라 (Long / Integer / UUID / String)
  - strip("Id") 한 심플네임이 knownEntity 와 정확 일치
  - (강화) 매칭 엔티티의 실제 @Id 타입과 일치 → 오탐 차단
```

- `Relation` enum에 `SOFT_REF` 신규 추가 (Repository용 `USES_ENTITY`와 구분).
- 해당 컬럼은 그대로 둔다(실제 존재하는 컬럼이므로). 엣지만 추가한다.
- **노이즈 가드**: `parentId`(self-ref), `createdById`(시스템 컬럼) 등의 오탐을 막기 위해
  - knownEntity **정확 일치만** 허용,
  - 기본값은 **모듈 경계를 넘는 경우에만 표시**(intra-module soft-ref는 옵션),
  - 설정 토글로 on/off.

→ MSA에서 `Order.userId → User`, `OrderItem.productId → Product` 를 점선으로
그려주는 차별화 포인트. (현재 demo/ecommerce-msa의 `UserSnapshot`, `Order.userId`,
`OrderItem.productId` 패턴이 정확히 이 케이스.)

## 8. 뷰어 (viewer)

### 8.1 타입 동기화 (`types.ts`)

```ts
GraphNode { ...; module?: string }
GraphLink { ...; boundary?: "INTRA" | "CROSS_FK" | "CROSS_SOFT" }
GraphData { ...; architecture?: "MONOLITH" | "MODULAR_MONOLITH" | "MSA"; modules?: string[] }
Relation:  기존 + "SOFT_REF"
```

### 8.2 공간 그룹핑

- **3D (`GraphView.tsx`)**: 모듈 = z-평면(층)으로 매핑. 모듈마다 타깃 중심 좌표를
  링/그리드로 배치하고, `fg.d3Force("moduleCluster", ...)` 커스텀 포스로 각 노드를
  자기 모듈 중심으로 당긴다(기존 charge/link force 튜닝 지점 옆에 추가).
- **2D (`ErdView2D.tsx`)**: 모듈 = 스윔레인 / 그룹 박스(접기 가능).

> 일반 모놀리식은 모듈이 1개라 그룹핑이 자연히 단일 평면으로 수렴 → 별도 분기 불필요.

### 8.3 엣지 스타일 (boundary 기반)

기존 `linkColor` / `linkWidth` 콜백을 `l.boundary`로 분기. `theme.ts`에
`RELATION_COLOR`/`RELATION_DASH` 옆에 `BOUNDARY_STYLE` 추가:

| boundary | 스타일 |
|---|---|
| INTRA | 얇은 실선, 기본색 (배경 취급) |
| CROSS_FK | 굵게 + 고채도 강조 (모듈러 모놀리스의 주인공) |
| CROSS_SOFT | 점선 ghost (MSA의 약한 참조) |

### 8.4 필터 & 배지 (`Legend.tsx`)

- **탭 아님 — 모듈 다중 토글 칩** + "경계 엣지만 보기" 토글로 노드/링크 visibility 구동.
- 아키텍처 모드 배지 표시. 모드별 기본 강조:
  - `MONOLITH`: 전부 실선.
  - `MODULAR_MONOLITH`: CROSS_FK 굵게.
  - `MSA`: CROSS_SOFT 점선 위주 + 경계 침범 시 경고.

## 9. 구현 순서 (PR 분할)

각 단계는 독립 동작하며, 앞 단계만 머지해도 빌드/기존 동작이 깨지지 않는다
(`@JsonInclude(NON_NULL)` 덕에 뷰어가 신규 필드를 무시해도 안전).

1. **모델 + 모듈 식별**: `GraphNode.module` 필드 + `ProjectFileIndex` 산출 + 정규화 순수 함수 + 테스트.
2. **아키텍처 감지 + boundary 분류**: Gradle 빌드 토폴로지 → `GraphData.architecture`,
   링크 `boundary` 채움.
3. **Soft-ref 추출**: `Relation.SOFT_REF` + 휴리스틱 + 노이즈 가드 + `Jpa3dAnalyzerTest` 멀티모듈 픽스처.
4. **뷰어 공간 그룹핑 + 엣지 3종 스타일**.
5. **모듈 토글 필터 + 아키텍처 배지**.

테스트는 기존 `idea-plugin/src/test`의 `JpaStubs` 패턴을 따라 멀티모듈/MSA 픽스처를 구성한다.

## 10. 미해결 / 추후 결정 사항

- `normalizeModuleName` 의 정규화 규칙을 Gradle 외 빌드(Maven, Bazel)에서도 일반화할지.
- soft-ref 휴리스틱을 복합키/문자열 PK·UUID까지 확장할 때의 오탐률.
- 모듈 수가 매우 많을 때(수십 개) z-layer/스윔레인 레이아웃의 스케일링.
- 아키텍처 감지 결과를 사용자가 수동 오버라이드(설정)할 수 있게 할지.
