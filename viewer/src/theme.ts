// 공통 디자인 토큰 + 의미 색상.
// 2D(ErdView2D), 3D(GraphView), 범례(Legend) 가 같은 소스를 공유한다.
// 색/라벨이 한 곳에 모여야 "화면에 보이는 색 == 범례의 색" 이 보장된다.
import { Relation } from "./types";

export type ThemeMode = "dark" | "light";

/** UI 크롬 토큰의 형태. 값은 테마(다크/라이트)에 따라 런타임에 채워진다. */
export interface UiTokens {
  canvas: string;
  canvas3d: string;
  /** 입력창·칩 등 인셋 표면 — 배경(canvas)이 투명일 때도 **불투명**을 유지해 글자 가독성을 지킨다. */
  field: string;
  panel: string;
  panelAlt: string;
  panelHover: string;
  border: string;
  borderStrong: string;
  text: string;
  textDim: string;
  textMuted: string;
  textBright: string;
  /** 선택/포커스/브랜드 강조 — 카테고리 파랑(entity)과 구분되는 바이올렛. */
  accent: string;
  danger: string;
}

/** 다크(기본) — slate 팔레트. 스탠드얼론(플러그인 밖) 기본값이기도 하다. */
const UI_DARK: UiTokens = {
  canvas: "#0f172a",
  canvas3d: "#0b1020",
  field: "#0f172a",
  panel: "#1e293b",
  panelAlt: "#0f172a",
  panelHover: "#273449",
  border: "#3b4a61",       // 카드/패널 경계 — 배경과 대비를 위해 한 단계 밝게
  borderStrong: "#475569",
  text: "#e2e8f0",
  textDim: "#cbd5e1",
  textMuted: "#94a3b8",
  textBright: "#f1f5f9",
  accent: "#8b5cf6",
  danger: "#f87171"
};

/** 라이트 — 다크와 동일한 의미 축을 밝게 반전(slate 계열 유지). IDE 라이트 테마에서 사용. */
const UI_LIGHT: UiTokens = {
  canvas: "#fbfcfd",       // 2D 배경 — 회색기를 빼고 거의 흰색에 가깝게
  canvas3d: "#eef1f5",     // 3D 씬 배경 — 다크 카드와 대비되도록 약간 밝은 톤
  field: "#ffffff",
  panel: "#ffffff",
  panelAlt: "#f6f8fa",
  panelHover: "#eef1f4",
  border: "#e2e6ea",       // 경계도 한 단계 옅게 — 회색 띠가 도드라지지 않도록
  borderStrong: "#aab3bf",
  text: "#1e293b",
  textDim: "#334155",
  textMuted: "#64748b",
  textBright: "#0f172a",
  accent: "#7c3aed",       // 밝은 배경 대비를 위해 다크보다 한 단계 진한 바이올렛
  danger: "#dc2626"
};

/**
 * 활성 UI 크롬 토큰. **동일 객체 참조**를 유지한 채 [applyTheme] 가 내용만 교체한다.
 * 컴포넌트는 렌더 시점에 `UI.x` 를 읽으므로, 재렌더만 트리거되면 새 색이 반영된다.
 */
export const UI: UiTokens = { ...UI_DARK };

/**
 * 3D 노드 카드는 IDE 테마와 무관한 **고정 다크 디자인**(3D 공간에 떠 있는 '물체'라
 * 라이트 배경 위에서도 그대로 둔다). 카드 내부 색은 테마러블한 [UI] 와 분리한다.
 */
export const CARD3D = {
  text: "#e2e8f0",
  textMuted: "#94a3b8",
  inhFallback: "#475569"
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
  USES_ENTITY: "#94a3b8",   // slate  — 보조(참조, 점선)
  SOFT_REF: "#94a3b8"       // slate  — 약한 ID 참조(점선). 경계 스타일은 PR4 에서 boundary 로 강조.
};

// 관계/종류/상속 전략의 **텍스트 라벨**은 i18n 으로 옮겼다(언어별).
//   relationLabel / kindLabel / inheritanceLabel (./i18n)
// 색/형태 토큰만 이 파일에 남긴다 — "화면의 색 == 범례의 색" 은 여전히 여기 한 곳에서 보장.

