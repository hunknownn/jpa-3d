import { useEffect, useMemo, useRef, useState } from "react";
import GraphView, { GraphHandle } from "./GraphView";
import ErdView2D, { Erd2dHandle } from "./ErdView2D";
import Legend from "./Legend";
import { fetchErd, navigateToSource, searchErd } from "./api";
import { GraphData, GraphNode } from "./types";
import { UI, RADIUS, controlButton, KIND_COLOR, kindKey, applyTheme } from "./theme";
import { IconSync, IconSearch } from "./Icons";
import { bfsDistances, endpointId, resolveSeedIdsMulti, SeedRef } from "./graphDepth";
import { t, setLocale } from "./i18n";

type Scope = "all" | "seed";
type ViewMode = "3d" | "2d";

interface ErdParams {
  scope: Scope;
  /** 다중 시드 — 엔티티(fqn)/패키지(package) 를 섞어 여러 개. 비어 있으면 미선택. */
  seeds: SeedRef[];
  /** 컬럼 표시 여부 (관계는 항상 표시). */
  showColumns: boolean;
  /** Repository 노드/USES_ENTITY 엣지 표시 여부 (컬럼과 독립 — "리포지토리만" 가능). */
  showRepository: boolean;
  depth: number;
  view: ViewMode;
  showExtends: boolean;
}

const EMPTY_DATA: GraphData = { seed: "", depth: 0, nodes: [], links: [] };

/**
 * envelope fetch 깊이 — seed 연결성분 전체를 받아오기 위한 충분히 큰 값.
 * 백엔드 BFS 는 frontier 가 비면 조기 종료하므로 실제 비용은 그래프 지름까지만 든다.
 * 이 한 번의 fetch 로 받아둔 데이터를 클라이언트에서 depth 별로 잘라 쓴다.
 */
const FULL_DEPTH = 1000;

function readParamsFromHash(): ErdParams {
  const hash = window.location.hash.slice(1);
  const qIdx = hash.indexOf("?");
  const qs = qIdx >= 0 ? hash.slice(qIdx + 1) : "";
  const p = new URLSearchParams(qs);
  const scope = (p.get("scope") === "seed" ? "seed" : "all") as Scope;
  const showColumns = p.get("col") === "1";
  const showRepository = p.get("repo") === "1";
  const depth = parseInt(p.get("depth") ?? "2", 10);
  const seeds = parseSeeds(p);
  const view: ViewMode = p.get("view") === "2d" ? "2d" : "3d";
  // showExtends 미지정이면 기본 true (기존 동작 유지)
  const showExtends = p.get("extends") !== "0";
  return { scope, seeds, showColumns, showRepository, depth: Number.isFinite(depth) ? depth : 2, view, showExtends };
}

/**
 * 해시에서 시드 목록을 파싱한다.
 *  - 신형: `seeds=fqn:com.app.User,package:com.app.order` (type:value 를 콤마로)
 *  - 구형(하위호환): 단일 `seed` + `seedType` → 시드 1개로 변환
 */
function parseSeeds(p: URLSearchParams): SeedRef[] {
  const raw = p.get("seeds");
  if (raw) {
    return raw
      .split(",")
      .map((tok): SeedRef => {
        const i = tok.indexOf(":");
        if (i < 0) return { value: tok, type: "fqn" };
        return { value: tok.slice(i + 1), type: tok.slice(0, i) === "package" ? "package" : "fqn" };
      })
      .filter((s) => s.value);
  }
  const legacy = p.get("seed");
  if (legacy) {
    return [{ value: legacy, type: p.get("seedType") === "package" ? "package" : "fqn" }];
  }
  return [];
}

/** 시드 한 개 동등성. */
function sameSeed(a: SeedRef, b: SeedRef): boolean {
  return a.value === b.value && a.type === b.type;
}

/** plugin(BridgeInjector)이 주입하는 설정 기반 뷰어 기본값. 스탠드얼론(브라우저)에선 없음. */
interface ViewerDefaults {
  view?: string; scope?: string; depth?: number;
  col?: boolean; repo?: boolean; extends?: boolean;
}

/** 모듈 로드 시점의 해시 쿼리 — 비어 있으면 "fresh open" 으로 보고 설정 기본값을 적용한다. */
const INITIAL_QS: string = (() => {
  const hash = window.location.hash.slice(1);
  const qIdx = hash.indexOf("?");
  return qIdx >= 0 ? hash.slice(qIdx + 1) : "";
})();

/** window.__JPA3D_DEFAULTS__ → ErdParams. 주입 전이면 null. */
function readViewerDefaults(): ErdParams | null {
  const d = (window as unknown as { __JPA3D_DEFAULTS__?: ViewerDefaults }).__JPA3D_DEFAULTS__;
  if (!d) return null;
  return {
    scope: d.scope === "seed" ? "seed" : "all",
    seeds: [],
    showColumns: !!d.col,
    showRepository: !!d.repo,
    showExtends: d.extends !== false,
    depth: Number.isFinite(d.depth) ? (d.depth as number) : 2,
    view: d.view === "2d" ? "2d" : "3d"
  };
}

function writeParamsToHash(params: ErdParams) {
  const p = new URLSearchParams();
  p.set("scope", params.scope);
  if (params.showColumns) p.set("col", "1");
  if (params.showRepository) p.set("repo", "1");
  p.set("view", params.view);
  if (!params.showExtends) p.set("extends", "0");
  if (params.scope === "seed") {
    if (params.seeds.length) {
      p.set("seeds", params.seeds.map((s) => `${s.type}:${s.value}`).join(","));
    }
    p.set("depth", String(params.depth));
  }
  const newHash = `/erd?${p.toString()}`;
  if (window.location.hash.slice(1) !== newHash) {
    window.location.hash = newHash;
  }
}

/** 상단 툴바 높이 — 그래프 영역 오프셋/팝오버 위치와 공유. */
const TOOLBAR_H = 38;

/** 검색 드롭다운에 표시할 정규화된 제안 항목 — 패키지/노드 두 종류를 한 모양으로. */
interface SuggestItem {
  key: string;
  primary: string;
  secondary: string;
  onPick: () => void;
}

