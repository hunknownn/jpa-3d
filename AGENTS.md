# AGENTS.md

jpa-3d 작업 시 지켜야 할 규칙. (IntelliJ 플러그인 `idea-plugin` + JCEF 임베드 웹 뷰어 `viewer`)

## 성능 — 최우선 원칙

**성능 문제가 없어야 한다. 블로킹 대신 논블로킹 방식을 사용하라.**

- UI 스레드(EDT) 와 **AppKit(macOS 네이티브 UI) / JCEF 콜백 스레드** 를 절대 무거운 작업으로 막지 말 것.
- 무거운 작업(프로젝트 분석, PSI 순회, I/O)은 **백그라운드 풀 스레드**(`ApplicationManager.getApplication().executeOnPooledThread { ... }`)에서 수행한다.
- 결과가 아직 없으면 **즉시** 상태 플래그(예: `indexing:true`)로 응답하고, viewer 가 폴링/재시도해 준비된 결과를 받게 한다. 호출 스레드에서 동기로 기다리지 않는다.
- 락(`@Synchronized` 등)을 **UI/콜백 스레드에서 대기**하게 만들지 말 것. 락은 백그라운드에서만 대기한다.

### 왜 (실측 사례, 반드시 기억)

JCEF 브리지 콜백(`JBCefJSQuery.addHandler`)은 macOS 에서 **AppKit 스레드**에서 실행된다.
거기서 `Jpa3dAnalysisCache.getGraphData()`(첫 호출 시 전체 분석, `@Synchronized`)를 동기로 호출하면:

1. AppKit 스레드가 분석 락을 기다리며 **BLOCKED**,
2. EDT 의 포커스 요청 → 네이티브 `NSWindow.isKeyWindow` 가 그 AppKit 스레드를 필요로 함,
3. 결과적으로 **EDT 가 ~12초 프리즈** (freeze 스레드 덤프로 확인됨).

→ 해결: `Jpa3dAnalysisCache.getGraphDataOrNull()` 같은 **논블로킹 조회**를 쓴다.
캐시가 신선하면 즉시 반환, 아니면 백그라운드 재계산을 트리거하고 `null`(→ `indexing` 응답) 반환.
bridge 핸들러(`Jpa3dRequestHandler.handleErd`/`handleSearch`)는 이 논블로킹 경로만 사용한다.

### 새 코드 체크리스트

- [ ] EDT / AppKit / JCEF 콜백 스레드에서 동기 블로킹(분석·락·I/O)이 없는가?
- [ ] 무거운 작업은 풀 스레드로 보냈는가?
- [ ] 준비 안 됨 상태를 즉시 응답 + 재시도로 처리하는가?
- [ ] 변경 후 runIde 로 첫 로드 시 UI 프리즈(`PerformanceWatcher ... UI was frozen`)가 로그에 없는가?

## viewer 빌드

- viewer 소스를 고치면 `idea-plugin` 의 `buildViewer` 태스크가 입력/출력 추적으로 **자동 재빌드**된다(수동 `npm run build` 불필요). runIde 를 재시작하면 새 번들이 반영된다.

## bridge 데이터 계약

- plugin → viewer 로 보내는 `GraphData` 를 가공/재구성할 때 `modules`, `architecture` 등 **메타 필드를 빠뜨리지 말 것**. viewer 의 모듈 컨테이너(2D)·군집(3D)·아키텍처 배지·범례·모듈 필터가 모두 이 필드에 의존한다.
