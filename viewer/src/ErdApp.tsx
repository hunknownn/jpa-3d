import { useEffect, useRef, useState } from "react";
import GraphView, { GraphHandle } from "./GraphView";
import ErdView2D from "./ErdView2D";
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
  return { scope, seed, level, depth: Number.isFinite(depth) ? depth : 2, view };
}

function writeParamsToHash(params: ErdParams) {
  const p = new URLSearchParams();
  p.set("scope", params.scope);
  p.set("level", String(params.level));
  p.set("view", params.view);
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
  const graphRef = useRef<GraphHandle>(null);

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

  // ERD 데이터 fetch
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
      level: params.level
    })
      .then(setData)
      .catch((e) => setError(String(e?.message ?? e)))
      .finally(() => setLoading(false));
  }, [params]);

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
  const showEmpty = !loading && !error && params.scope === "all" && !hasEntities;

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
        <ViewToggle value={params.view} onChange={(view) => setParams({ ...params, view })} />
        {params.scope === "seed" && (
          <div style={{ position: "relative" }}>
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Entity 또는 Repository 검색..."
              style={{
                width: 280, padding: "4px 8px", fontSize: 13,
                background: "#0f172a", color: "#e2e8f0",
                border: "1px solid #475569", borderRadius: 4
              }}
            />
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
        )}
        <div style={{ flex: 1 }} />
        <div style={{ color: "#94a3b8", fontSize: 12 }}>
          {loading ? "로딩 중..." : `노드 ${data.nodes.length} · 관계 ${data.links.length}`}
        </div>
      </div>

      {/* 그래프 영역 */}
      <div style={{ position: "absolute", top: 48, left: 0, right: 0, bottom: 0 }}>
        {showEmpty ? (
          <EmptyState />
        ) : error ? (
          <div style={{ padding: 24, color: "#f87171" }}>{error}</div>
        ) : params.view === "2d" ? (
          <ErdView2D
            data={data}
            width={size.w}
            height={size.h - 48}
            level={params.level}
            onNodeReseed={(n) => pickSeed(n)}
            onNodeNavigate={(n) => navigateToSource(n.id)}
          />
        ) : (
          <GraphView
            ref={graphRef}
            data={data}
            width={size.w}
            height={size.h - 48}
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