export default function ErdApp() {
  const [params, setParams] = useState<ErdParams>(() => readParamsFromHash());
  // seed 연결성분 전체(=envelope) — depth 필터 전의 원본. depth 변경은 이걸 다시 받지 않고
  // 아래 useMemo 에서 로컬로 자른다.
  const [fullData, setFullData] = useState<GraphData>(EMPTY_DATA);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [suggests, setSuggests] = useState<GraphNode[]>([]);
  const [query, setQuery] = useState("");
  const [size, setSize] = useState({ w: window.innerWidth, h: window.innerHeight });
  // 툴바는 좁은 폭에서 wrap 되어 높이가 가변 — 실제 높이를 측정해 그래프 영역 오프셋에 반영.
  // (고정값을 쓰면 wrap 시 좌상단 뷰 토글이 둘째 줄에 가려진다.)
  const [toolbarH, setToolbarH] = useState(TOOLBAR_H);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  // 우클릭으로 분석에서 제거한 노드 id 집합 — 거리/depth 재계산에 반영되고, 그로 인해 고립된
  // 연관 노드는 연쇄로 사라진다. 툴바 메뉴로 복원. (URL 비저장, 세션 한정)
  const [removedIds, setRemovedIds] = useState<Set<string>>(new Set());
  // 검색 드롭다운: 키보드 탐색 인덱스 + 열림 상태
  const [activeIndex, setActiveIndex] = useState(-1);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  // 언어 변경 시 트리 재렌더 트리거 — setLocale 은 모듈 변수만 바꾸므로 state 로 한 번 흔든다.
  const [, setLocaleTick] = useState(0);
  // 테마 변경 시 트리 전체를 재렌더해 새 UI 색을 반영하기 위한 틱.
  const [, setThemeTick] = useState(0);
  const graphRef = useRef<GraphHandle>(null);
  const erd2dRef = useRef<Erd2dHandle>(null);

  // === Plugin → Viewer snapshot 브리지 ===
  // 최신 view mode 와 ref 를 effect 안에서 잡아 외부에 전역으로 노출.
  // plugin 측 ViewerSnapshot 가 window.__JPA3D_SNAPSHOT__/__JPA3D_VIEW_STATE__ 을 호출.
  useEffect(() => {
    const w = window as unknown as {
      __JPA3D_VIEW_STATE__?: () => { mode: ViewMode };
      __JPA3D_SNAPSHOT__?: (format: "png" | "svg") => Promise<{ format: string; data?: string; error?: string }>;
    };
    w.__JPA3D_VIEW_STATE__ = () => ({ mode: params.view });
    w.__JPA3D_SNAPSHOT__ = async (format) => {
      try {
        if (params.view === "2d") {
          if (format === "svg") {
            const data = erd2dRef.current?.snapshotSvg() ?? "";
            return data ? { format, data } : { format, error: "2D view not ready" };
          }
          const data = (await erd2dRef.current?.snapshotPng()) ?? "";
          return data ? { format, data } : { format, error: "2D view not ready" };
        }
        // 3D
        if (format === "svg") return { format, error: "SVG snapshot is not supported in 3D view" };
        const data = graphRef.current?.snapshotPng() ?? "";
        return data ? { format, data } : { format, error: "3D view not ready" };
      } catch (e) {
        return { format, error: String((e as Error)?.message ?? e) };
      }
    };
    return () => {
      delete w.__JPA3D_VIEW_STATE__;
      delete w.__JPA3D_SNAPSHOT__;
    };
  }, [params.view]);

  // 윈도우 리사이즈
  useEffect(() => {
    const onResize = () => setSize({ w: window.innerWidth, h: window.innerHeight });
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  // 툴바 높이 측정 (wrap 으로 줄 수가 바뀌면 갱신)
  useEffect(() => {
    const el = toolbarRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setToolbarH(el.offsetHeight));
    ro.observe(el);
    setToolbarH(el.offsetHeight);
    return () => ro.disconnect();
  }, []);

  // URL ↔ state 동기화
  useEffect(() => {
    writeParamsToHash(params);
  }, [params]);

  // 설정(설정 → 도구 → JPA 3D) 기반 뷰어 기본값 1회 적용.
  // 해시 없이 fresh 로 열렸을 때만, 그리고 defaults 주입 시점(bridge-ready)을 기다려 한 번만 적용한다.
  // 공유/기존 해시로 열렸으면(INITIAL_QS 존재) 그 상태를 우선해 건드리지 않는다.
  const defaultsAppliedRef = useRef(false);
  useEffect(() => {
    if (INITIAL_QS) return;
    const apply = () => {
      if (defaultsAppliedRef.current) return;
      const d = readViewerDefaults();
      if (!d) return;
      defaultsAppliedRef.current = true;
      setParams(d);
    };
    apply(); // 이미 주입돼 있으면 즉시
    window.addEventListener("jpa3d:bridge-ready", apply);
    return () => window.removeEventListener("jpa3d:bridge-ready", apply);
  }, []);

  // 표시 언어 — plugin(BridgeInjector)이 window.__JPA3D_LOCALE__ 로 주입.
  // 주입은 페이지 로드 후(bridge-ready) 일어날 수 있고, 설정 변경 후 재오픈 시엔
  // apply-defaults 와 함께 갱신된다. 두 시점 모두 다시 적용하고 트리를 재렌더한다.
  useEffect(() => {
    const applyLocale = () => {
      const loc = (window as unknown as { __JPA3D_LOCALE__?: string }).__JPA3D_LOCALE__;
      if (loc) {
        setLocale(loc);
        setLocaleTick((x) => x + 1);
      }
    };
    applyLocale();
    window.addEventListener("jpa3d:bridge-ready", applyLocale);
    window.addEventListener("jpa3d:apply-defaults", applyLocale);
    return () => {
      window.removeEventListener("jpa3d:bridge-ready", applyLocale);
      window.removeEventListener("jpa3d:apply-defaults", applyLocale);
    };
  }, []);

  // IDE 테마(다크/라이트) — plugin(BridgeInjector)이 window.__JPA3D_THEME__ 로 주입.
  // 최초 주입(bridge-ready)과 IDE 테마 전환(theme-change, LafManagerListener) 두 시점 모두
  // UI 토큰을 교체하고 트리를 재렌더한다. (3D 노드 카드는 고정 다크라 영향 없음.)
  useEffect(() => {
    const applyHostTheme = () => {
      const g = window as unknown as {
        __JPA3D_THEME__?: string;
        __JPA3D_THEME_BG__?: string;
        __JPA3D_TRANSPARENT__?: string;
      };
      applyTheme(g.__JPA3D_THEME__, g.__JPA3D_THEME_BG__, g.__JPA3D_TRANSPARENT__ === "true");
      setThemeTick((x) => x + 1);
    };
    applyHostTheme();
    window.addEventListener("jpa3d:bridge-ready", applyHostTheme);
    window.addEventListener("jpa3d:theme-change", applyHostTheme);
    return () => {
      window.removeEventListener("jpa3d:bridge-ready", applyHostTheme);
      window.removeEventListener("jpa3d:theme-change", applyHostTheme);
    };
  }, []);

  // 툴윈도우 재오픈 시 plugin 이 바뀐 기본값을 push (jpa3d:apply-defaults).
  // 살아있는 뷰어 인스턴스라 페이지 리로드가 없으므로, 이 이벤트로 파라미터를 기본값으로 리셋한다.
  // (위 1회용 가드와 달리 매번 적용 — push 자체가 "설정이 바뀌었을 때만" 오므로 안전)
  useEffect(() => {
    const onApply = () => {
      const d = readViewerDefaults();
      if (d) setParams(d);
    };
    window.addEventListener("jpa3d:apply-defaults", onApply);
    return () => window.removeEventListener("jpa3d:apply-defaults", onApply);
  }, []);

  // plugin(프로젝트 트리 우클릭 → "이 패키지 추가" 등)에서 시드를 push.
  // detail: { value: string, type?: "fqn" | "package" } → 중심 모드로 전환하며 시드 추가.
  // __JPA3D_ADD_SEED_READY__: plugin 이 "리스너가 준비됐는지" 폴링할 수 있게 하는 플래그.
  // (툴윈도우를 새로 열면 React mount 전에 dispatch 돼 이벤트가 유실될 수 있어서.)
  useEffect(() => {
    const onAddSeed = (e: Event) => {
      const detail = (e as CustomEvent).detail as { value?: string; type?: string } | undefined;
      if (!detail?.value) return;
      addSeed({ value: detail.value, type: detail.type === "package" ? "package" : "fqn" });
    };
    window.addEventListener("jpa3d:add-seed", onAddSeed);
    (window as unknown as { __JPA3D_ADD_SEED_READY__?: boolean }).__JPA3D_ADD_SEED_READY__ = true;
    return () => {
      window.removeEventListener("jpa3d:add-seed", onAddSeed);
      delete (window as unknown as { __JPA3D_ADD_SEED_READY__?: boolean }).__JPA3D_ADD_SEED_READY__;
    };
    // addSeed 는 setState 함수형 업데이트만 쓰므로 최초 클로저로 안전 — 재구독 불필요.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ERD 데이터 fetch — 다중 시드는 클라이언트에서 필터하므로 항상 **전체 그래프**(envelope)를
  // 한 번 받아두고, seed/depth 필터는 아래 useMemo 에서 로컬로 적용한다.
  // 그래서 시드를 추가/제거해도 재요청이 없고(즉시 반응), depth 슬라이더도 재요청 없이 동작한다.
  // 단, 중심 모드인데 아직 시드가 없으면 빈 캔버스 + 안내만 보이면 되므로 fetch 를 건너뛴다.
  // (refreshTick = 동기화/인덱싱 재시도 트리거)
  const skipFetch = params.scope === "seed" && params.seeds.length === 0;
  useEffect(() => {
    if (skipFetch) {
      setFullData(EMPTY_DATA);
      return;
    }
    setLoading(true);
    setError(null);
    fetchErd({
      scope: "all",
      depth: FULL_DEPTH,
      showColumns: params.showColumns,
      showRepository: params.showRepository,
      showExtends: params.showExtends
    })
      .then(setFullData)
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [
    skipFetch,
    params.showColumns, params.showRepository, params.showExtends,
    refreshTick
  ]);

  // 선택한 모든 시드로부터의 홉 거리(union BFS) + 최대 깊이(슬라이더 상한). 전체 모드면 필터 비활성.
  // 제거된 노드는 그래프에서 빼고 거리를 재계산한다 → 그 노드를 유일 경로로 닿던 노드는 도달
  // 불가가 되어 거리맵에서 빠지고, depth 필터에서 자동으로(=연쇄로) 사라진다.
  const { distances, maxDepth } = useMemo(() => {
    if (params.scope !== "seed" || params.seeds.length === 0) {
      return { distances: null as Map<string, number> | null, maxDepth: 0 };
    }
    const seedIds = resolveSeedIdsMulti(fullData.nodes, params.seeds);
    const links = removedIds.size === 0
      ? fullData.links
      : fullData.links.filter(
          (l) => !removedIds.has(endpointId(l.source)) && !removedIds.has(endpointId(l.target))
        );
    const dist = bfsDistances(seedIds, links);
    // 제거된 시드 자신은 거리 0 으로 들어오므로 제외한다.
    for (const id of removedIds) dist.delete(id);
    let mx = 0;
    for (const v of dist.values()) mx = Math.max(mx, v);
    return { distances: dist, maxDepth: mx };
  }, [fullData, params.scope, params.seeds, removedIds]);

  // 화면에 보낼 데이터 — envelope 를 현재 depth(홉) 까지 로컬로 자른 부분 그래프.
  // 전체 모드/필터 비활성이면 envelope 그대로.
  const data = useMemo<GraphData>(() => {
    // seed 모드: distances 가 이미 제거 노드를 배제한 채 재계산됐으므로, depth 컷만 적용하면
    // 제거 노드와 그로 인해 도달 불가가 된 연관 노드가 모두 자동으로 빠진다.
    if (params.scope === "seed" && params.seeds.length > 0 && distances) {
      const limit = Math.min(params.depth, maxDepth);
      const keepNodes = fullData.nodes.filter((n) => {
        const d = distances.get(n.id);
        return d != null && d <= limit;
      });
      const keep = new Set(keepNodes.map((n) => n.id));
      const keepLinks = fullData.links.filter(
        (l) => keep.has(endpointId(l.source)) && keep.has(endpointId(l.target))
      );
      return { ...fullData, depth: limit, nodes: keepNodes, links: keepLinks };
    }

    // 전체 모드: 거리 기준이 없으므로 직접 정리한다. 제거 노드를 빼고, "원래는 연결돼 있었으나
    // 제거 때문에 새로 고립된" 노드만 연쇄로 함께 제거한다(fixpoint). 원래부터 독립이던 노드는
    // 제거와 무관하므로 보존한다.
    if (removedIds.size === 0) return fullData;

    const origDegree = new Map<string, number>();
    for (const l of fullData.links) {
      const s = endpointId(l.source), t = endpointId(l.target);
      origDegree.set(s, (origDegree.get(s) ?? 0) + 1);
      origDegree.set(t, (origDegree.get(t) ?? 0) + 1);
    }

    const alive = new Set(fullData.nodes.map((n) => n.id).filter((id) => !removedIds.has(id)));
    for (;;) {
      const degree = new Map<string, number>();
      for (const l of fullData.links) {
        const s = endpointId(l.source), t = endpointId(l.target);
        if (!alive.has(s) || !alive.has(t)) continue;
        degree.set(s, (degree.get(s) ?? 0) + 1);
        degree.set(t, (degree.get(t) ?? 0) + 1);
      }
      const orphaned: string[] = [];
      for (const id of alive) {
        const wasConnected = (origDegree.get(id) ?? 0) > 0;
        const nowConnected = (degree.get(id) ?? 0) > 0;
        if (wasConnected && !nowConnected) orphaned.push(id);
      }
      if (orphaned.length === 0) break;
      for (const id of orphaned) alive.delete(id);
    }

    const nodes = fullData.nodes.filter((n) => alive.has(n.id));
    const links = fullData.links.filter(
      (l) => alive.has(endpointId(l.source)) && alive.has(endpointId(l.target))
    );
    return { ...fullData, nodes, links };
  }, [fullData, distances, maxDepth, params.depth, params.scope, params.seeds, removedIds]);

  // 제거한 노드 목록(원본에 실재하는 것만) — 툴바 복원 메뉴용.
  const removedNodes = useMemo(
    () => fullData.nodes.filter((n) => removedIds.has(n.id)),
    [fullData, removedIds]
  );

  function removeNode(n: GraphNode) {
    setRemovedIds((prev) => {
      if (prev.has(n.id)) return prev;
      const next = new Set(prev);
      next.add(n.id);
      return next;
    });
  }
  function restoreNode(id: string) {
    setRemovedIds((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  }
  function restoreAllNodes() {
    setRemovedIds(new Set());
  }

  // 검색
  useEffect(() => {
    if (!query.trim()) {
      setSuggests([]);
      return;
    }
    let cancelled = false;
    searchErd(query, true).then((r) => {
      if (!cancelled) setSuggests(r);
    });
    return () => { cancelled = true; };
  }, [query]);

  const hasEntities = data.nodes.some((n) => n.entity != null);
  const isIndexing = !!data.indexing;
  const showEmpty = !loading && !error && !isIndexing && params.scope === "all" && !hasEntities;
  // 중심 모드인데 아직 시드를 고르지 않은 상태 — 빈 캔버스 대신 안내
  const showSeedPrompt = !loading && !error && !isIndexing && params.scope === "seed" && params.seeds.length === 0;

  // indexing 상태면 3초마다 자동 재요청 — 인덱싱 끝나면 첫 성공 응답에서 멈춤
  useEffect(() => {
    if (!isIndexing) return;
    const t = setTimeout(() => setRefreshTick((x) => x + 1), 3000);
    return () => clearTimeout(t);
  }, [isIndexing, refreshTick]);

  // 검색어가 있고, 매칭이 1개 이상일 때만 하이라이트 활성.
  // 비활성 시 undefined 를 넘겨 뷰가 평소처럼 렌더하게 한다.
  const highlightedIds: Set<string> | undefined =
    query.trim().length > 0 && suggests.length > 0
      ? new Set(suggests.map((n) => n.id))
      : undefined;

  // 시드 한 개를 추가(중복 무시) 하고 검색창을 비워 다음 입력을 받는다.
  function addSeed(ref: SeedRef) {
    setParams((prev) => {
      if (prev.seeds.some((s) => sameSeed(s, ref))) {
        return prev.scope === "seed" ? prev : { ...prev, scope: "seed" };
      }
      return { ...prev, scope: "seed", seeds: [...prev.seeds, ref] };
    });
    setQuery("");
    closeDropdown();
  }

  function removeSeed(ref: SeedRef) {
    setParams((prev) => ({ ...prev, seeds: prev.seeds.filter((s) => !sameSeed(s, ref)) }));
  }

  function pickSeed(n: GraphNode) {
    // Repository 선택 시: 해당 Repository 가 가리키는 첫 Entity 를 시드로
    if (n.entity == null) {
      const target = fullData.links.find((l) => l.source === n.id && l.relation === "USES_ENTITY")?.target;
      if (target) {
        addSeed({ value: target, type: "fqn" });
        return;
      }
    }
    addSeed({ value: n.id, type: "fqn" });
  }

  function pickPackage(pkg: string) {
    addSeed({ value: pkg, type: "package" });
  }

  function closeDropdown() {
    setSuggests([]);
    setDropdownOpen(false);
    setActiveIndex(-1);
  }

  // 패키지 모드 제안: 검색 결과 노드들의 pkg 를 distinct 하게 추려 query 로 필터.
  const packageSuggests = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [] as string[];
    const set = new Set<string>();
    for (const n of suggests) {
      if (n.pkg && n.pkg.toLowerCase().includes(q)) set.add(n.pkg);
    }
    return [...set].sort().slice(0, 20);
  }, [suggests, query]);

  // 통합 제안: 패키지 + 엔티티/Repository 를 한 리스트로 — 클릭하면 시드로 추가.
  // 패키지를 위에, 그다음 노드. 키보드 탐색은 한 경로로 처리.
  const items: SuggestItem[] = useMemo(() => {
    if (!query.trim()) return [];
    const pkgItems: SuggestItem[] = packageSuggests.map((pkg) => ({
      key: `pkg:${pkg}`, primary: pkg, secondary: t("search.suggest.package"),
      onPick: () => pickPackage(pkg)
    }));
    const nodeItems: SuggestItem[] = suggests.map((n) => ({
      key: `node:${n.id}`,
      primary: n.name,
      secondary: `${n.entity ? `Entity (${n.entity.kind})` : "Repository"} · ${n.pkg}`,
      onPick: () => pickSeed(n)
    }));
    return [...pkgItems, ...nodeItems];
    // pickSeed/pickPackage 는 매 렌더 새로 생성되나 클로저 동작은 동일 — deps 에서 의도적으로 제외.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, packageSuggests, suggests]);

  // query 변경 시 활성 인덱스 초기화
  useEffect(() => { setActiveIndex(-1); }, [query]);

  function onSearchKeyDown(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setDropdownOpen(true);
      setActiveIndex((i) => Math.min(items.length - 1, i + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((i) => Math.max(0, i - 1));
    } else if (e.key === "Enter") {
      if (activeIndex >= 0 && activeIndex < items.length) {
        items[activeIndex].onPick();
      }
    } else if (e.key === "Escape") {
      setDropdownOpen(false);
    }
  }

  const showDropdown = dropdownOpen && query.trim().length > 0;
  const showNoResults = showDropdown && items.length === 0;

  return (
    <div style={{ position: "fixed", inset: 0, background: UI.canvas, color: UI.text }}>
      {/* 컨트롤 바 — 좁은 툴윈도우에서 줄바꿈되도록 wrap */}
      <div ref={toolbarRef} style={{
        position: "absolute", top: 0, left: 0, right: 0, minHeight: TOOLBAR_H,
        display: "flex", flexWrap: "wrap", alignItems: "center", gap: 10,
        padding: "4px 10px", rowGap: 6,
        background: UI.panel, borderBottom: `1px solid ${UI.border}`, fontSize: 12, zIndex: 30
      }}>
        <ScopeToggle value={params.scope} onChange={(scope) => setParams({ ...params, scope })} />
        <Divider />
        {params.scope === "seed" && params.seeds.length > 0 && (
          <>
            <SeedChips
              seeds={params.seeds}
              onRemove={removeSeed}
              onClear={() => setParams({ ...params, seeds: [] })}
            />
            <Divider />
            <DepthSlider
              value={params.depth}
              max={maxDepth}
              onChange={(depth) => setParams({ ...params, depth })}
            />
            <Divider />
          </>
        )}
        <OptionsMenu
          showColumns={params.showColumns}
          showRepository={params.showRepository}
          showExtends={params.showExtends}
          onChange={(patch) => setParams({ ...params, ...patch })}
        />
        {removedNodes.length > 0 && (
          <>
            <Divider />
            <HiddenNodesMenu
              nodes={removedNodes}
              onRestore={restoreNode}
              onRestoreAll={restoreAllNodes}
            />
          </>
        )}

        {/* 검색 — 남는 공간을 차지하다가 좁아지면 다음 줄로 */}
        <div style={{ position: "relative", flex: "1 1 200px", minWidth: 160, maxWidth: 380 }}>
          <span style={{
            position: "absolute", left: 8, top: "50%", transform: "translateY(-50%)",
            color: UI.textMuted, pointerEvents: "none", display: "flex"
          }}>
            <IconSearch size={13} />
          </span>
          <input
            value={query}
            role="combobox"
            aria-expanded={showDropdown}
            aria-controls="erd-search-listbox"
            aria-activedescendant={activeIndex >= 0 ? `erd-opt-${activeIndex}` : undefined}
            aria-autocomplete="list"
            onChange={(e) => { setQuery(e.target.value); setDropdownOpen(true); }}
            onFocus={() => setDropdownOpen(true)}
            onBlur={() => setTimeout(() => setDropdownOpen(false), 120)}
            onKeyDown={onSearchKeyDown}
            placeholder={
              params.scope !== "seed"
                ? t("search.placeholder.all")
                : t("search.placeholder.seed")
            }
            style={{
              width: "100%", padding: "5px 26px 5px 28px", fontSize: 13,
              background: UI.field, color: UI.text,
              border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control
            }}
          />
          {query.trim() && (
            <button
              onClick={() => { setQuery(""); setDropdownOpen(false); }}
              title={t("search.clear")}
              aria-label={t("search.clear")}
              style={{
                position: "absolute", right: 4, top: 4,
                width: 22, height: 22, lineHeight: "20px", textAlign: "center",
                background: "transparent", color: UI.textMuted,
                border: "none", borderRadius: RADIUS.control, cursor: "pointer", fontSize: 14
              }}
            >×</button>
          )}
          {(showDropdown && items.length > 0) && (
            <div
              id="erd-search-listbox"
              role="listbox"
              style={{
                position: "absolute", top: "calc(100% + 4px)", left: 0, right: 0,
                background: UI.panel, border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
                maxHeight: 260, overflowY: "auto", zIndex: 40,
                boxShadow: "0 8px 24px rgba(0,0,0,0.45)"
              }}
            >
              {items.map((it, idx) => (
                <div
                  key={it.key}
                  id={`erd-opt-${idx}`}
                  role="option"
                  aria-selected={idx === activeIndex}
                  // blur 로 닫히기 전에 선택되도록 mousedown 사용
                  onMouseDown={(e) => { e.preventDefault(); it.onPick(); }}
                  onMouseEnter={() => setActiveIndex(idx)}
                  style={{
                    padding: "6px 10px", cursor: "pointer",
                    borderBottom: `1px solid ${UI.border}`, fontSize: 12,
                    background: idx === activeIndex ? "#273449" : "transparent"
                  }}
                >
                  <div>{it.primary}</div>
                  <div style={{ color: UI.textMuted, fontSize: 11 }}>{it.secondary}</div>
                </div>
              ))}
            </div>
          )}
          {showNoResults && (
            <div style={{
              position: "absolute", top: "calc(100% + 4px)", left: 0, right: 0,
              background: UI.panel, border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
              padding: "8px 10px", fontSize: 12, color: UI.textMuted, zIndex: 40
            }}>
              {t("search.noResults", [query.trim()])}
            </div>
          )}
        </div>

        {/* 우측 액션 묶음 — 한 줄일 땐 오른쪽 정렬, 좁으면 함께 내려감 */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginLeft: "auto" }}>
          <HelpButton open={showHelp} onToggle={() => setShowHelp((v) => !v)} />
          <button
            onClick={() => setRefreshTick((t) => t + 1)}
            disabled={loading}
            title={loading ? t("toolbar.syncing") : t("toolbar.resync")}
            aria-label={t("toolbar.sync")}
            style={{
              ...controlButton,
              color: loading ? UI.textMuted : UI.textDim,
              cursor: loading ? "wait" : "pointer"
            }}
          >
            <IconSync spin={loading} />
          </button>
          <div style={{ color: UI.textMuted, fontSize: 12, whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
            {t("toolbar.counts", [data.nodes.length, data.links.length])}
          </div>
        </div>
      </div>

      {/* 그래프 영역 — 툴바 실제 높이만큼 내려 시작 (wrap 대응) */}
      <div style={{ position: "absolute", top: toolbarH, left: 0, right: 0, bottom: 0 }}>
        {/* 뷰 모드(렌더 방식) — 데이터 필터와 성격이 달라 캔버스 좌상단에 분리 배치 */}
        <ViewModeControl value={params.view} onChange={(view) => setParams({ ...params, view })} />
        {isIndexing ? (
          <IndexingState />
        ) : showEmpty ? (
          <EmptyState />
        ) : showSeedPrompt ? (
          <SeedPromptState />
        ) : error ? (
          <ErrorState message={error} onRetry={() => setRefreshTick((t) => t + 1)} />
        ) : params.view === "2d" ? (
          <>
            <ErdView2D
              ref={erd2dRef}
              data={data}
              width={size.w}
              height={size.h - toolbarH}
              showColumns={params.showColumns}
              highlightedIds={highlightedIds}
              highlightBaseId={params.seeds[0]?.value}
              onNodeReseed={(n) => pickSeed(n)}
              onNodeRemove={(n) => removeNode(n)}
              onNodeNavigate={(n, split) => navigateToSource(n.id, split)}
            />
            <Legend view="2d" />
          </>
        ) : (
          <>
            <GraphView
              ref={graphRef}
              data={data}
              width={size.w}
              height={size.h - toolbarH}
              showColumns={params.showColumns}
              highlightedIds={highlightedIds}
              highlightBaseId={params.seeds[0]?.value}
              onNodeSelect={(n, split) => navigateToSource(n.id, split)}
              onNodeReseed={(n) => pickSeed(n)}
              onNodeRemove={(n) => removeNode(n)}
            />
            <Legend view="3d" />
          </>
        )}
      </div>

      {showHelp && <HelpPopover top={toolbarH + 6} onClose={() => setShowHelp(false)} />}
    </div>
  );
}

// 모든 토글 그룹은 [라벨: 세그먼트] 형태로 통일 — 라벨 정책 일관.
const groupStyle: React.CSSProperties = { display: "flex", gap: 6, alignItems: "center" };
const labelStyle: React.CSSProperties = { color: UI.textMuted, fontSize: 11, whiteSpace: "nowrap" };

function Divider() {
  return <div aria-hidden style={{ width: 1, height: 18, background: UI.border, flexShrink: 0 }} />;
}

/** 붙은 세그먼트 컨트롤 컨테이너 — 개별 버튼을 한 덩어리로 묶어 가로 공간을 줄인다. */
function SegGroup({ children }: { children: React.ReactNode }) {
  return (
    <div role="group" style={{
      display: "inline-flex", background: UI.panel,
      border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control, overflow: "hidden"
    }}>
      {children}
    </div>
  );
}

/** 세그먼트 한 칸. first=false 면 좌측에 칸막이. */
function SegItem({ active, onClick, children, title, first }: {
  active: boolean; onClick: () => void; children: React.ReactNode; title?: string; first?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-pressed={active}
      style={{
        padding: "2px 9px", height: 24, fontSize: 12, lineHeight: 1,
        background: active ? UI.accent : "transparent",
        color: active ? "#fff" : UI.textDim,
        border: "none",
        borderLeft: first ? "none" : `1px solid ${UI.borderStrong}`,
        cursor: "pointer",
        transition: "background 0.12s ease, color 0.12s ease"
      }}
    >
      {children}
    </button>
  );
}

function ScopeToggle({ value, onChange }: { value: Scope; onChange: (v: Scope) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>{t("scope.label")}</span>
      <SegGroup>
        <SegItem first active={value === "all"} onClick={() => onChange("all")} title={t("scope.all.title")}>{t("scope.all")}</SegItem>
        <SegItem active={value === "seed"} onClick={() => onChange("seed")} title={t("scope.seed.title")}>{t("scope.seed")}</SegItem>
      </SegGroup>
    </div>
  );
}

/** 짧은 라벨 — 엔티티는 FQN 의 마지막 마디(클래스명), 패키지는 마지막 마디. tooltip 으로 전체. */
function seedShortLabel(s: SeedRef): string {
  const last = s.value.split(".").filter(Boolean).pop() ?? s.value;
  return last || s.value;
}

/** 선택한 다중 시드를 칩(태그) 목록으로 표시 — 각 ✕ 로 제거, 2개 이상이면 "모두 지우기". */
function SeedChips({ seeds, onRemove, onClear }: {
  seeds: SeedRef[];
  onRemove: (s: SeedRef) => void;
  onClear: () => void;
}) {
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 4, alignItems: "center", maxWidth: 360 }}>
      <span style={labelStyle}>{t("seeds.label")}</span>
      {seeds.map((s) => (
        <span
          key={`${s.type}:${s.value}`}
          title={`${s.type === "package" ? t("seeds.package.prefix") : ""}${s.value}`}
          style={{
            display: "inline-flex", alignItems: "center", gap: 4,
            height: 22, padding: "0 4px 0 7px", fontSize: 11, lineHeight: 1,
            background: UI.field, color: UI.text,
            border: `1px solid ${UI.borderStrong}`, borderRadius: 11
          }}
        >
          <span aria-hidden style={{ color: UI.textMuted, fontSize: 10 }}>
            {s.type === "package" ? "▦" : "◆"}
          </span>
          {seedShortLabel(s)}
          <button
            onClick={() => onRemove(s)}
            title={t("seeds.remove")}
            aria-label={t("seeds.remove.aria", [s.value])}
            style={{
              width: 16, height: 16, lineHeight: "14px", textAlign: "center",
              background: "transparent", color: UI.textMuted,
              border: "none", borderRadius: 8, cursor: "pointer", fontSize: 13, padding: 0
            }}
          >×</button>
        </span>
      ))}
      {seeds.length > 1 && (
        <button
          onClick={onClear}
          title={t("seeds.clearAll.title")}
          style={{
            height: 22, padding: "0 8px", fontSize: 11,
            background: "transparent", color: UI.textMuted,
            border: `1px solid ${UI.border}`, borderRadius: 11, cursor: "pointer"
          }}
        >{t("seeds.clearAll")}</button>
      )}
    </div>
  );
}

/**
 * 연결 깊이(seed 로부터의 홉) 슬라이더 — 0..max 를 마우스 드래그로 조절.
 * 필터링은 클라이언트 로컬이라 재요청 없이 즉시 반영된다(실시간 드래그).
 * max 는 현재 seed 연결성분의 실제 최대 거리. max<=0 (seed 고립/미로딩) 이면 비활성.
 */
function DepthSlider({ value, max, onChange }: {
  value: number; max: number; onChange: (v: number) => void;
}) {
  const disabled = max <= 0;
  const shown = Math.min(value, Math.max(max, 0));
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>{t("depth.label")}</span>
      <input
        type="range"
        min={0}
        max={Math.max(max, 1)}
        step={1}
        value={shown}
        disabled={disabled}
        aria-label={t("depth.aria")}
        title={disabled ? t("depth.title.disabled") : t("depth.title", [shown, max])}
        onChange={(e) => onChange(parseInt(e.target.value, 10))}
        style={{
          width: 92, accentColor: UI.accent,
          cursor: disabled ? "default" : "pointer",
          opacity: disabled ? 0.5 : 1
        }}
      />
      <span style={{
        ...labelStyle, color: UI.textDim, minWidth: 30,
        fontVariantNumeric: "tabular-nums", textAlign: "right"
      }}>
        {shown}/{Math.max(max, 0)}
      </span>
    </div>
  );
}

interface DisplayOptions { showColumns: boolean; showRepository: boolean; showExtends: boolean }

/**
 * "표시 옵션" 드롭다운 — 관계는 항상 표시되는 기본값이고, 컬럼/리포지토리/상속을
 * 각각 독립적으로 켜고 끈다(다중 선택). IntelliJ Database 툴윈도우의 표시 옵션 메뉴와 같은 패턴.
 */
function OptionsMenu({ showColumns, showRepository, showExtends, onChange }: {
  showColumns: boolean; showRepository: boolean; showExtends: boolean;
  onChange: (patch: Partial<DisplayOptions>) => void;
}) {
  const [open, setOpen] = useState(false);
  const activeCount = [showColumns, showRepository, showExtends].filter(Boolean).length;

  const items: { key: keyof DisplayOptions; label: string; hint: string; checked: boolean }[] = [
    { key: "showColumns", label: t("options.columns"), hint: t("options.columns.hint"), checked: showColumns },
    { key: "showRepository", label: t("options.repository"), hint: t("options.repository.hint"), checked: showRepository },
    { key: "showExtends", label: t("options.extends"), hint: t("options.extends.hint"), checked: showExtends }
  ];

  return (
    <div style={{ ...groupStyle, position: "relative" }}>
      <span style={labelStyle}>{t("options.label")}</span>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
        title={t("options.title")}
        style={{
          ...controlButton, gap: 6,
          background: open ? UI.panelHover : "transparent"
        }}
      >
        {t("options.summary", [activeCount])}
        <span style={{ fontSize: 9, color: UI.textMuted }}>{open ? "▴" : "▾"}</span>
      </button>
      {open && (
        <>
          {/* 바깥 클릭 닫기 */}
          <div style={{ position: "fixed", inset: 0, zIndex: 39 }} onClick={() => setOpen(false)} />
          <div
            role="menu"
            style={{
              position: "absolute", top: "calc(100% + 4px)", left: 0, minWidth: 220, zIndex: 40,
              background: UI.panel, border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
              padding: 4, boxShadow: "0 8px 24px rgba(0,0,0,0.45)"
            }}
          >
            <div style={{ padding: "4px 10px", fontSize: 11, color: UI.textMuted }}>
              {t("options.alwaysRelations")}
            </div>
            {items.map((it) => (
              <button
                key={it.key}
                role="menuitemcheckbox"
                aria-checked={it.checked}
                onClick={() => onChange({ [it.key]: !it.checked })}
                style={{
                  display: "flex", alignItems: "center", gap: 8, width: "100%",
                  padding: "6px 10px", background: "transparent", border: "none",
                  borderRadius: RADIUS.control, cursor: "pointer", textAlign: "left",
                  color: UI.text, fontSize: 12
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = UI.panelHover)}
                onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
              >
                <span style={{ width: 14, color: it.checked ? UI.accent : UI.textMuted }}>
                  {it.checked ? "✓" : ""}
                </span>
                <span style={{ flex: 1 }}>{it.label}</span>
                <span style={{ color: UI.textMuted, fontSize: 11 }}>{it.hint}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/**
 * "제거" 드롭다운 — 노드를 우클릭하면 분석에서 제거되고, 명시적으로 제거한 노드가 이 메뉴에 쌓인다.
 * 항목을 클릭하면 그 노드만 복원, "모두 복원" 으로 일괄 복원. 제거한 노드가 없으면 렌더되지 않는다.
 */
function HiddenNodesMenu({ nodes, onRestore, onRestoreAll }: {
  nodes: GraphNode[];
  onRestore: (id: string) => void;
  onRestoreAll: () => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ ...groupStyle, position: "relative" }}>
      <span style={labelStyle}>{t("hidden.label")}</span>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
        title={t("hidden.title")}
        style={{ ...controlButton, gap: 6, background: open ? UI.panelHover : "transparent" }}
      >
        {t("hidden.summary", [nodes.length])}
        <span style={{ fontSize: 9, color: UI.textMuted }}>{open ? "▴" : "▾"}</span>
      </button>
      {open && (
        <>
          {/* 바깥 클릭 닫기 */}
          <div style={{ position: "fixed", inset: 0, zIndex: 39 }} onClick={() => setOpen(false)} />
          <div
            role="menu"
            style={{
              position: "absolute", top: "calc(100% + 4px)", left: 0, minWidth: 220, maxHeight: 320,
              overflowY: "auto", zIndex: 40,
              background: UI.panel, border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
              padding: 4, boxShadow: "0 8px 24px rgba(0,0,0,0.45)"
            }}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "4px 10px" }}>
              <span style={{ fontSize: 11, color: UI.textMuted }}>{t("hidden.menuTitle")}</span>
              <button
                onClick={() => { onRestoreAll(); setOpen(false); }}
                style={{ background: "transparent", border: "none", color: UI.accent, cursor: "pointer", fontSize: 11 }}
              >{t("hidden.restoreAll")}</button>
            </div>
            {nodes.map((n) => (
              <button
                key={n.id}
                role="menuitem"
                onClick={() => onRestore(n.id)}
                title={n.id}
                aria-label={t("hidden.restore.aria", [n.name])}
                style={{
                  display: "flex", alignItems: "center", gap: 8, width: "100%",
                  padding: "6px 10px", background: "transparent", border: "none",
                  borderRadius: RADIUS.control, cursor: "pointer", textAlign: "left",
                  color: UI.text, fontSize: 12
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = UI.panelHover)}
                onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
              >
                <span aria-hidden style={{
                  width: 10, height: 10, borderRadius: 2, flexShrink: 0,
                  background: KIND_COLOR[kindKey(n.entity)]
                }} />
                <span style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {n.name}
                </span>
                <span style={{ color: UI.textMuted, fontSize: 11 }}>{t("hidden.restore")}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

/** 캔버스 좌상단 오버레이 — 뷰 렌더 모드(3D/2D) 전환. SegGroup 의 panel 배경으로 그래프 위에서도 읽힌다. */
function ViewModeControl({ value, onChange }: { value: ViewMode; onChange: (v: ViewMode) => void }) {
  return (
    <div style={{ position: "absolute", top: 16, left: 16, zIndex: 20 }}>
      <SegGroup>
        <SegItem first active={value === "3d"} onClick={() => onChange("3d")}>3D</SegItem>
        <SegItem active={value === "2d"} onClick={() => onChange("2d")}>2D</SegItem>
      </SegGroup>
    </div>
  );
}

function HelpButton({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button
      onClick={onToggle}
      title={t("view.help")}
      aria-label={t("view.help")}
      aria-expanded={open}
      style={{
        width: 26, height: 26, fontSize: 13, borderRadius: "50%",
        background: open ? UI.accent : "transparent",
        color: open ? "#fff" : UI.textDim,
        border: `1px solid ${open ? UI.accent : UI.borderStrong}`, cursor: "pointer"
      }}
    >?</button>
  );
}

function HelpPopover({ onClose, top }: { onClose: () => void; top: number }) {
  const rows: [string, string][] = [
    [t("help.k.click"), t("help.v.click")],
    [t("help.k.cmdClick"), t("help.v.cmdClick")],
    [t("help.k.dblClick"), t("help.v.dblClick")],
    [t("help.k.rightClick"), t("help.v.rightClick")],
    [t("help.k.drag2d"), t("help.v.drag2d")],
    [t("help.k.colHover"), t("help.v.colHover")],
    [t("help.k.search"), t("help.v.search")],
    [t("help.k.wheel"), t("help.v.wheel")],
    [t("help.k.drag3d"), t("help.v.drag3d")]
  ];
  return (
    <div
      onClick={onClose}
      style={{ position: "absolute", inset: 0, zIndex: 50 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          position: "absolute", top, right: 12, width: 320,
          background: UI.panel, color: UI.text,
          border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
          padding: "12px 14px", fontSize: 12, lineHeight: 1.5,
          boxShadow: "0 10px 30px rgba(0,0,0,0.5)"
        }}
      >
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <strong style={{ fontSize: 13 }}>{t("help.title")}</strong>
          <button
            onClick={onClose}
            aria-label={t("help.close")}
            style={{ background: "transparent", border: "none", color: UI.textMuted, cursor: "pointer", fontSize: 16 }}
          >×</button>
        </div>
        {rows.map(([k, v]) => (
          <div key={k} style={{ display: "flex", gap: 10, padding: "3px 0" }}>
            <span style={{
              flexShrink: 0, minWidth: 92, color: UI.textBright, fontWeight: 600
            }}>{k}</span>
            <span style={{ color: UI.textDim }}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function CenterMessage({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
      height: "100%", color: UI.textMuted, gap: 10, padding: 24, textAlign: "center"
    }}>
      {children}
    </div>
  );
}

function GraphGlyph() {
  return (
    <svg
      width={52} height={52} viewBox="0 0 24 24" fill="none"
      stroke={UI.borderStrong} strokeWidth={1.4} strokeLinecap="round" strokeLinejoin="round"
      aria-hidden style={{ marginBottom: 4 }}
    >
      <line x1="7" y1="7.5" x2="11" y2="15" />
      <line x1="17" y1="7.5" x2="13" y2="15" />
      <line x1="8" y1="6" x2="16" y2="6" />
      <circle cx="6" cy="6" r="2.4" fill={UI.panel} />
      <circle cx="18" cy="6" r="2.4" fill={UI.panel} />
      <circle cx="12" cy="17" r="2.4" fill={UI.panel} />
    </svg>
  );
}

function EmptyState() {
  return (
    <CenterMessage>
      <GraphGlyph />
      <div style={{ fontSize: 18 }}>{t("empty.title")}</div>
      <div style={{ fontSize: 13 }}>
        {t("empty.desc")}
      </div>
    </CenterMessage>
  );
}

function SeedPromptState() {
  return (
    <CenterMessage>
      <GraphGlyph />
      <div style={{ fontSize: 18 }}>{t("seedPrompt.title")}</div>
      <div style={{ fontSize: 13 }}>
        {t("seedPrompt.desc")}
      </div>
    </CenterMessage>
  );
}

function IndexingState() {
  return (
    <CenterMessage>
      <div style={{ fontSize: 18 }}>{t("indexing.title")}</div>
      <div style={{ fontSize: 13 }}>
        {t("indexing.desc")}
      </div>
    </CenterMessage>
  );
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <CenterMessage>
      <div style={{ fontSize: 16, color: UI.danger }}>{t("error.title")}</div>
      <div style={{ fontSize: 12, color: UI.textMuted, maxWidth: 520, wordBreak: "break-word" }}>{message}</div>
      <button
        onClick={onRetry}
        style={{ ...controlButton, marginTop: 4, height: 32, padding: "0 14px", fontSize: 13 }}
      >{t("error.retry")}</button>
    </CenterMessage>
  );
}