/** 구조/참조 관계는 점선(파선)으로 — 카디널리티(실선)와 시각적으로 분리. */
export const RELATION_DASH: Partial<Record<Relation, string>> = {
  EXTENDS: "7 5",
  IMPLEMENTS: "7 5",
  USES_ENTITY: "3 4",
  SOFT_REF: "3 4"
};

/** 엔티티 종류(카드 헤더 / 3D anchor) 색상 — 관계 hue 와 분리된 계열. */
export const KIND_COLOR = {
  entity: "#2563eb",          // blue-600  — 주인공
  mappedSuperclass: "#4338ca", // indigo-700 — 추상
  embeddable: "#0d9488",      // teal-600  — 값 타입
  repository: "#64748b"       // slate-500 — 비엔티티(인프라)
} as const;

/** @Inheritance 전략 배지 색상/약어 — 세 전략 간 변별만 되면 충분(작은 띠). */
export const INHERITANCE_COLOR: Record<string, string> = {
  SINGLE_TABLE: "#92400e",   // amber-800
  JOINED: "#6d28d9",         // violet-700
  TABLE_PER_CLASS: "#0e7490" // cyan-700
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

function computeControlButton(): React.CSSProperties {
  return {
    height: 28, minWidth: 28, padding: "0 8px", fontSize: 12,
    display: "inline-flex", alignItems: "center", justifyContent: "center",
    background: "transparent", color: UI.textDim,
    border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control,
    cursor: "pointer", lineHeight: 1
  };
}

/**
 * 공통 고스트 버튼 스타일 — zoom/fit/동기화/재시도 등이 공유.
 * [UI] 와 마찬가지로 **동일 참조**를 유지(테마 전환 시 내용만 갱신)하므로,
 * 이를 복사해 둔 파생 스타일(zoomBtnStyle 등)도 자동으로 새 색을 따라간다.
 */
export const controlButton: React.CSSProperties = computeControlButton();

/**
 * 호스트(plugin)가 주입한 테마를 적용. [UI]/[controlButton] 객체를 in-place 로 갱신한다.
 *
 * @param hostBg IDE 패널 배경색(hex). 라이트 테마에서 캔버스 배경을 이 색으로 맞춰
 *   주변 IDE 패널(예: Gradle 툴윈도우)과 이음매 없이 붙인다. 다크는 자체 팔레트 유지.
 * @param transparent true 면 캔버스/문서 배경을 투명으로 칠한다 — 비불투명 OSR JCEF 뒤의
 *   IDE 배경(월페이퍼)이 비치게 하기 위함. 노드/카드/툴바(panel 계열)는 불투명 유지.
 */
export function applyTheme(
  mode: ThemeMode | string | undefined,
  hostBg?: string,
  transparent?: boolean
): void {
  const tokens: UiTokens = { ...(mode === "light" ? UI_LIGHT : UI_DARK) };
  if (mode === "light" && hostBg) {
    // 카드(panel) 는 흰색으로 띄워 두고, 배경 계열만 IDE 패널색과 동일하게.
    tokens.canvas = hostBg;
    tokens.canvas3d = hostBg;
    tokens.panelAlt = hostBg;
    tokens.field = hostBg;
  }
  if (transparent) {
    // 배경 계열만 투명 — chrome(panel/border/text)은 그대로 불투명.
    tokens.canvas = "transparent";
    tokens.canvas3d = "transparent";
    tokens.panelAlt = "transparent";
  }
  Object.assign(UI, tokens);
  Object.assign(controlButton, computeControlButton());

  // index.html 의 정적 최하단 배경(html/body/#root)까지 동기화한다.
  // 투명이면 transparent, 아니면 현재 캔버스색으로 — 라이트에서 #0b1020 이 새는 것도 함께 막는다.
  const docBg = transparent ? "transparent" : UI.canvas;
  const root = typeof document !== "undefined" ? document.getElementById("root") : null;
  if (typeof document !== "undefined") {
    document.documentElement.style.background = docBg;
    document.body.style.background = docBg;
  }
  if (root) root.style.background = docBg;
}

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
