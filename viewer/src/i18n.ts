// 뷰어 경량 i18n — 사전 + t() 헬퍼.
//
// 표시 언어는 플러그인(BridgeInjector)이 `window.__JPA3D_LOCALE__` 로 주입한다.
// 그 값은 플러그인 언어 설정(Jpa3dSettings.UiLang, 기본=IDE 로케일)을 따른 결과다.
// 스탠드얼론(브라우저 fixture)에서는 주입이 없어 영어(기본)로 동작한다.
//
// setLocale 은 모듈 변수만 바꾸므로, 런타임에 언어가 바뀌면 React 트리를 다시
// 렌더해야 라벨이 갱신된다(ErdApp 가 locale 틱 state 로 처리).

type Lang = "en" | "ko";
type Dict = Record<string, string>;

const en: Dict = {
  // 관계 라벨 (theme 의 색과 짝) — 카디널리티는 언어 무관, 구조 관계만 번역.
  "rel.ONE_TO_MANY": "1:N",
  "rel.MANY_TO_ONE": "N:1",
  "rel.ONE_TO_ONE": "1:1",
  "rel.MANY_TO_MANY": "M:N",
  "rel.EXTENDS": "Inherit",
  "rel.IMPLEMENTS": "Impl",
  "rel.USES_ENTITY": "Uses",
  // 엔티티 종류 / 상속 전략 배지 (대부분 언어 무관)
  "kind.entity": "Entity",
  "kind.mappedSuperclass": "@MappedSuperclass",
  "kind.embeddable": "@Embeddable",
  "kind.repository": "Repository",
  "inh.SINGLE_TABLE": "SINGLE",
  "inh.JOINED": "JOINED",
  "inh.TABLE_PER_CLASS": "TPC",

  // 툴바 / 검색
  "scope.label": "Scope:",
  "scope.all": "All",
  "scope.all.title": "Show all entities",
  "scope.seed": "Center",
  "scope.seed.title": "Only the part reachable from the selected centers",
  "seeds.label": "Centers:",
  "seeds.package.prefix": "Package (incl. sub): ",
  "seeds.remove": "Remove",
  "seeds.remove.aria": "Remove {0}",
  "seeds.clearAll": "Clear all",
  "seeds.clearAll.title": "Remove all centers",
  "depth.label": "Depth:",
  "depth.aria": "Connection depth",
  "depth.title.disabled": "No connected neighbors",
  "depth.title": "{0} hop(s) from seed (max {1})",
  "options.label": "Show:",
  "options.title": "Display options",
  "options.summary": "Relations + {0}",
  "options.alwaysRelations": "Relations are always shown",
  "options.columns": "Columns",
  "options.columns.hint": "Entity columns/types",
  "options.repository": "Repositories",
  "options.repository.hint": "Spring Data Repository",
  "options.extends": "Inheritance",
  "options.extends.hint": "@MappedSuperclass / EXTENDS",
  "search.placeholder.all": "Search nodes (highlight)",
  "search.placeholder.seed": "Search & add entities/packages…",
  "search.clear": "Clear search",
  "search.noResults": "No items match \"{0}\".",
  "search.suggest.package": "Package (incl. sub)",
  "toolbar.sync": "Sync",
  "toolbar.syncing": "Syncing…",
  "toolbar.resync": "Re-analyze ERD from current project state",
  "toolbar.counts": "Nodes {0} · Edges {1}",
  "view.help": "Usage help",

  // 숨긴 노드 (우클릭으로 view 에서 제거 → 툴바 메뉴로 복원)
  "hidden.label": "Removed:",
  "hidden.title": "Nodes removed from the analysis",
  "hidden.summary": "{0} removed",
  "hidden.menuTitle": "Removed nodes — click to restore",
  "hidden.restore": "Restore",
  "hidden.restore.aria": "Restore {0}",
  "hidden.restoreAll": "Restore all",

  // 도움말
  "help.title": "Usage",
  "help.close": "Close",
  "help.k.click": "Click",
  "help.v.click": "Open the entity's source file",
  "help.k.cmdClick": "Cmd/Ctrl + click",
  "help.v.cmdClick": "Open the source in a split editor",
  "help.k.dblClick": "Double-click",
  "help.v.dblClick": "Center the view on that node (re-explore as a seed)",
  "help.k.rightClick": "Right-click",
  "help.v.rightClick": "Remove the node from the analysis (orphaned neighbors are removed too) · undo with Cmd/Ctrl+Z or restore from the toolbar",
  "help.k.drag2d": "Drag (2D)",
  "help.v.drag2d": "Move node position · restore with ↺",
  "help.k.colHover": "Hover a column",
  "help.v.colHover": "Highlight relations connected to that entity",
  "help.k.search": "Search",
  "help.v.search": "Type, then ↑/↓ to move, Enter to pick",
  "help.k.wheel": "Wheel / +−",
  "help.v.wheel": "Zoom; 'fit' to see everything",
  "help.k.drag3d": "Drag (3D)",
  "help.v.drag3d": "Left-drag to rotate · wheel/right-drag to pan",

  // 빈/인덱싱/오류 상태
  "empty.title": "No JPA entities found in this project.",
  "empty.desc": "Make sure the analyzed sources are built and the package scope settings are correct.",
  "seedPrompt.title": "Select a center.",
  "seedPrompt.desc": "Search and add an entity or package in the search box above to draw the graph around the selected centers. You can mix several.",
  "indexing.title": "IDE indexing in progress…",
  "indexing.desc": "Analysis results will appear automatically once indexing finishes.",
  "error.title": "An error occurred during analysis.",
  "error.retry": "↻ Retry",

  // 범례
  "legend.button": "Legend",
  "legend.button.title": "Show legend",
  "legend.3d.layer.before": "Vertical ",
  "legend.3d.layer.word": "layers",
  "legend.3d.layer.after": " = distance (hops) from the center. Cards expand as you get closer.",
  "legend.section.relations": "Relations",
  "legend.section.kinds": "Entity kinds",
  "legend.section.columns": "Column marks",
  "legend.section.inheritance": "Inheritance strategy badges",
  "legend.relations.foot.2d": "Line ends — bar │ = 1, crow's foot ⪛ = N",
  "legend.relations.foot.3d": "Arrow direction = the side the relationship points to",
  "legend.col.pk": "Primary key (PK)",
  "legend.col.fk": "Foreign key (FK)",
  "legend.col.unique": "unique constraint",
  "legend.col.indexed": "indexed",
  "legend.col.notnull": "NOT NULL (trailing * after type)",
  "legend.relHint.EXTENDS": "Inheritance / @MappedSuperclass",
  "legend.relHint.USES_ENTITY": "Repository → Entity",
  "legend.inhHint.SINGLE_TABLE": "Single table",
  "legend.inhHint.JOINED": "Joined tables",
  "legend.inhHint.TABLE_PER_CLASS": "Table per concrete class",

  // 2D 컨트롤
  "erd2d.direction.title": "Toggle direction (current {0})",
  "erd2d.direction.horizontal": "horizontal",
  "erd2d.direction.vertical": "vertical",
  "erd2d.direction.aria": "Toggle layout direction",
  "ctrl.zoomOut": "Zoom out",
  "ctrl.zoomIn": "Zoom in",
  "ctrl.fit": "Fit to screen",
  "ctrl.fit.aria": "Fit to screen",
  "ctrl.resetPos": "Reset positions — return dragged nodes to auto layout",
  "ctrl.resetPos.aria": "Reset positions"
};

