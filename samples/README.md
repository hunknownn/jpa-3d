# JPA-3D 아키텍처 샘플

같은 shop 도메인(User / Product / Order / OrderItem)을 **세 가지 아키텍처**로 구성해,
JPA-3D 의 아키텍처 자동 감지와 엣지 3종 분류를 케이스별로 확인하기 위한 참고 프로젝트.

세 케이스의 **결정적 차이는 모듈 경계를 넘는 참조의 성격**이다.

| 샘플 | 빌드 구조 | 모듈 간 참조 | 감지 결과 | 경계 엣지 |
|---|---|---|---|---|
| `monolith/` | 단일 빌드 · 단일 패키지(`com.shop`) | (모듈 1개) | **MONOLITH** | 전부 INTRA |
| `modular-monolith/` | 단일 빌드 + 서브프로젝트(`:user`/`:catalog`/`:order`) | 모듈 간 **`@ManyToOne`** (공유 영속성) | **MODULAR_MONOLITH** | **CROSS_FK**(굵게) |
| `msa/` | 서비스마다 **별도 빌드/DB** | **`userId: Long`** 약한 ID 참조 + `UserSnapshot` 복제 | **MSA** | **CROSS_SOFT**(점선) |

## 핵심 코드 차이

- **monolith** — `Order` 가 같은 패키지의 `User` 를 `@ManyToOne` 으로 참조. 모듈 경계가 없다.
- **modular-monolith** — `order` 모듈의 `Order` 가 다른 모듈 `user` 의 `User` 를 **직접 `@ManyToOne`** (단일 DB라 가능). → CROSS_FK.
- **msa** — `order-service` 는 `user-service` 를 import 하지 못하고 `Long userId` 로만 참조. → CROSS_SOFT.

## 실제 IDE 에서 보기

`runIde` 샌드박스에서 각 폴더를 열면 (Gradle 동기화로 jakarta.persistence 해석 후) 툴윈도우에서:

- 폴더로 열 대상:
  - 모놀리식 → `samples/monolith`
  - 멀티모듈 모놀리식 → `samples/modular-monolith`
  - MSA → `samples/msa` (3개 서비스를 함께 열어야 서비스 간 약한참조가 매칭됨)

> 분석 로직(감지/분류)은 `idea-plugin` 의 `SampleProjectsVerificationTest` 가 이 소스들을 그대로
> 통과시켜 회귀 검증한다. settings.gradle 개수(빌드 경계) 1차 신호는 실제 IDE 에서만 적용되고,
> 테스트(light fixture)에서는 JPA-엣지 폴백 경로로 동일 결과를 확인한다.
