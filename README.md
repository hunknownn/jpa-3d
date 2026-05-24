# jpa-3d

JPA Entity ERD 를 3D / 2D 로 시각화하는 IntelliJ Plugin (작업 중).

원본: [DepScope](https://github.com/hunknownn/DepScope) 의 ERD 뷰를 떼어내 plugin 형태로 재구성.

## 디렉터리

```
jpa-3d/
├── viewer/                React + Vite 뷰어 (plugin 의 JCEF 안에 임베드)
│   ├── src/
│   │   ├── ErdApp.tsx     ERD 메인 화면
│   │   ├── ErdView2D.tsx  elkjs 기반 2D ERD 렌더러
│   │   ├── GraphView.tsx  react-force-graph-3d 기반 3D 렌더러
│   │   ├── api.ts         plugin 브리지 + 스탠드얼론 fixture fallback
│   │   ├── types.ts       GraphData / EntityInfo / ColumnInfo 등
│   │   └── main.tsx       엔트리
│   └── public/fixtures/erd.json   스탠드얼론 개발용 더미 데이터
│
├── idea-plugin/           IntelliJ Plugin (Kotlin)
│   └── src/main/
│       ├── kotlin/com/jpa3d/
│       │   ├── Jpa3dToolWindowFactory.kt   ToolWindow 등록
│       │   ├── Jpa3dViewerPanel.kt         JCEF 패널 + lifecycle
│       │   ├── BridgeInjector.kt           viewer ↔ plugin IPC 주입
│       │   └── Jpa3dRequestHandler.kt      요청 핸들러 (현재 스텁)
│       └── resources/META-INF/plugin.xml
│
└── reference/             PSI 분석기 작성 시 참조용 — 직접 사용 X
    ├── ClassIndexer.java       ASM 기반 JPA 어노테이션 처리 (PSI 로 포팅 예정)
    └── extractor-model/        Node / Edge / Relation / EntityInfo / ColumnInfo
```

## viewer 단독 실행 (plugin 없이)

```bash
cd viewer
npm install
npm run dev
```

브라우저에서 `http://localhost:5173` 접속 → `public/fixtures/erd.json` 데이터로 동작.

## Plugin 통합 시 (예정)

`window.__JPA3D_BRIDGE__` 를 호스트(IntelliJ plugin 의 JCEF) 가 주입한다.

```ts
interface Jpa3dBridge {
  request(kind: "erd" | "search", args: unknown): Promise<unknown>;
}
```

브리지가 주입돼 있으면 `api.ts` 는 fetch 대신 그 브리지를 통해 호스트에 요청한다. `reference/` 의 자바 모델을 그대로 직렬화하면 viewer 의 `types.ts` 와 매칭된다.

## Plugin 빌드 & 실행

```bash
# plugin zip 생성 (viewer 빌드 자동 수행)
./gradlew :idea-plugin:buildPlugin
# → idea-plugin/build/distributions/idea-plugin-*.zip

# 샌드박스 IDE 에 plugin 띄워서 직접 확인
./gradlew :idea-plugin:runIde
```

> Gradle 데몬은 JDK 21 을 사용한다 (`gradle.properties` 의 `org.gradle.java.home`).
> Kotlin 컴파일러 IntelliJ 내장 utility 가 JDK 25 의 버전 문자열을 파싱 못해 막힌다.

## JetBrains Marketplace 배포

### 첫 출시 (수동)

1. plugins.jetbrains.com 로그인 → **Upload plugin**
2. `idea-plugin/build/distributions/idea-plugin-0.1.0.zip` 업로드
3. Title / Description / Tags / Categories 설정 (plugin.xml 의 내용이 자동 반영됨)
4. Screenshots (1280×800 권장, 3장 이상)
5. 제출 → JetBrains 검수 (영업일 1~3일)

### 이후 업데이트 (자동)

```bash
# https://plugins.jetbrains.com/author/me/tokens 에서 token 발급
export ORG_GRADLE_PROJECT_publishToken="perm:..."

# version 을 bump 한 뒤
./gradlew :idea-plugin:publishPlugin
```

### 코드 서명 (선택)

JetBrains 가 권장. `gradle.properties` 또는 환경변수:

```properties
certificateChain=...
privateKey=...
privateKeyPassword=...
```

미설정 시 unsigned 로 배포되며 marketplace 가 자체 서명을 추가.

자세한 안내: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html

## 다음 작업

1. ~~`idea-plugin/` 모듈 추가~~ ✓
2. ~~ToolWindow + JCEF 패널 + viewer dist 번들~~ ✓
3. ~~PSI 기반 JPA 분석기~~ ✓ (UAST 로 Kotlin 까지 지원)
4. ~~Marketplace 메타데이터 / 아이콘 / publish 설정~~ ✓
5. ERD 노드 클릭 → 소스 점프 (`NavigationUtil.activateFileWithPsiElement`)
6. PSI 변경 리스너로 실시간 갱신
7. 분석기 결과 캐싱 (PSI 변경 시 무효화) — 성능 개선
