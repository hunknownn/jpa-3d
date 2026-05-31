import { useEffect, useRef, useState } from "react";
import GraphView, { GraphHandle } from "./GraphView";
import ErdView2D, { Erd2dHandle } from "./ErdView2D";
import { fetchErd, navigateToSource, searchErd } from "./api";
import { GraphData, GraphNode } from "./types";

type Scope = "all" | "seed";
type Level = 1 | 2 | 3;
type ViewMode = "3d" | "2d";

interface ErdParams {
  scope: Scope;
  seed?: string;
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
  const view: ViewMode = p.get("view") === "2d" ? "2d" : "3d";
  // showExtends 미지정이면 기본 true (기존 동작 유지)
  const showExtends = p.get("extends") !== "0";
  return { scope, seed, level, depth: Number.isFinite(depth) ? depth : 2, view, showExtends };
}

function writeParamsToHash(params: ErdParams) {
  const p = new URLSearchParams();
  p.set("scope", params.scope);
  p.set("level", String(params.level));
  p.set("view", params.view);
  if (!params.showExtends) p.set("extends", "0");
  if (params.scope === "seed") {
    if (params.seed) p.set("seed", params.seed);
    p.set("depth", String(params.depth));
  }
  const newHash = `/erd?${p.toString()}`;
  if (window.location.hash.slice(1) !== newHash) {
    window.location.hash = newHash;
  }
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
        setParams({ ...params, scope: "seed", seed: target });
        setQuery(target);
        setSuggests([]);
        return;
      }
    }
    setParams({ ...params, scope: "seed", seed: n.id });
    setQuery(n.id);
    setSuggests([]);
  }

  return (
    <div style={{ position: "fixed", inset: 0, background: "#0f172a", color: "#e2e8f0" }}>
      {/* 컨트롤 바 */}
      <div style={{
        position: "absolute", top: 0, left: 0, right: 0, height: 48,
        display: "flex", alignItems: "center", gap: 16, padding: "0 16px",
        background: "#1e293b", borderBottom: "1px solid #334155", fontSize: 13
      }}>
        <ScopeToggle value={params.scope} onChange={(scope) => setParams({ ...params, scope })} />
        <LevelToggle value={params.level} onChange={(level) => setParams({ ...params, level })} />
        <ExtendsToggle value={params.showExtends} onChange={(showExtends) => setParams({ ...params, showExtends })} />
        <ViewToggle value={params.view} onChange={(view) => setParams({ ...params, view })} />
        <div style={{ position: "relative" }}>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={params.scope === "seed" ? "seed 검색…" : "노드 검색 (하이라이트)"}
            style={{
              width: 280, padding: "4px 8px", fontSize: 13,
              background: "#0f172a", color: "#e2e8f0",
              border: "1px solid #475569", borderRadius: 4
            }}
          />
          {query.trim() && (
            <button
              onClick={() => setQuery("")}
              title="검색 초기화"
              style={{
                position: "absolute", right: 4, top: 3,
                width: 22, height: 22, lineHeight: "20px", textAlign: "center",
                background: "transparent", color: "#94a3b8",
                border: "none", borderRadius: 4, cursor: "pointer", fontSize: 14
              }}
            >×</button>
          )}
          {suggests.length > 0 && (
            <div style={{
              position: "absolute", top: 28, left: 0, width: 360,
              background: "#1e293b", border: "1px solid #475569", borderRadius: 4,
              maxHeight: 240, overflowY: "auto", zIndex: 10
            }}>
              {suggests.map((n) => (
                <div
                  key={n.id}
                  onClick={() => pickSeed(n)}
                  style={{
                    padding: "6px 10px", cursor: "pointer",
                    borderBottom: "1px solid #334155", fontSize: 12
                  }}
                >
                  <div>{n.name}</div>
                  <div style={{ color: "#94a3b8", fontSize: 11 }}>
                    {n.entity ? `Entity (${n.entity.kind})` : "Repository"} · {n.pkg}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
        <div style={{ flex: 1 }} />
        <button
          onClick={() => setRefreshTick((t) => t + 1)}
          disabled={loading}
          title="현재 프로젝트 상태로 ERD 재분석"
          style={{
            padding: "4px 10px", fontSize: 12,
            background: loading ? "#334155" : "transparent",
            color: loading ? "#64748b" : "#cbd5e1",
            border: "1px solid #475569", borderRadius: 4,
            cursor: loading ? "wait" : "pointer"
          }}
        >
          {loading ? "동기화 중..." : "↻ 동기화"}
        </button>
        <div style={{ color: "#94a3b8", fontSize: 12, marginLeft: 12 }}>
          {`노드 ${data.nodes.length} · 관계 ${data.links.length}`}
        </div>
      </div>

      {/* 그래프 영역 */}
      <div style={{ position: "absolute", top: 48, left: 0, right: 0, bottom: 0 }}>
        {isIndexing ? (
          <IndexingState />
        ) : showEmpty ? (
          <EmptyState />
        ) : error ? (
          <div style={{ padding: 24, color: "#f87171" }}>{error}</div>
        ) : params.view === "2d" ? (
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
        ) : (
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
        )}
      </div>
    </div>
  );
}

function ScopeToggle({ value, onChange }: { value: Scope; onChange: (v: Scope) => void }) {
  return (
    <div style={{ display: "flex", gap: 4 }}>
      <SegBtn active={value === "all"} onClick={() => onChange("all")}>전체</SegBtn>
      <SegBtn active={value === "seed"} onClick={() => onChange("seed")}>seed 중심</SegBtn>
    </div>
  );
}

function LevelToggle({ value, onChange }: { value: Level; onChange: (v: Level) => void }) {
  return (
    <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
      <span style={{ color: "#94a3b8", fontSize: 12 }}>표시:</span>
      <SegBtn active={value === 1} onClick={() => onChange(1)}>관계만</SegBtn>
      <SegBtn active={value === 2} onClick={() => onChange(2)}>+컬럼</SegBtn>
      <SegBtn active={value === 3} onClick={() => onChange(3)}>+Repository</SegBtn>
    </div>
  );
}

function ExtendsToggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
      <SegBtn active={value} onClick={() => onChange(!value)}>상속 {value ? "ON" : "OFF"}</SegBtn>
    </div>
  );
}

function ViewToggle({ value, onChange }: { value: ViewMode; onChange: (v: ViewMode) => void }) {
  return (
    <div style={{ display: "flex", gap: 4, alignItems: "center" }}>
      <span style={{ color: "#94a3b8", fontSize: 12 }}>뷰:</span>
      <SegBtn active={value === "3d"} onClick={() => onChange("3d")}>3D</SegBtn>
      <SegBtn active={value === "2d"} onClick={() => onChange("2d")}>2D</SegBtn>
    </div>
  );
}

function SegBtn({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: "4px 10px", fontSize: 12,
        background: active ? "#3b82f6" : "transparent",
        color: active ? "#fff" : "#cbd5e1",
        border: `1px solid ${active ? "#3b82f6" : "#475569"}`,
        borderRadius: 4, cursor: "pointer"
      }}
    >
      {children}
    </button>
  );
}

function EmptyState() {
  return (
    <div style={{
      display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
      height: "100%", color: "#94a3b8", gap: 12
    }}>
      <div style={{ fontSize: 18 }}>이 프로젝트에서 JPA Entity를 찾지 못했습니다.</div>
      <div style={{ fontSize: 13 }}>
        분석 대상이 빌드되어 있고 패키지 범위 설정이 올바른지 확인해 주세요.
      </div>
    </div>
  );
}

function IndexingState() {
  return (
    <div style={{
      display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center",
      height: "100%", color: "#94a3b8", gap: 8
    }}>
      <div style={{ fontSize: 18 }}>IDE 인덱싱 진행 중…</div>
      <div style={{ fontSize: 13 }}>
        인덱싱이 끝나면 자동으로 분석 결과가 표시됩니다.
      </div>
    </div>
  );
}
