import { useEffect, useMemo, useRef, useState } from "react";
import ELK, { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { GraphData, GraphLink, GraphNode, Relation } from "./types";

interface Props {
  data: GraphData;
  width: number;
  height: number;
  level: 1 | 2 | 3;
  onNodeReseed: (n: GraphNode) => void;
  onNodeNavigate?: (n: GraphNode) => void;
}

const RELATION_LABEL: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "1:N",
  MANY_TO_ONE: "N:1",
  ONE_TO_ONE: "1:1",
  MANY_TO_MANY: "M:N",
  EXTENDS: "extends",
  USES_ENTITY: "uses"
};

const RELATION_COLOR: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "#10b981",
  MANY_TO_ONE: "#14b8a6",
  ONE_TO_ONE: "#06b6d4",
  MANY_TO_MANY: "#6366f1",
  EXTENDS: "#ff7b7b",
  USES_ENTITY: "#eab308"
};

const CARD_W = 220;
const CARD_HEADER_H = 36;
const ROW_H = 18;

type RankDir = "LR" | "TB";

interface CardBox { x: number; y: number; w: number; h: number; }
interface EdgePath { points: { x: number; y: number }[]; }

interface Layout {
  nodes: Map<string, CardBox>;
  edges: Map<string, EdgePath>;
  width: number;
  height: number;
}

const EMPTY_LAYOUT: Layout = { nodes: new Map(), edges: new Map(), width: 0, height: 0 };

// elkjs 인스턴스는 worker 를 띄울 수 있어 전역에서 한 번만 생성
const elk = new ELK();

/**
 * elkjs 기반 2D ERD 렌더러.
 *
 * `layered` 알고리즘 + `ORTHOGONAL` 엣지 라우팅으로 직교(꺾인) 경로를 그린다.
 * 방향은 LR/TB 토글. 엣지 교차/겹침은 elkjs 가 dagre 보다 잘 처리한다.
 */
