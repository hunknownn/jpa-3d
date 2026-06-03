import { useEffect, useMemo, useRef, useState } from "react";
import GraphView, { GraphHandle } from "./GraphView";
import ErdView2D, { Erd2dHandle } from "./ErdView2D";
import Legend from "./Legend";
import { fetchErd, navigateToSource, searchErd } from "./api";
import { GraphData, GraphNode } from "./types";
import { UI, RADIUS, controlButton } from "./theme";
import { IconSync, IconSearch } from "./Icons";
import { bfsDistances, endpointId, resolveSeedIds } from "./graphDepth";

type Scope = "all" | "seed";
type SeedType = "fqn" | "package";
type ViewMode = "3d" | "2d";

interface ErdParams {
  scope: Scope;
  seed?: string;
  /** seed 해석 방식: 단일 엔티티(fqn) vs 패키지+하위(package). */
  seedType: SeedType;
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
  const seed = p.get("seed") ?? undefined;
  const seedType: SeedType = p.get("seedType") === "package" ? "package" : "fqn";
  const view: ViewMode = p.get("view") === "2d" ? "2d" : "3d";
  // showExtends 미지정이면 기본 true (기존 동작 유지)
  const showExtends = p.get("extends") !== "0";
  return { scope, seed, seedType, showColumns, showRepository, depth: Number.isFinite(depth) ? depth : 2, view, showExtends };
}

