import { useEffect, useMemo, useRef, useState } from "react";
import GraphView, { GraphHandle } from "./GraphView";
import ErdView2D, { Erd2dHandle } from "./ErdView2D";
import Legend from "./Legend";
import { fetchErd, navigateToSource, searchErd } from "./api";
import { GraphData, GraphNode } from "./types";
import { UI, RADIUS, controlButton } from "./theme";

type Scope = "all" | "seed";
type SeedType = "fqn" | "package";
type Level = 1 | 2 | 3;
type ViewMode = "3d" | "2d";

interface ErdParams {
  scope: Scope;
  seed?: string;
  /** seed 해석 방식: 단일 엔티티(fqn) vs 패키지+하위(package). */
  seedType: SeedType;
  level: Level;
  depth: number;
  view: ViewMode;
  showExtends: boolean;
}

const EMPTY_DATA: GraphData = { seed: "", depth: 0, nodes: [], links: [] };

function readParamsFromHash(): ErdParams {
  const hash = window.location.hash.slice(1);
  const qIdx = hash.indexOf("?");
  const qs = qIdx >= 0 ? hash.slice(qIdx + 1) : "";
  const p = new URLSearchParams(qs);
  const scope = (p.get("scope") === "seed" ? "seed" : "all") as Scope;
  const lvlRaw = parseInt(p.get("level") ?? "1", 10);
  const level: Level = lvlRaw === 2 ? 2 : lvlRaw === 3 ? 3 : 1;
  const depth = parseInt(p.get("depth") ?? "2", 10);
  const seed = p.get("seed") ?? undefined;
  const seedType: SeedType = p.get("seedType") === "package" ? "package" : "fqn";
  const view: ViewMode = p.get("view") === "2d" ? "2d" : "3d";
  // showExtends 미지정이면 기본 true (기존 동작 유지)
  const showExtends = p.get("extends") !== "0";
  return { scope, seed, seedType, level, depth: Number.isFinite(depth) ? depth : 2, view, showExtends };
}