export default function ErdView2D({ data, width, height, level, onNodeReseed, onNodeNavigate }: Props) {
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState<{ x: number; y: number } | null>(null);
  const [hoverEdge, setHoverEdge] = useState<string | null>(null);
  const [rankdir, setRankdir] = useState<RankDir>("LR");
  const [layout, setLayout] = useState<Layout>(EMPTY_LAYOUT);
  const layoutSeq = useRef(0);

  const linkKey = useMemo(
    () => (l: GraphLink, i: number) => `${l.source}-${l.target}-${l.relation}-${i}`,
    []
  );

  // elkjs 는 비동기 layout 이라 useEffect 로 계산
  useEffect(() => {
    const seq = ++layoutSeq.current;
    computeElkLayout(data, level, rankdir).then((result) => {
      // 도중에 다른 layout 요청이 들어왔다면 무시
      if (seq !== layoutSeq.current) return;
      setLayout(result);
    });
  }, [data, level, rankdir]);

  // 레이아웃이 바뀌면 자동으로 화면에 맞춤
  useEffect(() => {
    if (layout.width === 0 || layout.height === 0) return;
    const margin = 40;
    const scale = Math.min(
      (width - margin * 2) / layout.width,
      (height - margin * 2) / layout.height,
      1
    );
    setZoom(scale);
    setPan({
      x: (width - layout.width * scale) / 2,
      y: (height - layout.height * scale) / 2
    });
  }, [layout, width, height]);

  return (
    <div
      style={{ position: "absolute", inset: 0, overflow: "hidden", background: "#0f172a", cursor: dragging ? "grabbing" : "grab" }}
      onMouseDown={(e) => setDragging({ x: e.clientX - pan.x, y: e.clientY - pan.y })}
      onMouseMove={(e) => { if (dragging) setPan({ x: e.clientX - dragging.x, y: e.clientY - dragging.y }); }}
      onMouseUp={() => setDragging(null)}
      onMouseLeave={() => setDragging(null)}
      onWheel={(e) => {
        const next = Math.max(0.2, Math.min(3, zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
        setZoom(next);
      }}
    >
      <svg width={width} height={height}>
        <defs>
          {Object.entries(RELATION_COLOR).map(([rel, color]) => (
            <marker
              key={rel}
              id={`arrow-${rel}`}
              viewBox="0 0 10 10"
              refX={9} refY={5}
              markerWidth={6} markerHeight={6}
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill={color} />
            </marker>
          ))}
        </defs>
        <g transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}>
          {/* 엣지 */}
          {data.links.map((l, i) => {
            const key = linkKey(l, i);
            const path = layout.edges.get(key);
            if (!path || path.points.length < 2) return null;
            const color = RELATION_COLOR[l.relation] ?? "#94a3b8";
            const isHover = hoverEdge === key;
            const d = pointsToPath(path.points);
            const labelPoint = midPoint(path.points);
            return (
              <g
                key={key}
                onMouseEnter={() => setHoverEdge(key)}
                onMouseLeave={() => setHoverEdge((h) => (h === key ? null : h))}
              >
                <path
                  d={d}
                  fill="none"
                  stroke={color}
                  strokeWidth={isHover ? 2.5 : 1.5}
                  opacity={0.85}
                  markerEnd={`url(#arrow-${l.relation})`}
                />
                <text
                  x={labelPoint.x}
                  y={labelPoint.y - 6}
                  fill={color}
                  fontSize={11}
                  textAnchor="middle"
                  stroke="#0f172a"
                  strokeWidth={3}
                  paintOrder="stroke"
                >
                  {RELATION_LABEL[l.relation] ?? l.relation}
                </text>
                {l.label && isHover && (
                  <text
                    x={labelPoint.x}
                    y={labelPoint.y + 10}
                    fill="#cbd5e1"
                    fontSize={10}
                    textAnchor="middle"
                    stroke="#0f172a"
                    strokeWidth={3}
                    paintOrder="stroke"
                  >
                    {l.label}
                  </text>
                )}
              </g>
            );
          })}

          {/* 노드 */}
          {data.nodes.map((n) => {
            const box = layout.nodes.get(n.id);
            if (!box) return null;
            return (
              <EntityCard
                key={n.id}
                node={n}
                x={box.x}
                y={box.y}
                level={level}
                onReseed={() => onNodeReseed(n)}
                onNavigate={onNodeNavigate ? () => onNodeNavigate(n) : undefined}
              />
            );
          })}
        </g>
      </svg>

      {/* 컨트롤 */}
      <div style={{
        position: "absolute", bottom: 16, right: 16,
        display: "flex", gap: 4, background: "#1e293b", padding: 4, borderRadius: 4
      }}>
        <button
          style={zoomBtnStyle}
          onClick={() => setRankdir((d) => (d === "LR" ? "TB" : "LR"))}
          title="방향 전환 (LR ↔ TB)"
        >
          {rankdir}
        </button>
        <button style={zoomBtnStyle} onClick={() => setZoom((z) => Math.min(3, z * 1.2))}>+</button>
        <button style={zoomBtnStyle} onClick={() => setZoom((z) => Math.max(0.2, z / 1.2))}>−</button>
        <button
          style={zoomBtnStyle}
          onClick={() => {
            const margin = 40;
            const scale = Math.min(
              (width - margin * 2) / Math.max(1, layout.width),
              (height - margin * 2) / Math.max(1, layout.height),
              1
            );
            setZoom(scale);
            setPan({
              x: (width - layout.width * scale) / 2,
              y: (height - layout.height * scale) / 2
            });
          }}
        >
          fit
        </button>
      </div>
    </div>
  );
}

function pointsToPath(points: { x: number; y: number }[]): string {
  if (points.length === 0) return "";
  const head = `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`;
  const rest = points.slice(1)
    .map((p) => `L ${p.x.toFixed(2)} ${p.y.toFixed(2)}`)
    .join(" ");
  return `${head} ${rest}`;
}

// 폴리라인 전체 길이의 중간 지점 → 라벨 위치
function midPoint(points: { x: number; y: number }[]): { x: number; y: number } {
  if (points.length === 1) return points[0];
  let total = 0;
  const seg: number[] = [];
  for (let i = 1; i < points.length; i++) {
    const dx = points[i].x - points[i - 1].x;
    const dy = points[i].y - points[i - 1].y;
    const len = Math.hypot(dx, dy);
    seg.push(len);
    total += len;
  }
  let half = total / 2;
  for (let i = 0; i < seg.length; i++) {
    if (half <= seg[i]) {
      const t = seg[i] === 0 ? 0 : half / seg[i];
      return {
        x: points[i].x + (points[i + 1].x - points[i].x) * t,
        y: points[i].y + (points[i + 1].y - points[i].y) * t
      };
    }
    half -= seg[i];
  }
  return points[points.length - 1];
}

function cardHeight(n: GraphNode, level: 1 | 2 | 3): number {
  if (level < 2 || !n.entity || n.entity.columns.length === 0) return CARD_HEADER_H + 8;
  return CARD_HEADER_H + n.entity.columns.length * ROW_H + 8;
}

/**
 * elkjs layered 알고리즘 + ORTHOGONAL 엣지 라우팅으로 레이아웃 계산.
 *
 * - 노드 좌표는 좌상단 기준 (dagre 와 달리 변환 불필요).
 * - 엣지 경로는 `section.startPoint + bendPoints + endPoint` 로 폴리라인 구성.
 * - multi-edge 는 elkjs 가 자체 id 로 구분하므로 link 인덱스를 id 에 섞어 충돌 회피.
 */
async function computeElkLayout(data: GraphData, level: 1 | 2 | 3, rankdir: RankDir): Promise<Layout> {
  if (data.nodes.length === 0) return EMPTY_LAYOUT;

  const direction = rankdir === "LR" ? "RIGHT" : "DOWN";

  const children: ElkNode[] = data.nodes.map((n) => ({
    id: n.id,
    width: CARD_W,
    height: cardHeight(n, level)
  }));

  const nodeIds = new Set(data.nodes.map((n) => n.id));
  const edges: ElkExtendedEdge[] = [];
  data.links.forEach((l, i) => {
    if (!nodeIds.has(l.source) || !nodeIds.has(l.target)) return;
    edges.push({
      id: `e-${i}-${l.relation}`,
      sources: [l.source],
      targets: [l.target]
    });
  });

  const graph: ElkNode = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.edgeRouting": "ORTHOGONAL",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      // 레이어 간 간격을 늘려 엣지가 비스킵 노드 본체를 우회할 공간 확보
      "elk.layered.spacing.nodeNodeBetweenLayers": "120",
      "elk.spacing.nodeNode": "60",
      // edge-node 간격이 좁으면 라우터가 노드 본체를 통과하는 경로를 선택할 수 있음
      "elk.spacing.edgeNode": "40",
      "elk.spacing.edgeEdge": "20",
      "elk.layered.spacing.edgeNodeBetweenLayers": "40",
      "elk.layered.spacing.edgeEdgeBetweenLayers": "20",
      "elk.layered.crossingMinimization.strategy": "LAYER_SWEEP",
      "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
      // 직선화 우선 — bend 가 줄고, 노드를 가로지르는 segment 발생률도 감소
      "elk.layered.nodePlacement.bk.edgeStraightening": "IMPROVE_STRAIGHTNESS",
      "elk.layered.unnecessaryBendpoints": "true",
      "elk.padding": "[top=30,left=30,bottom=30,right=30]"
    },
    children,
    edges
  };

  let result: ElkNode;
  try {
    result = await elk.layout(graph);
  } catch (err) {
    console.error("elkjs layout failed", err);
    return EMPTY_LAYOUT;
  }

  const nodeBoxes = new Map<string, CardBox>();
  for (const c of result.children ?? []) {
    nodeBoxes.set(c.id, {
      x: c.x ?? 0,
      y: c.y ?? 0,
      w: c.width ?? CARD_W,
      h: c.height ?? CARD_HEADER_H
    });
  }

  const edgePaths = new Map<string, EdgePath>();
  let edgeIdx = 0;
  data.links.forEach((l, i) => {
    if (!nodeIds.has(l.source) || !nodeIds.has(l.target)) return;
    const elkEdge = (result.edges ?? [])[edgeIdx++];
    if (!elkEdge || !elkEdge.sections || elkEdge.sections.length === 0) return;
    const section = elkEdge.sections[0];
    const points: { x: number; y: number }[] = [
      { x: section.startPoint.x, y: section.startPoint.y },
      ...(section.bendPoints ?? []).map((p) => ({ x: p.x, y: p.y })),
      { x: section.endPoint.x, y: section.endPoint.y }
    ];
    const key = `${l.source}-${l.target}-${l.relation}-${i}`;
    edgePaths.set(key, { points });
  });

  return {
    nodes: nodeBoxes,
    edges: edgePaths,
    width: result.width ?? 0,
    height: result.height ?? 0
  };
}

