# jpa-3d

JPA Entity ERD 를 3D / 2D 로 시각화하는 IntelliJ Plugin (작업 중).

원본: [DepScope](https://github.com/hunknownn/DepScope) 의 ERD 뷰를 떼어내 plugin 형태로 재구성.

## 디렉터리

```
jpa-3d/
├── viewer/                React + Vite 뷰어 (plugin 의 JCEF 안에 임베드 예정)
│   ├── src/
│   │   ├── ErdApp.tsx     ERD 메인 화면
│   │   ├── ErdView2D.tsx  elkjs 기반 2D ERD 렌더러
│   │   ├── GraphView.tsx  react-force-graph-3d 기반 3D 렌더러
│   │   ├── api.ts         plugin 브리지 + 스탠드얼론 fixture fallback
│   │   ├── types.ts       GraphData / EntityInfo / ColumnInfo 등
│   │   └── main.tsx       엔트리
│   └── public/fixtures/erd.json   스탠드얼론 개발용 더미 데이터
│
└── reference/             plugin 작성 시 참조용 — 직접 사용 X
    ├── ClassIndexer.java       ASM 기반 JPA 어노테이션 처리 (PSI 로 다시 짤 것)
    └── extractor-model/        Node / Edge / Relation / EntityInfo / ColumnInfo 자바 모델
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

## 다음 작업

1. `idea-plugin/` 모듈 추가 (`intellij-platform-gradle-plugin`)
2. ToolWindow + JCEF 패널 + viewer dist 번들
3. PSI 기반 JPA 분석기 (`reference/ClassIndexer.java` 의 어노테이션 처리 로직을 PSI 로 포팅)
4. ERD 노드 클릭 → 소스 점프