function writeParamsToHash(params: ErdParams) {
  const p = new URLSearchParams();
  p.set("scope", params.scope);
  p.set("level", String(params.level));
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

/** 검색 드롭다운에 표시할 정규화된 제안 항목 — 패키지/노드 두 종류를 한 모양으로. */
interface SuggestItem {
  key: string;
  primary: string;
  secondary: string;
  onPick: () => void;
}

export default function ErdApp() {
  const [params, setParams] = useState<ErdParams>(() => readParamsFromHash());
  const [data, setData] = useState<GraphData>(EMPTY_DATA);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [suggests, setSuggests] = useState<GraphNode[]>([]);
  const [query, setQuery] = useState(params.seed ?? "");
  const [size, setSize] = useState({ w: window.innerWidth, h: window.innerHeight });
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

  // URL ↔ state 동기화
  useEffect(() => {
    writeParamsToHash(params);
  }, [params]);

  // ERD 데이터 fetch — params 변경 또는 invalidate 이벤트(refreshTick) 시 재실행
  useEffect(() => {
    if (params.scope === "seed" && !params.seed) {
      setData(EMPTY_DATA);
      return;
    }
    setLoading(true);
    setError(null);
    fetchErd({
      scope: params.scope,
      seed: params.seed,
      seedType: params.seedType,
      depth: params.depth,
      level: params.level,
      showExtends: params.showExtends
    })
      .then(setData)
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [params, refreshTick]);

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
      const target = data.links.find((l) => l.source === n.id && l.relation === "USES_ENTITY")?.target;
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
      <div style={{
        position: "absolute", top: 0, left: 0, right: 0, minHeight: 48,
        display: "flex", flexWrap: "wrap", alignItems: "center", gap: 12,
        padding: "6px 12px", rowGap: 8,
        background: UI.panel, borderBottom: `1px solid ${UI.border}`, fontSize: 13, zIndex: 30
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
          </>
        )}
        <LevelToggle value={params.level} onChange={(level) => setParams({ ...params, level })} />
        <Divider />
        <ExtendsToggle value={params.showExtends} onChange={(showExtends) => setParams({ ...params, showExtends })} />
        <Divider />
        <ViewToggle value={params.view} onChange={(view) => setParams({ ...params, view })} />

        {/* 검색 — 남는 공간을 차지하다가 좁아지면 다음 줄로 */}
        <div style={{ position: "relative", flex: "1 1 200px", minWidth: 160, maxWidth: 380 }}>
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
              width: "100%", padding: "5px 26px 5px 8px", fontSize: 13,
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
            title="현재 프로젝트 상태로 ERD 재분석"
            style={{
              ...controlButton,
              padding: "0 10px",
              background: loading ? UI.border : "transparent",
              color: loading ? UI.textMuted : UI.textDim,
              cursor: loading ? "wait" : "pointer"
            }}
          >
            {loading ? "동기화 중..." : "↻ 동기화"}
          </button>
          <div style={{ color: UI.textMuted, fontSize: 12, whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums" }}>
            {`노드 ${data.nodes.length} · 관계 ${data.links.length}`}
          </div>
        </div>
      </div>

      {/* 그래프 영역 */}
      <div style={{ position: "absolute", top: 48, left: 0, right: 0, bottom: 0 }}>
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
              height={size.h - 48}
              level={params.level}
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
              height={size.h - 48}
              level={params.level}
              highlightedIds={highlightedIds}
              highlightBaseId={params.seed}
              onNodeSelect={(n) => navigateToSource(n.id)}
              onNodeReseed={(n) => pickSeed(n)}
            />
            <Legend view="3d" />
          </>
        )}
      </div>

      {showHelp && <HelpPopover onClose={() => setShowHelp(false)} />}
    </div>
  );
}

// 모든 토글 그룹은 [라벨: 버튼…] 형태로 통일 — 라벨 정책 일관.
const groupStyle: React.CSSProperties = { display: "flex", gap: 4, alignItems: "center" };
const labelStyle: React.CSSProperties = { color: UI.textMuted, fontSize: 12, whiteSpace: "nowrap" };

function Divider() {
  return <div aria-hidden style={{ width: 1, height: 20, background: UI.border, flexShrink: 0 }} />;
}

function ScopeToggle({ value, onChange }: { value: Scope; onChange: (v: Scope) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>범위:</span>
      <SegBtn active={value === "all"} onClick={() => onChange("all")} title="모든 엔티티 표시">전체</SegBtn>
      <SegBtn active={value === "seed"} onClick={() => onChange("seed")} title="선택한 중심에서 도달 가능한 부분만">중심</SegBtn>
    </div>
  );
}

function SeedTypeToggle({ value, onChange }: { value: SeedType; onChange: (v: SeedType) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>기준:</span>
      <SegBtn active={value === "fqn"} onClick={() => onChange("fqn")}>엔티티</SegBtn>
      <SegBtn active={value === "package"} onClick={() => onChange("package")}>패키지</SegBtn>
    </div>
  );
}

function LevelToggle({ value, onChange }: { value: Level; onChange: (v: Level) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>표시:</span>
      <SegBtn active={value === 1} onClick={() => onChange(1)}>관계만</SegBtn>
      <SegBtn active={value === 2} onClick={() => onChange(2)}>+컬럼</SegBtn>
      <SegBtn active={value === 3} onClick={() => onChange(3)}>+리포지토리</SegBtn>
    </div>
  );
}

function ExtendsToggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>상속:</span>
      <SegBtn active={value} onClick={() => onChange(!value)} title="상속 / @MappedSuperclass 관계 표시">
        {value ? "ON" : "OFF"}
      </SegBtn>
    </div>
  );
}

function ViewToggle({ value, onChange }: { value: ViewMode; onChange: (v: ViewMode) => void }) {
  return (
    <div style={groupStyle}>
      <span style={labelStyle}>뷰:</span>
      <SegBtn active={value === "3d"} onClick={() => onChange("3d")}>3D</SegBtn>
      <SegBtn active={value === "2d"} onClick={() => onChange("2d")}>2D</SegBtn>
    </div>
  );
}

function SegBtn({ active, onClick, children, title }: {
  active: boolean; onClick: () => void; children: React.ReactNode; title?: string;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-pressed={active}
      style={{
        padding: "4px 10px", fontSize: 12,
        background: active ? UI.accent : "transparent",
        color: active ? "#fff" : UI.textDim,
        border: `1px solid ${active ? UI.accent : UI.borderStrong}`,
        borderRadius: 4, cursor: "pointer",
        transition: "background 0.12s ease, color 0.12s ease, border-color 0.12s ease"
      }}
    >
      {children}
    </button>
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

function HelpPopover({ onClose }: { onClose: () => void }) {
  const rows: [string, string][] = [
    ["클릭", "엔티티의 소스 파일로 이동"],
    ["우클릭", "그 노드를 중심(seed)으로 다시 탐색"],
    ["드래그 (2D)", "노드 위치 이동 · '위치초기화'로 복원"],
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
          position: "absolute", top: 52, right: 12, width: 320,
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