function EntityCard({ node, x, y, level, onReseed, onNavigate }: {
  node: GraphNode; x: number; y: number; level: 1 | 2 | 3;
  onReseed: () => void;
  onNavigate?: () => void;
}) {
  const isEntity = node.entity != null;
  const isMappedSuper = node.entity?.kind === "mappedSuperclass";
  const isEmbeddable = node.entity?.kind === "embeddable";
  const headerBg = isMappedSuper ? "#3730a3" : isEmbeddable ? "#0f766e" : isEntity ? "#1d4ed8" : "#475569";
  const tableLine = node.entity?.tableName ?? node.name;
  const showColumns = level >= 2 && isEntity && node.entity!.columns.length > 0;
  const h = cardHeight(node, level);

  return (
    <g
      transform={`translate(${x},${y})`}
      onClick={onNavigate}
      onContextMenu={(e) => { e.preventDefault(); onReseed(); }}
      style={{ cursor: onNavigate ? "pointer" : "default" }}
    >
      <rect width={CARD_W} height={h} rx={6} fill="#1e293b" stroke="#334155" />
      <rect width={CARD_W} height={CARD_HEADER_H} rx={6} fill={headerBg} />
      <text x={10} y={16} fill="#f1f5f9" fontSize={13} fontWeight={600}>{node.name}</text>
      <text x={10} y={30} fill="#cbd5e1" fontSize={10}>
        {isEntity ? `${tableLine}` : "Repository"}
      </text>
      {showColumns && node.entity!.columns.map((c, i) => (
        <g key={c.fieldName} transform={`translate(0,${CARD_HEADER_H + i * ROW_H})`}>
          <text x={10} y={14} fill={c.primaryKey ? "#fbbf24" : "#e2e8f0"} fontSize={11}>
            {c.primaryKey ? "🔑 " : ""}{c.columnName ?? c.fieldName}
          </text>
          <text x={CARD_W - 10} y={14} fill="#94a3b8" fontSize={10} textAnchor="end">
            {shortType(c.javaType)}{c.nullable ? "" : "*"}
          </text>
        </g>
      ))}
    </g>
  );
}

function shortType(t: string): string {
  const i = t.lastIndexOf(".");
  return i < 0 ? t : t.slice(i + 1);
}

const zoomBtnStyle: React.CSSProperties = {
  minWidth: 28, height: 28, fontSize: 12, padding: "0 6px",
  background: "transparent", color: "#cbd5e1",
  border: "1px solid #475569", borderRadius: 4, cursor: "pointer"
};
