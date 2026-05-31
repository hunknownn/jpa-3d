import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import ELK, { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { GraphData, GraphLink, GraphNode, Relation } from "./types";

interface Props {
  data: GraphData;
  width: number;
  height: number;
  level: 1 | 2 | 3;
  onNodeReseed: (n: GraphNode) => void;
  onNodeNavigate?: (n: GraphNode) => void;
  /** 검색 매칭 노드 id 집합. 비어있지 않으면 비매칭 노드/엣지를 페이드한다. */
  highlightedIds?: Set<string>;
  /** 하이라이트 기준 노드(보통 현재 seed) — 항상 매칭으로 간주된다. */
  highlightBaseId?: string;
}

const RELATION_LABEL: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "1:N",
  MANY_TO_ONE: "N:1",
  ONE_TO_ONE: "1:1",
  MANY_TO_MANY: "M:N",
  EXTENDS: "extends",
  USES_ENTITY: "uses"
};

/**
 * Crow's foot 표기:
 *  - 1 (cf-one): 수직 막대
 *  - N (cf-many): 세 갈래 까마귀발
 *
 * 우리 모델에서는 관계가 source → target 방향으로 표기되므로:
 *  - ONE_TO_MANY: source=1, target=N
 *  - MANY_TO_ONE: source=N, target=1
 *  - ONE_TO_ONE: 양쪽 1
 *  - MANY_TO_MANY: 양쪽 N
 *  - EXTENDS / USES_ENTITY: 카디널리티 없음 → 화살표 유지
 */
function sourceMarker(rel: Relation): string | null {
  switch (rel) {
    case "ONE_TO_MANY":
    case "ONE_TO_ONE":
      return "cf-one";
    case "MANY_TO_ONE":
    case "MANY_TO_MANY":
      return "cf-many";
    default:
      return null;
  }
}
function targetMarker(rel: Relation): string | null {
  switch (rel) {
    case "MANY_TO_ONE":
    case "ONE_TO_ONE":
      return "cf-one";
    case "ONE_TO_MANY":
    case "MANY_TO_MANY":
      return "cf-many";
    default:
      return null;
  }
}

const RELATION_COLOR: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "#10b981",
  MANY_TO_ONE: "#14b8a6",
  ONE_TO_ONE: "#06b6d4",
  MANY_TO_MANY: "#6366f1",
  EXTENDS: "#ff7b7b",
  USES_ENTITY: "#eab308"
};

const CARD_W = 260;
const CARD_HEADER_H = 32;
const INH_BAR_H = 18;
const ROW_H = 20;

const INHERITANCE_COLOR: Record<string, string> = {
  SINGLE_TABLE: "#92400e",   // amber-800
  JOINED: "#6d28d9",         // violet-700
  TABLE_PER_CLASS: "#0e7490" // cyan-700
};

const INHERITANCE_LABEL: Record<string, string> = {
  SINGLE_TABLE: "SINGLE",
  JOINED: "JOINED",
  TABLE_PER_CLASS: "TPC"
};

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
interface NodeOffset { dx: number; dy: number }
interface NodeDrag {
  id: string;
  startClientX: number;
  startClientY: number;
  startDx: number;
  startDy: number;
  moved: number;
}

const CLICK_THRESHOLD_PX = 4;

/**
 * 외부(plugin) 가 호출하는 명령형 인터페이스.
 * Snapshot 은 현재 `<svg>` 엘리먼트를 그대로 시리얼라이즈하거나, canvas 로 그려 PNG 로 변환한다.
 */
export interface Erd2dHandle {
  snapshotSvg(): string;
  snapshotPng(): Promise<string>;
}