const ko: Dict = {
  "rel.ONE_TO_MANY": "1:N",
  "rel.MANY_TO_ONE": "N:1",
  "rel.ONE_TO_ONE": "1:1",
  "rel.MANY_TO_MANY": "M:N",
  "rel.EXTENDS": "상속",
  "rel.IMPLEMENTS": "구현",
  "rel.USES_ENTITY": "참조",
  "kind.entity": "Entity",
  "kind.mappedSuperclass": "@MappedSuperclass",
  "kind.embeddable": "@Embeddable",
  "kind.repository": "Repository",
  "inh.SINGLE_TABLE": "SINGLE",
  "inh.JOINED": "JOINED",
  "inh.TABLE_PER_CLASS": "TPC",

  "scope.label": "범위:",
  "scope.all": "전체",
  "scope.all.title": "모든 엔티티 표시",
  "scope.seed": "중심",
  "scope.seed.title": "선택한 중심에서 도달 가능한 부분만",
  "seeds.label": "중심:",
  "seeds.package.prefix": "패키지(하위 포함): ",
  "seeds.remove": "제거",
  "seeds.remove.aria": "{0} 제거",
  "seeds.clearAll": "모두 지우기",
  "seeds.clearAll.title": "모든 중심 제거",
  "depth.label": "깊이:",
  "depth.aria": "연결 깊이",
  "depth.title.disabled": "연결된 이웃이 없습니다",
  "depth.title": "seed 로부터 {0} 홉 (최대 {1})",
  "options.label": "표시:",
  "options.title": "표시 옵션",
  "options.summary": "관계 + {0}",
  "options.alwaysRelations": "관계는 항상 표시됩니다",
  "options.columns": "컬럼",
  "options.columns.hint": "엔티티 컬럼/타입",
  "options.repository": "리포지토리",
  "options.repository.hint": "Spring Data Repository",
  "options.extends": "상속",
  "options.extends.hint": "@MappedSuperclass / EXTENDS",
  "search.placeholder.all": "노드 검색 (하이라이트)",
  "search.placeholder.seed": "엔티티·패키지 검색 후 추가…",
  "search.clear": "검색 초기화",
  "search.noResults": "\"{0}\" 와 일치하는 항목이 없습니다.",
  "search.suggest.package": "패키지 (하위 포함)",
  "toolbar.sync": "동기화",
  "toolbar.syncing": "동기화 중…",
  "toolbar.resync": "현재 프로젝트 상태로 ERD 재분석",
  "toolbar.counts": "노드 {0} · 관계 {1}",
  "view.help": "사용법 도움말",

  "hidden.label": "제거:",
  "hidden.title": "분석에서 제거한 노드",
  "hidden.summary": "{0}개 제거",
  "hidden.menuTitle": "제거한 노드 — 클릭하면 복원",
  "hidden.restore": "복원",
  "hidden.restore.aria": "{0} 복원",
  "hidden.restoreAll": "모두 복원",

  "help.title": "사용법",
  "help.close": "닫기",
  "help.k.click": "클릭",
  "help.v.click": "엔티티의 소스 파일로 이동",
  "help.k.cmdClick": "Cmd/Ctrl + 클릭",
  "help.v.cmdClick": "소스를 분할 에디터로 열기",
  "help.k.dblClick": "더블클릭",
  "help.v.dblClick": "그 노드를 중심으로 보기 (seed 로 다시 탐색)",
  "help.k.rightClick": "우클릭",
  "help.v.rightClick": "그 노드를 분석에서 제거(연관 고립 노드도 함께 제거) · Cmd/Ctrl+Z 로 되돌리거나 툴바에서 복원",
  "help.k.drag2d": "드래그 (2D)",
  "help.v.drag2d": "노드 위치 이동 · ↺ 로 복원",
  "help.k.colHover": "컬럼에 마우스",
  "help.v.colHover": "해당 엔티티에 연결된 관계 강조",
  "help.k.search": "검색",
  "help.v.search": "입력 후 ↑/↓ 이동, Enter 로 선택",
  "help.k.wheel": "휠 / +−",
  "help.v.wheel": "확대·축소, 'fit' 으로 전체 보기",
  "help.k.drag3d": "드래그 (3D)",
  "help.v.drag3d": "좌클릭 회전 · 휠클릭/우클릭 이동",

  "empty.title": "이 프로젝트에서 JPA Entity를 찾지 못했습니다.",
  "empty.desc": "분석 대상이 빌드되어 있고 패키지 범위 설정이 올바른지 확인해 주세요.",
  "seedPrompt.title": "중심을 선택하세요.",
  "seedPrompt.desc": "상단 검색창에서 엔티티나 패키지를 검색해 추가하면, 선택한 중심들 주변 그래프를 그립니다. 여러 개를 섞어서 추가할 수 있습니다.",
  "indexing.title": "IDE 인덱싱 진행 중…",
  "indexing.desc": "인덱싱이 끝나면 자동으로 분석 결과가 표시됩니다.",
  "error.title": "분석 중 오류가 발생했습니다.",
  "error.retry": "↻ 다시 시도",

  "legend.button": "범례",
  "legend.button.title": "범례 보기",
  "legend.3d.layer.before": "수직 ",
  "legend.3d.layer.word": "층",
  "legend.3d.layer.after": " = 중심에서의 거리(홉). 가까이 가면 카드가 펼쳐집니다.",
  "legend.section.relations": "관계",
  "legend.section.kinds": "엔티티 종류",
  "legend.section.columns": "컬럼 표기",
  "legend.section.inheritance": "상속 전략 배지",
  "legend.relations.foot.2d": "선 끝 표기 — 막대 │ = 1, 까마귀발 ⪛ = N",
  "legend.relations.foot.3d": "화살표 방향 = 관계의 향하는 쪽",
  "legend.col.pk": "기본키 (PK)",
  "legend.col.fk": "외래키 (FK)",
  "legend.col.unique": "unique 제약",
  "legend.col.indexed": "인덱스 포함",
  "legend.col.notnull": "NOT NULL (타입 뒤 *)",
  "legend.relHint.EXTENDS": "상속 / @MappedSuperclass",
  "legend.relHint.USES_ENTITY": "Repository → Entity",
  "legend.inhHint.SINGLE_TABLE": "단일 테이블",
  "legend.inhHint.JOINED": "조인 테이블",
  "legend.inhHint.TABLE_PER_CLASS": "구체 클래스별 테이블",

  "erd2d.direction.title": "방향 전환 (현재 {0})",
  "erd2d.direction.horizontal": "가로",
  "erd2d.direction.vertical": "세로",
  "erd2d.direction.aria": "레이아웃 방향 전환",
  "ctrl.zoomOut": "축소",
  "ctrl.zoomIn": "확대",
  "ctrl.fit": "전체 보기 (화면에 맞춤)",
  "ctrl.fit.aria": "전체 보기",
  "ctrl.resetPos": "위치 초기화 — 드래그한 노드를 자동 레이아웃으로 되돌림",
  "ctrl.resetPos.aria": "위치 초기화"
};