function writeParamsToHash(params: ErdParams) {
  const p = new URLSearchParams();
  p.set("scope", params.scope);
  if (params.showColumns) p.set("col", "1");
  if (params.showRepository) p.set("repo", "1");
  p.set("view", params.view);
  if (!params.showExtends) p.set("extends", "0");
  if (params.scope === "seed") {
    if (params.seed) p.set("seed", params.seed);
    if (params.seedType === "package") p.set("seedType", "package");
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
  const [query, setQuery] = useState(params.seed ?? "");
  const [size, setSize] = useState({ w: window.innerWidth, h: window.innerHeight });
  // 툴바는 좁은 폭에서 wrap 되어 높이가 가변 — 실제 높이를 측정해 그래프 영역 오프셋에 반영.
  // (고정값을 쓰면 wrap 시 좌상단 뷰 토글이 둘째 줄에 가려진다.)
  const [toolbarH, setToolbarH] = useState(TOOLBAR_H);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const [refreshTick, setRefreshTick] = useState(0);
  // 검색 드롭다운: 키보드 탐색 인덱스 + 열림 상태
  const [activeIndex, setActiveIndex] = useState(-1);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
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

  // ERD 데이터 fetch — depth 를 **제외한** 입력이 바뀔 때만 재요청한다.
  // depth 는 FULL_DEPTH 로 연결성분 전체를 받아두고 클라이언트에서 자르므로 fetch 에서 뺀다.
  // (refreshTick = 동기화/인덱싱 재시도 트리거)
  useEffect(() => {
    if (params.scope === "seed" && !params.seed) {
      setFullData(EMPTY_DATA);
      return;
    }
    setLoading(true);
    setError(null);
    fetchErd({
      scope: params.scope,
      seed: params.seed,
      seedType: params.seedType,
      depth: FULL_DEPTH,
      showColumns: params.showColumns,
      showRepository: params.showRepository,
      showExtends: params.showExtends
    })
      .then(setFullData)
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [
    params.scope, params.seed, params.seedType,
    params.showColumns, params.showRepository, params.showExtends,
    refreshTick
  ]);

  // seed 로부터의 홉 거리 + 최대 깊이(슬라이더 상한). 전체 모드면 필터 비활성.
  const { distances, maxDepth } = useMemo(() => {
    if (params.scope !== "seed" || !params.seed) {
      return { distances: null as Map<string, number> | null, maxDepth: 0 };
    }
    const seeds = resolveSeedIds(fullData.nodes, params.seedType, params.seed);
    const dist = bfsDistances(seeds, fullData.links);
    let mx = 0;
    for (const v of dist.values()) mx = Math.max(mx, v);
    return { distances: dist, maxDepth: mx };
  }, [fullData, params.scope, params.seed, params.seedType]);

  // 화면에 보낼 데이터 — envelope 를 현재 depth(홉) 까지 로컬로 자른 부분 그래프.
  // 전체 모드/필터 비활성이면 envelope 그대로.
  const data = useMemo<GraphData>(() => {
    if (params.scope !== "seed" || !params.seed || !distances) return fullData;
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
  }, [fullData, distances, maxDepth, params.depth, params.scope, params.seed]);

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
  // 중심 모드인데 아직 seed 를 고르지 않은 상태 — 빈 캔버스 대신 안내
  const showSeedPrompt = !loading && !error && !isIndexing && params.scope === "seed" && !params.seed;

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

  function pickSeed(n: GraphNode) {
    // Repository 선택 시: 해당 Repository 가 가리키는 첫 Entity 를 seed 로
    if (n.entity == null) {
      const target = fullData.links.find((l) => l.source === n.id && l.relation === "USES_ENTITY")?.target;
      if (target) {
        setParams({ ...params, scope: "seed", seedType: "fqn", seed: target });
        setQuery(target);
        closeDropdown();
        return;
      }
    }
    setParams({ ...params, scope: "seed", seedType: "fqn", seed: n.id });
    setQuery(n.id);
    closeDropdown();
  }

  function pickPackage(pkg: string) {
    setParams({ ...params, scope: "seed", seedType: "package", seed: pkg });
    setQuery(pkg);
    closeDropdown();
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

  const isPkgMode = params.scope === "seed" && params.seedType === "package";

  // 패키지/노드 제안을 단일 리스트로 정규화 — 키보드 탐색을 한 경로로 처리.
  const items: SuggestItem[] = useMemo(() => {
    if (!query.trim()) return [];
    if (isPkgMode) {
      return packageSuggests.map((pkg) => ({
        key: pkg, primary: pkg, secondary: "패키지 (하위 포함)",
        onPick: () => pickPackage(pkg)
      }));
    }
    return suggests.map((n) => ({
      key: n.id,
      primary: n.name,
      secondary: `${n.entity ? `Entity (${n.entity.kind})` : "Repository"} · ${n.pkg}`,
      onPick: () => pickSeed(n)
    }));
    // pickSeed/pickPackage 는 매 렌더 새로 생성되나 클로저 동작은 동일 — deps 에서 의도적으로 제외.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, isPkgMode, packageSuggests, suggests]);

  // query/모드 변경 시 활성 인덱스 초기화
  useEffect(() => { setActiveIndex(-1); }, [query, isPkgMode]);

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
      } else if (isPkgMode && query.trim()) {
        // 패키지 모드: 선택 없이 Enter 면 입력값을 접두 패키지로 바로 적용
        pickPackage(query.trim());
      }
    } else if (e.key === "Escape") {
      setDropdownOpen(false);
    }
  }

  const showDropdown = dropdownOpen && query.trim().length > 0;
  const showNoResults = showDropdown && !isPkgMode && items.length === 0 && suggests.length === 0;

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
        {params.scope === "seed" && (
          <>
            <SeedTypeToggle
              value={params.seedType}
              onChange={(seedType) => {
                // 기준 전환 시 기존 seed/검색어 초기화 — 엔티티 FQN 과 패키지는 매칭 규칙이 달라서.
                setParams({ ...params, seedType, seed: undefined });
                setQuery("");
              }}
            />
            <Divider />
            {params.seed && (
              <>
                <DepthSlider
                  value={params.depth}
                  max={maxDepth}
                  onChange={(depth) => setParams({ ...params, depth })}
                />
                <Divider />
              </>
            )}
          </>
        )}
        <OptionsMenu
          showColumns={params.showColumns}
          showRepository={params.showRepository}
          showExtends={params.showExtends}
          onChange={(patch) => setParams({ ...params, ...patch })}
        />

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
                ? "노드 검색 (하이라이트)"
                : isPkgMode
                  ? "패키지 입력 후 Enter…"
                  : "중심 엔티티 검색…"
            }
            style={{
              width: "100%", padding: "5px 26px 5px 28px", fontSize: 13,
              background: UI.canvas, color: UI.text,
              border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control
            }}
          />
          {query.trim() && (
            <button
              onClick={() => { setQuery(""); setDropdownOpen(false); }}
              title="검색 초기화"
              aria-label="검색 초기화"
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
              "{query.trim()}" 와 일치하는 항목이 없습니다.
            </div>
          )}
        </div>

        {/* 우측 액션 묶음 — 한 줄일 땐 오른쪽 정렬, 좁으면 함께 내려감 */}
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginLeft: "auto" }}>
          <HelpButton open={showHelp} onToggle={() => setShowHelp((v) => !v)} />
          <button
            onClick={() => setRefreshTick((t) => t + 1)}
            disabled={loading}
            title={loading ? "동기화 중…" : "현재 프로젝트 상태로 ERD 재분석"}
            aria-label="동기화"
            style={{
              ...controlButton,
              color: loading ? UI.textMuted : UI.textDim,
              cursor: loading ? "wait" : "pointer"
            }}
          >
            <IconSync spin={loading} />
          </button>
          <div style={{ color: UI.textMuted, fontSize: 12, whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
            {`노드 ${data.nodes.length} · 관계 ${data.links.length}`}
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
          <SeedPromptState pkgMode={isPkgMode} />
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
              highlightBaseId={params.seed}
              onNodeReseed={(n) => pickSeed(n)}
              onNodeNavigate={(n) => navigateToSource(n.id)}
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
              highlightBaseId={params.seed}
              onNodeSelect={(n) => navigateToSource(n.id)}
              onNodeReseed={(n) => pickSeed(n)}
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
      <span style={labelStyle}>범위:</span>
      <SegGroup>
        <SegItem first active={value === "all"} onClick={() => onChange("all")} title="모든 엔티티 표시">전체</SegItem>
        <SegItem active={value === "seed"} onClick={() => onChange("seed")} title="선택한 중심에서 도달 가능한 부분만">중심</SegItem>
      </SegGroup>
    </div>
  );
}