const ErdView2D = forwardRef<Erd2dHandle, Props>(function ErdView2D({
  data, width, height, level, onNodeReseed, onNodeNavigate,
  highlightedIds, highlightBaseId
}, ref) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const hlActive = !!highlightedIds && highlightedIds.size > 0;
  const isHighlighted = (id: string): boolean =>
    !!highlightedIds && (highlightedIds.has(id) || id === highlightBaseId);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState<{ x: number; y: number } | null>(null);
  const [hoverEdge, setHoverEdge] = useState<string | null>(null);
  // 컬럼 row 위에 마우스가 올라온 entity. 그 entity 에 연결된 엣지를 강조.
  // null 일 때는 평소대로.
  const [hoverEntityId, setHoverEntityId] = useState<string | null>(null);
  const [rankdir, setRankdir] = useState<RankDir>("LR");
  const [layout, setLayout] = useState<Layout>(EMPTY_LAYOUT);
  // 노드별 사용자 조정 offset. 자동 레이아웃 결과 위에 누적된다.
  const [nodeOffsets, setNodeOffsets] = useState<Map<string, NodeOffset>>(new Map());
  // 진행 중인 노드 드래그 — render 영향이 없으니 ref 로 잡음
  const nodeDragRef = useRef<NodeDrag | null>(null);
  const layoutSeq = useRef(0);

  // === Snapshot 출력 ===
  // 외부(plugin) 가 호출. SVG 는 그대로 직렬화, PNG 는 SVG 를 임시 <img> → canvas 로 그려 dataURL 생성.
  useImperativeHandle(ref, () => ({
    snapshotSvg: () => {
      const node = svgRef.current;
      if (!node) return "";
      const clone = node.cloneNode(true) as SVGSVGElement;
      if (!clone.getAttribute("xmlns")) clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
      // 배경색을 명시 — viewer 의 어두운 배경이 그대로 캡처되도록.
      const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      bg.setAttribute("width", String(width));
      bg.setAttribute("height", String(height));
      bg.setAttribute("fill", "#0f172a");
      clone.insertBefore(bg, clone.firstChild);
      return new XMLSerializer().serializeToString(clone);
    },
    snapshotPng: async () => {
      const svgString = (function () {
        const node = svgRef.current;
        if (!node) return "";
        const clone = node.cloneNode(true) as SVGSVGElement;
        if (!clone.getAttribute("xmlns")) clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
        return new XMLSerializer().serializeToString(clone);
      })();
      if (!svgString) return "";
      const blob = new Blob([svgString], { type: "image/svg+xml;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      try {
        const img = new Image();
        await new Promise<void>((resolve, reject) => {
          img.onload = () => resolve();
          img.onerror = () => reject(new Error("SVG → image load failed"));
          img.src = url;
        });
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d")!;
        ctx.fillStyle = "#0f172a";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0);
        return canvas.toDataURL("image/png");
      } finally {
        URL.revokeObjectURL(url);
      }
    }
  }), [width, height]);

  // 레이아웃 방향이 바뀌면(LR↔TB) 좌표계가 회전되니 offset 도 초기화.
  // 데이터 변경(엔티티 추가/삭제) 만으로는 offset 유지.
  useEffect(() => {
    setNodeOffsets(new Map());
  }, [rankdir]);

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
      onMouseDown={(e) => {
        // 노드 드래그가 stopPropagation 으로 막아주지 않는 빈 캔버스에서만 팬 시작
        if (nodeDragRef.current) return;
        setDragging({ x: e.clientX - pan.x, y: e.clientY - pan.y });
      }}
      onMouseMove={(e) => {
        const nd = nodeDragRef.current;
        if (nd) {
          const dxC = e.clientX - nd.startClientX;
          const dyC = e.clientY - nd.startClientY;
          nd.moved = Math.max(nd.moved, Math.hypot(dxC, dyC));
          const dx = nd.startDx + dxC / zoom;
          const dy = nd.startDy + dyC / zoom;
          setNodeOffsets((prev) => {
            const next = new Map(prev);
            next.set(nd.id, { dx, dy });
            return next;
          });
          return;
        }
        if (dragging) setPan({ x: e.clientX - dragging.x, y: e.clientY - dragging.y });
      }}
      onMouseUp={() => {
        const nd = nodeDragRef.current;
        if (nd) {
          // 거의 안 움직였으면 click 으로 간주 → navigate
          if (nd.moved < CLICK_THRESHOLD_PX && onNodeNavigate) {
            const node = data.nodes.find((n) => n.id === nd.id);
            if (node) onNodeNavigate(node);
          }
          nodeDragRef.current = null;
        }
        setDragging(null);
      }}
      onMouseLeave={() => {
        nodeDragRef.current = null;
        setDragging(null);
      }}
      onWheel={(e) => {
        const next = Math.max(0.2, Math.min(3, zoom * (e.deltaY < 0 ? 1.1 : 1 / 1.1)));
        setZoom(next);
      }}
    >
      <svg ref={svgRef} width={width} height={height} xmlns="http://www.w3.org/2000/svg">
        <defs>
          {/* EXTENDS / USES_ENTITY 용 화살촉 — 카디널리티가 없는 관계 */}
          {Object.entries(RELATION_COLOR).map(([rel, color]) => (
            <marker
              key={`arrow-${rel}`}
              id={`arrow-${rel}`}
              viewBox="0 0 10 10"
              refX={9} refY={5}
              markerWidth={6} markerHeight={6}
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill={color} />
            </marker>
          ))}
          {/*
            Crow's foot 마커.
            - cf-one: 노드 측에 수직 막대 (cardinality 1)
            - cf-many: 노드 측에 세 갈래 까마귀발 (cardinality N)
            refX=0 → 마커의 (0, refY) 가 path 끝점에 정렬됨. 즉 path 끝에서
            노드 쪽으로 +X 방향으로 그려진다.
            orient="auto-start-reverse" → markerStart 일 때 자동으로 뒤집혀
            same definition 으로 양 끝에 쓸 수 있다.
            stroke="context-stroke" → 사용하는 path 의 stroke 색을 그대로 상속.
          */}
          <marker
            id="cf-one"
            viewBox="0 0 14 14"
            refX={0} refY={7}
            markerWidth={14} markerHeight={14}
            orient="auto-start-reverse"
          >
            <line x1={8} y1={2} x2={8} y2={12} stroke="context-stroke" strokeWidth={1.6} />
          </marker>
          <marker
            id="cf-many"
            viewBox="0 0 14 14"
            refX={0} refY={7}
            markerWidth={14} markerHeight={14}
            orient="auto-start-reverse"
          >
            <line x1={0} y1={7} x2={12} y2={0} stroke="context-stroke" strokeWidth={1.4} />
            <line x1={0} y1={7} x2={12} y2={7} stroke="context-stroke" strokeWidth={1.4} />
            <line x1={0} y1={7} x2={12} y2={14} stroke="context-stroke" strokeWidth={1.4} />
          </marker>
        </defs>
        <g transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}>
          {/* 엣지 */}
          {data.links.map((l, i) => {
            const key = linkKey(l, i);
            const path = layout.edges.get(key);
            if (!path || path.points.length < 2) return null;
            const color = RELATION_COLOR[l.relation] ?? "#94a3b8";
            const isHover = hoverEdge === key;
            // 두 가지 페이드 사유:
            //  1) 검색 하이라이트 비활성 노드 사이 엣지
            //  2) hoverEntityId 가 있는데 양 끝 모두 그 entity 가 아닌 엣지
            const searchHl = !hlActive || isHighlighted(l.source) || isHighlighted(l.target);
            const hoverHl = hoverEntityId == null || l.source === hoverEntityId || l.target === hoverEntityId;
            const edgeHl = searchHl && hoverHl;
            // 양 끝 노드가 드래그된 경우 첫/끝 점만 offset 적용. 중간 bend points 는 유지.
            const sOff = nodeOffsets.get(l.source);
            const tOff = nodeOffsets.get(l.target);
            const adjustedPoints = (sOff || tOff)
              ? path.points.map((p, idx) => {
                  if (idx === 0 && sOff) return { x: p.x + sOff.dx, y: p.y + sOff.dy };
                  if (idx === path.points.length - 1 && tOff) return { x: p.x + tOff.dx, y: p.y + tOff.dy };
                  return p;
                })
              : path.points;
            const d = pointsToPath(adjustedPoints);
            const labelPoint = midPoint(adjustedPoints);
            return (
              <g
                key={key}
                opacity={edgeHl ? 1 : 0.15}
                onMouseEnter={() => setHoverEdge(key)}
                onMouseLeave={() => setHoverEdge((h) => (h === key ? null : h))}
              >
                <path
                  d={d}
                  fill="none"
                  stroke={color}
                  strokeWidth={isHover ? 2.5 : 1.5}
                  opacity={0.85}
                  markerStart={(() => {
                    const m = sourceMarker(l.relation);
                    return m ? `url(#${m})` : undefined;
                  })()}
                  markerEnd={(() => {
                    const m = targetMarker(l.relation);
                    return m ? `url(#${m})` : `url(#arrow-${l.relation})`;
                  })()}
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
            const off = nodeOffsets.get(n.id);
            // 검색 하이라이트로 dim
            const searchDim = hlActive && !isHighlighted(n.id);
            // hoverEntity 활성 시: hover 한 entity 본인과 그에 연결된 이웃만 강조, 나머지 dim
            const hoverDim = hoverEntityId != null
              && n.id !== hoverEntityId
              && !data.links.some((l) =>
                (l.source === hoverEntityId && l.target === n.id) ||
                (l.target === hoverEntityId && l.source === n.id));
            const dimmed = searchDim || hoverDim;
            return (
              <EntityCard
                key={n.id}
                node={n}
                x={box.x + (off?.dx ?? 0)}
                y={box.y + (off?.dy ?? 0)}
                level={level}
                dimmed={dimmed}
                onColumnHover={(hovering) => {
                  if (hovering) setHoverEntityId(n.id);
                  else setHoverEntityId((cur) => (cur === n.id ? null : cur));
                }}
                onReseed={() => onNodeReseed(n)}
                onDragStart={(clientX, clientY) => {
                  // outer div 의 mousemove/up 이 받아 처리. navigate 는 mouseup 시 movement 보고 결정.
                  nodeDragRef.current = {
                    id: n.id,
                    startClientX: clientX,
                    startClientY: clientY,
                    startDx: off?.dx ?? 0,
                    startDy: off?.dy ?? 0,
                    moved: 0
                  };
                }}
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
        {nodeOffsets.size > 0 && (
          <button
            style={zoomBtnStyle}
            title="드래그한 노드 위치를 자동 레이아웃으로 되돌림"
            onClick={() => setNodeOffsets(new Map())}
          >
            위치초기화
          </button>
        )}
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
});

export default ErdView2D;

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
  const inhExtra = n.entity?.inheritance ? INH_BAR_H : 0;
  if (level < 2 || !n.entity || n.entity.columns.length === 0) return CARD_HEADER_H + inhExtra + 8;
  return CARD_HEADER_H + inhExtra + n.entity.columns.length * ROW_H + 8;
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

function EntityCard({ node, x, y, level, dimmed, onReseed, onDragStart, onColumnHover }: {
  node: GraphNode; x: number; y: number; level: 1 | 2 | 3;
  dimmed?: boolean;
  onReseed: () => void;
  onDragStart: (clientX: number, clientY: number) => void;
  onColumnHover?: (hovering: boolean) => void;
}) {
  const isEntity = node.entity != null;
  const isMappedSuper = node.entity?.kind === "mappedSuperclass";
  const isEmbeddable = node.entity?.kind === "embeddable";
  const headerBg = isMappedSuper ? "#3730a3" : isEmbeddable ? "#0f766e" : isEntity ? "#1d4ed8" : "#475569";
  const showColumns = level >= 2 && isEntity && node.entity!.columns.length > 0;
  const h = cardHeight(node, level);

  // 테이블명이 클래스명과 다른 entity 만 우측에 표 이름을 작게 부기.
  // Repository / 동명 테이블 entity 는 한 줄(이름) 만 표시.
  const tableName = node.entity?.tableName;
  const showTableBadge = isEntity && tableName != null && tableName.toLowerCase() !== node.name.toLowerCase();

  const inh = node.entity?.inheritance;
  const inhColor = inh ? (INHERITANCE_COLOR[inh.strategy] ?? "#475569") : null;
  const inhLabel = inh ? (INHERITANCE_LABEL[inh.strategy] ?? inh.strategy) : null;
  const columnsY = CARD_HEADER_H + (inh ? INH_BAR_H : 0);

  return (
    <g
      transform={`translate(${x},${y})`}
      opacity={dimmed ? 0.18 : 1}
      onMouseDown={(e) => {
        e.stopPropagation();  // 캔버스 팬 방지
        onDragStart(e.clientX, e.clientY);
      }}
      onContextMenu={(e) => { e.preventDefault(); onReseed(); }}
      style={{ cursor: "grab" }}
    >
      <rect width={CARD_W} height={h} rx={6} fill="#1e293b" stroke="#334155" />
      <rect width={CARD_W} height={CARD_HEADER_H} rx={6} fill={headerBg} />
      <text x={12} y={CARD_HEADER_H / 2 + 5} fill="#f1f5f9" fontSize={15} fontWeight={600}>
        {node.name}
      </text>
      {showTableBadge && (
        <text
          x={CARD_W - 12} y={CARD_HEADER_H / 2 + 4}
          fill="#cbd5e1" fontSize={11} textAnchor="end" fontStyle="italic"
        >
          {tableName}
        </text>
      )}
      {inh && inhColor && inhLabel && (
        <g transform={`translate(0,${CARD_HEADER_H})`}>
          <rect width={CARD_W} height={INH_BAR_H} fill={inhColor} opacity={0.85} />
          <text x={8} y={13} fill="#fff" fontSize={10} fontWeight={600} letterSpacing={0.4}>
            {inhLabel}
            {inh.discriminatorColumn ? `  ·  ${inh.discriminatorColumn}` : ""}
          </text>
          {inh.discriminatorValue && (
            <text
              x={CARD_W - 8} y={13}
              fill="#fff" fontSize={10} textAnchor="end" fontStyle="italic" opacity={0.95}
            >
              = "{inh.discriminatorValue}"
            </text>
          )}
        </g>
      )}
      {showColumns && node.entity!.columns.map((c, i) => (
        <g
          key={c.fieldName}
          transform={`translate(0,${columnsY + i * ROW_H})`}
          onMouseEnter={() => onColumnHover?.(true)}
          onMouseLeave={() => onColumnHover?.(false)}
        >
          {/* 투명 hit area — 글자 사이 빈 공간에서도 hover 가 끊기지 않게 */}
          <rect x={0} y={0} width={CARD_W} height={ROW_H} fill="transparent" />
          <text
            x={12} y={15} fontSize={12}
            fill={c.primaryKey ? "#fbbf24" : c.foreignKey ? "#7dd3fc" : "#e2e8f0"}
          >
            {c.primaryKey ? "🔑 " : c.foreignKey ? "🔗 " : ""}{c.columnName ?? c.fieldName}
          </text>
          {/*
            우측 우편함: [unique ◆] [indexed #] type[* if not nullable]
            - ◆ : @Column(unique) 또는 @Table(uniqueConstraints) 에 포함
            - # : @Table(indexes) 의 columnList 에 포함 (PK 의 자동 index 는 제외)
          */}
          <text x={CARD_W - 12} y={15} fontSize={11} textAnchor="end">
            {c.unique && <tspan fill="#fde047">◆ </tspan>}
            {c.indexed && <tspan fill="#67e8f9"># </tspan>}
            <tspan fill="#94a3b8">{shortType(c.javaType)}{c.nullable ? "" : "*"}</tspan>
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