const dicts: Record<Lang, Dict> = { en, ko };

let currentLang: Lang = "en";

/** "ko", "ko-KR" 등 → 지원 언어로 정규화. 미지원이면 영어. */
export function setLocale(loc: string | undefined | null): void {
  currentLang = loc && loc.toLowerCase().startsWith("ko") ? "ko" : "en";
}

export function getLocale(): Lang {
  return currentLang;
}

/**
 * 키를 현재 언어로 번역. `{0}`,`{1}` … 위치 인자 치환 지원.
 * 키가 없으면 fallback → 영어 → 키 순으로 폴백.
 */
export function t(key: string, params?: Array<string | number>, fallback?: string): string {
  let s = dicts[currentLang][key] ?? en[key] ?? fallback ?? key;
  if (params) {
    s = s.replace(/\{(\d+)\}/g, (_m, i) => String(params[Number(i)] ?? ""));
  }
  return s;
}

// === 의미 라벨 헬퍼 — theme 의 색 맵과 짝을 이루는 텍스트 ===
export const relationLabel = (rel: string): string => t(`rel.${rel}`, undefined, rel);
export const kindLabel = (kind: string): string => t(`kind.${kind}`, undefined, kind);
export const inheritanceLabel = (strategy: string): string => t(`inh.${strategy}`, undefined, strategy);
