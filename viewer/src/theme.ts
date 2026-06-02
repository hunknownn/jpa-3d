// 공통 디자인 토큰 + 의미 색상.
// 2D(ErdView2D), 3D(GraphView), 범례(Legend) 가 같은 소스를 공유한다.
// 색/라벨이 한 곳에 모여야 "화면에 보이는 색 == 범례의 색" 이 보장된다.
import { Relation } from "./types";

/** UI 크롬(패널/보더/텍스트) 토큰 — slate 팔레트. */
export const UI = {
  canvas: "#0f172a",
  canvas3d: "#0b1020",
  panel: "#1e293b",
  panelAlt: "#0f172a",
  panelHover: "#273449",
  border: "#3b4a61",       // 카드/패널 경계 — 배경과 대비를 위해 한 단계 밝게
  borderStrong: "#475569",
  text: "#e2e8f0",
  textDim: "#cbd5e1",
  textMuted: "#94a3b8",
  textBright: "#f1f5f9",
  /** 선택/포커스/브랜드 강조 — 카테고리 파랑(entity)과 구분되는 바이올렛. */
  accent: "#8b5cf6",
  danger: "#f87171"
} as const;

// === 색 의미 축 (서로 다른 hue 계열로 분리) ===
// 관계   : 색상환 전역 — 핵심 카디널리티는 고채도, 구조/참조는 보조 톤
// 카테고리: blue / indigo / teal / slate — 관계와 겹치지 않는 hue
// 강조   : violet (UI.accent)

/** 관계(엣지) 색상. 핵심 카디널리티 4종은 hue 를 충분히 벌려 변별. */
export const RELATION_COLOR: Record<Relation, string> = {
  ONE_TO_MANY: "#22c55e",   // green  — 핵심
  MANY_TO_ONE: "#f59e0b",   // amber  — 핵심
  ONE_TO_ONE: "#38bdf8",    // sky    — 핵심
  MANY_TO_MANY: "#e879f9",  // fuchsia— 핵심
  EXTENDS: "#fb7185",       // rose   — 보조(구조)
  IMPLEMENTS: "#fca5a5",    // red-300— 보조(구조)
  USES_ENTITY: "#94a3b8"    // slate  — 보조(참조, 점선)
};

/** 엣지에 표기하는 카디널리티/관계 라벨. */
export const RELATION_LABEL: Record<Relation, string> = {
  ONE_TO_MANY: "1:N",
  MANY_TO_ONE: "N:1",
  ONE_TO_ONE: "1:1",
  MANY_TO_MANY: "M:N",
  EXTENDS: "상속",
  IMPLEMENTS: "구현",
  USES_ENTITY: "참조"
};

/** 구조/참조 관계는 점선(파선)으로 — 카디널리티(실선)와 시각적으로 분리. */
export const RELATION_DASH: Partial<Record<Relation, string>> = {
  EXTENDS: "7 5",
  IMPLEMENTS: "7 5",
  USES_ENTITY: "3 4"
};

/** 엔티티 종류(카드 헤더 / 3D anchor) 색상 — 관계 hue 와 분리된 계열. */
export const KIND_COLOR = {
  entity: "#2563eb",          // blue-600  — 주인공
  mappedSuperclass: "#4338ca", // indigo-700 — 추상
  embeddable: "#0d9488",      // teal-600  — 값 타입
  repository: "#64748b"       // slate-500 — 비엔티티(인프라)
} as const;

export const KIND_LABEL: Record<string, string> = {
  entity: "Entity",
  mappedSuperclass: "@MappedSuperclass",
  embeddable: "@Embeddable",
  repository: "Repository"
};

/** @Inheritance 전략 배지 색상/약어 — 세 전략 간 변별만 되면 충분(작은 띠). */
export const INHERITANCE_COLOR: Record<string, string> = {
  SINGLE_TABLE: "#92400e",   // amber-800
  JOINED: "#6d28d9",         // violet-700
  TABLE_PER_CLASS: "#0e7490" // cyan-700
};

export const INHERITANCE_LABEL: Record<string, string> = {
  SINGLE_TABLE: "SINGLE",
  JOINED: "JOINED",
  TABLE_PER_CLASS: "TPC"
};

/** 컬럼 메타 마커 색상 (PK/FK/unique/index) — 카드 내부 인라인. */
export const COLUMN_MARK = {
  pk: "#fbbf24",      // gold — 키
  fk: "#7dd3fc",      // sky-300 — FK(관계와 의미 연결)
  unique: "#fde047",  // yellow-300
  indexed: "#67e8f9"  // cyan-300
} as const;

// === 간격 / 형태 / 타이포 토큰 ===

/** 4px 베이스 간격 스케일. */
export const SPACE = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 } as const;

/** 모서리 반경 — 컨트롤(작은 요소) / 컨테이너(패널·팝오버) 2단계. */
export const RADIUS = { control: 6, container: 10 } as const;

export const FONT_SANS = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
/** 코드 식별자(컬럼명/타입)용 모노스페이스 — 세로 정렬 + 코드 친화. */
export const FONT_MONO = 'ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace';

/** 공통 고스트 버튼 스타일 — zoom/fit/동기화/재시도 등이 공유. */
export const controlButton: React.CSSProperties = {
  height: 28, minWidth: 28, padding: "0 8px", fontSize: 12,
  display: "inline-flex", alignItems: "center", justifyContent: "center",
  background: "transparent", color: UI.textDim,
  border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control,
  cursor: "pointer", lineHeight: 1
};

/** hex(#rrggbb) → rgba 문자열. 3D 카드의 반투명 헤더 등에 사용. */
export function hexToRgba(hex: string, alpha: number): string {
  const h = hex.replace("#", "");
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/** 노드의 종류 키("entity"|"mappedSuperclass"|"embeddable"|"repository") 판정. */
export function kindKey(entity: { kind: string } | null | undefined): keyof typeof KIND_COLOR {
  if (entity == null) return "repository";
  if (entity.kind === "mappedSuperclass") return "mappedSuperclass";
  if (entity.kind === "embeddable") return "embeddable";
  return "entity";
}