function SeedTypeToggle({ value, onChange }: { value: SeedType; onChange: (v: SeedType) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>기준:</span>
      <SegGroup>
        <SegItem first active={value === "fqn"} onClick={() => onChange("fqn")}>엔티티</SegItem>
        <SegItem active={value === "package"} onClick={() => onChange("package")}>패키지</SegItem>
      </SegGroup>
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
      <span style={labelStyle}>깊이:</span>
      <input
        type="range"
        min={0}
        max={Math.max(max, 1)}
        step={1}
        value={shown}
        disabled={disabled}
        aria-label="연결 깊이"
        title={disabled ? "연결된 이웃이 없습니다" : `seed 로부터 ${shown} 홉 (최대 ${max})`}
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
    { key: "showColumns", label: "컬럼", hint: "엔티티 컬럼/타입", checked: showColumns },
    { key: "showRepository", label: "리포지토리", hint: "Spring Data Repository", checked: showRepository },
    { key: "showExtends", label: "상속", hint: "@MappedSuperclass / EXTENDS", checked: showExtends }
  ];

  return (
    <div style={{ ...groupStyle, position: "relative" }}>
      <span style={labelStyle}>표시:</span>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
        title="표시 옵션"
        style={{
          ...controlButton, gap: 6,
          background: open ? UI.panelHover : "transparent"
        }}
      >
        관계 + {activeCount}
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
              관계는 항상 표시됩니다
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
      title="사용법 도움말"
      aria-label="사용법 도움말"
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
    ["클릭 (3D)", "그 노드로 카메라 이동 · 이웃 강조"],
    ["더블클릭 (3D)", "엔티티의 소스 파일로 이동"],
    ["클릭 (2D)", "엔티티의 소스 파일로 이동"],
    ["우클릭", "그 노드를 중심(seed)으로 다시 탐색"],
    ["드래그 (2D)", "노드 위치 이동 · ↺ 로 복원"],
    ["컬럼에 마우스", "해당 엔티티에 연결된 관계 강조"],
    ["검색", "입력 후 ↑/↓ 이동, Enter 로 선택"],
    ["휠 / +−", "확대·축소, 'fit' 으로 전체 보기"],
    ["드래그 (3D)", "좌클릭 회전 · 휠클릭/우클릭 이동"]
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
          <strong style={{ fontSize: 13 }}>사용법</strong>
          <button
            onClick={onClose}
            aria-label="닫기"
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
      <div style={{ fontSize: 18 }}>이 프로젝트에서 JPA Entity를 찾지 못했습니다.</div>
      <div style={{ fontSize: 13 }}>
        분석 대상이 빌드되어 있고 패키지 범위 설정이 올바른지 확인해 주세요.
      </div>
    </CenterMessage>
  );
}

function SeedPromptState({ pkgMode }: { pkgMode: boolean }) {
  return (
    <CenterMessage>
      <GraphGlyph />
      <div style={{ fontSize: 18 }}>중심을 선택하세요.</div>
      <div style={{ fontSize: 13 }}>
        {pkgMode
          ? "상단 검색창에 패키지를 입력하고 Enter 를 누르면 해당 패키지(하위 포함)를 중심으로 그립니다."
          : "상단 검색창에서 중심이 될 엔티티를 검색해 선택하면, 그 주변 그래프를 그립니다."}
      </div>
    </CenterMessage>
  );
}

function IndexingState() {
  return (
    <CenterMessage>
      <div style={{ fontSize: 18 }}>IDE 인덱싱 진행 중…</div>
      <div style={{ fontSize: 13 }}>
        인덱싱이 끝나면 자동으로 분석 결과가 표시됩니다.
      </div>
    </CenterMessage>
  );
}

function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <CenterMessage>
      <div style={{ fontSize: 16, color: UI.danger }}>분석 중 오류가 발생했습니다.</div>
      <div style={{ fontSize: 12, color: UI.textMuted, maxWidth: 520, wordBreak: "break-word" }}>{message}</div>
      <button
        onClick={onRetry}
        style={{ ...controlButton, marginTop: 4, height: 32, padding: "0 14px", fontSize: 13 }}
      >↻ 다시 시도</button>
    </CenterMessage>
  );
}
