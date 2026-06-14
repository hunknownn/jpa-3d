# JPA-3D 아키텍처 샘플

같은 e-commerce 도메인(약 17개 엔티티)을 **세 가지 아키텍처**로 구성해, JPA-3D 의
아키텍처 자동 감지와 엣지 3종 분류를 케이스별로 확인하기 위한 참고 프로젝트.

세 케이스의 **결정적 차이는 모듈 경계를 넘는 참조의 성격**이다. 도메인 구조(엔티티/관계)는 동일하다.

| 샘플 | 빌드 구조 | 모듈 간 참조 | 감지 결과 | 경계 엣지 |
|---|---|---|---|---|
| `monolith/` | 단일 빌드 · 단일 패키지(`com.shop`) | (모듈 1개) | **MONOLITH** | 전부 INTRA |
| `modular-monolith/` | 단일 빌드 + 서브프로젝트(`:user`/`:catalog`/`:order`) | 모듈 간 **`@ManyToOne`** (공유 영속성) | **MODULAR_MONOLITH** | **CROSS_FK**(굵게) |
| `msa/` | 서비스마다 **별도 빌드/DB** | **`userId: Long`** 약한 ID 참조 + `UserSnapshot` 복제 | **MSA** | **CROSS_SOFT**(점선) |

## 도메인 (3개 바운디드 컨텍스트)

- **user** — `User`, `UserProfile`(1:1), `Address`(N:1), `Role`(M:N)
- **catalog / product** — `Category`(자기참조 계층), `Brand`, `Product`, `ProductImage`, `ProductReview`, `Inventory`(1:1)
- **order** — `Cart`, `CartItem`, `Order`, `OrderItem`, `Payment`(1:1), `Shipment`(1:1), `Coupon` (MSA 는 `UserSnapshot` 복제본 추가)

엔티티 수: monolith 17 · modular-monolith 17 · msa 18 (각 케이스 모두 15개 이상).

## 모듈 경계를 넘는 참조 (케이스별 차이)

같은 5개 참조가 아키텍처에 따라 다른 엣지로 분류된다:

| 참조 | monolith | modular-monolith | msa |
|---|---|---|---|
| `Order` → `User` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`userId`) |
| `OrderItem` → `Product` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`productId`) |
| `CartItem` → `Product` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`productId`) |
| `Cart` → `User` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`userId`) |
| `ProductReview` → `User` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`userId`) |
| `Shipment` → `Address` | INTRA | **CROSS_FK** | **CROSS_SOFT** (`addressId`) |

- **monolith** — 단일 패키지·단일 영속성. 모든 참조가 객체 `@ManyToOne` 이며 모듈 경계가 없다.
- **modular-monolith** — 모듈이 나뉘어도 단일 DB라 다른 모듈 엔티티를 **직접 `@ManyToOne`** 으로 참조. → CROSS_FK.
  모듈 의존은 순환 없는 DAG: `catalog → user`, `order → user`, `order → catalog`.
- **msa** — 서비스가 서로 import 하지 못하고 `Long userId` 같은 약한 ID 로만 참조. → CROSS_SOFT.

## 실제 IDE 에서 보기

`runIde` 샌드박스에서 각 폴더를 열면 (Gradle 동기화로 jakarta.persistence 해석 후) 툴윈도우에서:

- 폴더로 열 대상:
  - 모놀리식 → `samples/monolith`
  - 멀티모듈 모놀리식 → `samples/modular-monolith`
  - MSA → `samples/msa` (3개 서비스를 함께 열어야 서비스 간 약한참조가 매칭됨)

> 분석 로직(감지/분류)은 `idea-plugin` 의 `SampleProjectsVerificationTest` 가 이 소스들을 그대로
> 통과시켜 회귀 검증한다. settings.gradle 개수(빌드 경계) 1차 신호는 실제 IDE 에서만 적용되고,
> 테스트(light fixture)에서는 JPA-엣지 폴백 경로로 동일 결과를 확인한다.
