import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import ELK, { ElkExtendedEdge, ElkNode } from "elkjs/lib/elk.bundled.js";
import { GraphData, GraphLink, GraphNode, Relation } from "./types";
import {
  UI, RELATION_COLOR, RELATION_DASH,
  INHERITANCE_COLOR, KIND_COLOR, COLUMN_MARK, kindKey,
  RADIUS, FONT_MONO, controlButton
} from "./theme";
import { IconDirection, IconFit, IconReset } from "./Icons";
import { t, relationLabel, inheritanceLabel } from "./i18n";
import { fitText, measureWidth } from "./textFit";

interface Props {
  data: GraphData;
  width: number;
  height: number;
  /** 컬럼 표시 여부 (관계는 항상 표시). */
  showColumns: boolean;
  /** 더블 클릭 — 그 노드를 중심(seed)으로 다시 탐색. */
  onNodeReseed: (n: GraphNode) => void;
  /** 우클릭 — 그 노드를 view 에서 제거(숨김). */
  onNodeHide: (n: GraphNode) => void;
  /** 단일 클릭 — 소스 파일 열기. @param split Cmd/Ctrl 을 누른 채 클릭 → 에디터 분할로 열기. */
  onNodeNavigate?: (n: GraphNode, split: boolean) => void;
  /** 검색 매칭 노드 id 집합. 비어있지 않으면 비매칭 노드/엣지를 페이드한다. */
  highlightedIds?: Set<string>;
  /** 하이라이트 기준 노드(보통 현재 seed) — 항상 매칭으로 간주된다. */
  highlightBaseId?: string;
}

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

const CARD_W = 260;
const CARD_HEADER_H = 32;
const INH_BAR_H = 18;
const ROW_H = 22;
// 헤더 텍스트는 SVG 기본 sans 를 따른다. fitText 측정 정확도를 위한 근사 폰트 스택
// (실제와 약간 달라도 보수적으로 잘리는 쪽이라 오버플로는 나지 않는다).
const HEADER_FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';

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
  data, width, height, showColumns, onNodeReseed, onNodeHide, onNodeNavigate,
  highlightedIds, highlightBaseId
}, ref) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  // 단일/더블 클릭 구분 타이머 — 단일=소스 이동, 더블=중심 보기.
  const clickTimer = useRef<number | null>(null);
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
    computeElkLayout(data, showColumns, rankdir).then((result) => {
      // 도중에 다른 layout 요청이 들어왔다면 무시
      if (seq !== layoutSeq.current) return;
      setLayout(result);
    });
  }, [data, showColumns, rankdir]);

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

  // 단일=소스 이동 / 더블=중심 보기 / Cmd·Ctrl=분할 에디터. 단일·더블은 250ms 로 구분.
  function handleNodeClick(node: GraphNode, split: boolean) {
    if (split) {
      if (clickTimer.current != null) { window.clearTimeout(clickTimer.current); clickTimer.current = null; }
      onNodeNavigate?.(node, true);
      return;
    }
    if (clickTimer.current != null) {
      window.clearTimeout(clickTimer.current);
      clickTimer.current = null;
      onNodeReseed(node); // 더블클릭 — 중심 보기
      return;
    }
    clickTimer.current = window.setTimeout(() => {
      clickTimer.current = null;
      onNodeNavigate?.(node, false); // 단일클릭 — 소스 이동
    }, 250);
  }

  return (
    <div
      style={{ position: "absolute", inset: 0, overflow: "hidden", background: UI.canvas, cursor: dragging ? "grabbing" : "grab" }}
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
      onMouseUp={(e) => {
        const nd = nodeDragRef.current;
        if (nd) {
          // 거의 안 움직였으면 click 으로 간주 → 단일=소스, 더블=중심, Cmd/Ctrl=분할.
          if (nd.moved < CLICK_THRESHOLD_PX) {
            const node = data.nodes.find((n) => n.id === nd.id);
            if (node) handleNodeClick(node, e.metaKey || e.ctrlKey);
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
            const color = RELATION_COLOR[l.relation] ?? UI.textMuted;
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
                style={{ transition: "opacity 0.15s ease" }}
                onMouseEnter={() => setHoverEdge(key)}
                onMouseLeave={() => setHoverEdge((h) => (h === key ? null : h))}
              >
                <path
                  d={d}
                  fill="none"
                  stroke={color}
                  strokeWidth={isHover ? 2.5 : 1.5}
                  strokeDasharray={RELATION_DASH[l.relation]}
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
                  stroke={UI.canvas}
                  strokeWidth={3}
                  paintOrder="stroke"
                >
                  {relationLabel(l.relation)}
                </text>
                {l.label && isHover && (
                  <text
                    x={labelPoint.x}
                    y={labelPoint.y + 10}
                    fill={UI.textDim}
                    fontSize={10}
                    textAnchor="middle"
                    stroke={UI.canvas}
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
                showColumns={showColumns}
                dimmed={dimmed}
                onColumnHover={(hovering) => {
                  if (hovering) setHoverEntityId(n.id);
                  else setHoverEntityId((cur) => (cur === n.id ? null : cur));
                }}
                onHide={() => onNodeHide(n)}
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

      {/* 미니맵 — 전체 그래프 축소도 + 현재 보이는 영역 표시. 클릭으로 해당 지점으로 이동. */}
      <Minimap
        layout={layout}
        nodes={data.nodes}
        nodeOffsets={nodeOffsets}
        pan={pan}
        zoom={zoom}
        viewW={width}
        viewH={height}
        onJump={(wx, wy) => setPan({ x: width / 2 - wx * zoom, y: height / 2 - wy * zoom })}
      />

      {/* 컨트롤 */}
      <div style={{
        position: "absolute", bottom: 16, right: 16,
        display: "flex", alignItems: "center", gap: 4,
        background: UI.panel, padding: 4, borderRadius: RADIUS.control, border: `1px solid ${UI.border}`
      }}>
        <button
          style={zoomBtnStyle}
          onClick={() => setRankdir((d) => (d === "LR" ? "TB" : "LR"))}
          title={t("erd2d.direction.title", [rankdir === "LR" ? t("erd2d.direction.horizontal") : t("erd2d.direction.vertical")])}
          aria-label={t("erd2d.direction.aria")}
        >
          <IconDirection horizontal={rankdir === "LR"} />
        </button>
        <button style={zoomBtnStyle} title={t("ctrl.zoomOut")} onClick={() => setZoom((z) => Math.max(0.2, z / 1.2))}>−</button>
        <span style={{
          minWidth: 44, textAlign: "center", fontSize: 11, color: UI.textMuted,
          fontVariantNumeric: "tabular-nums"
        }}>
          {Math.round(zoom * 100)}%
        </span>
        <button style={zoomBtnStyle} title={t("ctrl.zoomIn")} onClick={() => setZoom((z) => Math.min(3, z * 1.2))}>+</button>
        {nodeOffsets.size > 0 && (
          <button
            style={zoomBtnStyle}
            title={t("ctrl.resetPos")}
            aria-label={t("ctrl.resetPos.aria")}
            onClick={() => setNodeOffsets(new Map())}
          >
            <IconReset />
          </button>
        )}
        <button
          style={zoomBtnStyle}
          title={t("ctrl.fit")}
          aria-label={t("ctrl.fit.aria")}
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
          <IconFit />
        </button>
      </div>
    </div>
  );
});

/** 우상단 미니맵. 노드 박스를 축소해 그리고, 현재 보이는 영역을 사각형으로 오버레이. */
function Minimap({ layout, nodes, nodeOffsets, pan, zoom, viewW, viewH, onJump }: {
  layout: Layout;
  nodes: GraphNode[];
  nodeOffsets: Map<string, NodeOffset>;
  pan: { x: number; y: number };
  zoom: number;
  viewW: number;
  viewH: number;
  onJump: (worldX: number, worldY: number) => void;
}) {
  if (layout.width <= 0 || layout.height <= 0 || layout.nodes.size < 2) return null;
  // 좁거나 낮은 도킹(left/right/bottom)에선 미니맵이 화면을 가리므로 숨긴다.
  if (viewW < 380 || viewH < 320) return null;
  // 미니맵 크기를 뷰 크기에 비례시켜 작은 창에서 과하게 커지지 않게.
  const MM_MAX = Math.max(96, Math.min(168, Math.min(viewW, viewH) * 0.26));
  const s = Math.min(MM_MAX / layout.width, MM_MAX / layout.height);
  const mmW = layout.width * s;
  const mmH = layout.height * s;

  // 현재 화면에 보이는 world 영역
  const vx = (-pan.x / zoom) * s;
  const vy = (-pan.y / zoom) * s;
  const vw = (viewW / zoom) * s;
  const vh = (viewH / zoom) * s;

  return (
    <div style={{
      position: "absolute", top: 16, right: 16,
      background: UI.panel, border: `1px solid ${UI.border}`, borderRadius: RADIUS.control, padding: 4
    }}>
      <svg
        width={mmW} height={mmH}
        style={{ display: "block", cursor: "pointer" }}
        onMouseDown={(e) => {
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          onJump((e.clientX - rect.left) / s, (e.clientY - rect.top) / s);
        }}
      >
        {nodes.map((n) => {
          const box = layout.nodes.get(n.id);
          if (!box) return null;
          const off = nodeOffsets.get(n.id);
          return (
            <rect
              key={n.id}
              x={(box.x + (off?.dx ?? 0)) * s}
              y={(box.y + (off?.dy ?? 0)) * s}
              width={box.w * s}
              height={box.h * s}
              fill={KIND_COLOR[kindKey(n.entity)]}
              opacity={0.8}
              rx={1}
            />
          );
        })}
        <rect
          x={vx} y={vy} width={vw} height={vh}
          fill="rgba(59,130,246,0.15)"
          stroke={UI.accent} strokeWidth={1}
        />
      </svg>
    </div>
  );
}

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

function cardHeight(n: GraphNode, showColumns: boolean): number {
  const inhExtra = n.entity?.inheritance ? INH_BAR_H : 0;
  if (!showColumns || !n.entity || n.entity.columns.length === 0) return CARD_HEADER_H + inhExtra + 8;
  return CARD_HEADER_H + inhExtra + n.entity.columns.length * ROW_H + 8;
}

/**
 * elkjs layered 알고리즘 + ORTHOGONAL 엣지 라우팅으로 레이아웃 계산.
 *
 * - 노드 좌표는 좌상단 기준 (dagre 와 달리 변환 불필요).
 * - 엣지 경로는 `section.startPoint + bendPoints + endPoint` 로 폴리라인 구성.
 * - multi-edge 는 elkjs 가 자체 id 로 구분하므로 link 인덱스를 id 에 섞어 충돌 회피.
 */
async function computeElkLayout(data: GraphData, showColumns: boolean, rankdir: RankDir): Promise<Layout> {
  if (data.nodes.length === 0) return EMPTY_LAYOUT;

  const direction = rankdir === "LR" ? "RIGHT" : "DOWN";

  const children: ElkNode[] = data.nodes.map((n) => ({
    id: n.id,
    width: CARD_W,
    height: cardHeight(n, showColumns)
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

function EntityCard({ node, x, y, showColumns, dimmed, onHide, onDragStart, onColumnHover }: {
  node: GraphNode; x: number; y: number; showColumns: boolean;
  dimmed?: boolean;
  onHide: () => void;
  onDragStart: (clientX: number, clientY: number) => void;
  onColumnHover?: (hovering: boolean) => void;
}) {
  const isEntity = node.entity != null;
  const headerBg = KIND_COLOR[kindKey(node.entity)];
  const hasColumns = showColumns && isEntity && node.entity!.columns.length > 0;
  const h = cardHeight(node, showColumns);

  // 테이블명이 클래스명과 다른 entity 만 우측에 표 이름을 작게 부기.
  // Repository / 동명 테이블 entity 는 한 줄(이름) 만 표시.
  const tableName = node.entity?.tableName;
  const showTableBadge = isEntity && tableName != null && tableName.toLowerCase() !== node.name.toLowerCase();

  const inh = node.entity?.inheritance;
  const inhColor = inh ? (INHERITANCE_COLOR[inh.strategy] ?? UI.borderStrong) : null;
  const inhLabel = inh ? inheritanceLabel(inh.strategy) : null;
  const columnsY = CARD_HEADER_H + (inh ? INH_BAR_H : 0);

  return (
    <g
      transform={`translate(${x},${y})`}
      opacity={dimmed ? 0.18 : 1}
      onMouseDown={(e) => {
        e.stopPropagation();  // 캔버스 팬 방지
        onDragStart(e.clientX, e.clientY);
      }}
      onContextMenu={(e) => { e.preventDefault(); onHide(); }}
      style={{ cursor: "grab", transition: "opacity 0.15s ease" }}
    >
      <rect width={CARD_W} height={h} rx={RADIUS.control} fill={UI.panel} stroke={UI.border} strokeWidth={1.5} />
      <rect width={CARD_W} height={CARD_HEADER_H} rx={RADIUS.control} fill={headerBg} />
      <text x={12} y={CARD_HEADER_H / 2 + 5} fill={UI.textBright} fontSize={15} fontWeight={600}>
        <title>{node.name}{showTableBadge ? ` (${tableName})` : ""}</title>
        {fitText(
          node.name,
          CARD_W - 24 - (showTableBadge ? measureWidth(tableName!, `italic 11px ${HEADER_FONT}`) + 8 : 0),
          `600 15px ${HEADER_FONT}`,
        )}
      </text>
      {showTableBadge && (
        <text
          x={CARD_W - 12} y={CARD_HEADER_H / 2 + 4}
          fill={UI.textDim} fontSize={11} textAnchor="end" fontStyle="italic"
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
      {hasColumns && node.entity!.columns.map((c, i) => {
        const fullName = c.columnName ?? c.fieldName;
        const typeStr = shortType(c.javaType) + (c.nullable ? "" : "*");
        // 우측 블록(◆/# 마커 + 타입) 폭과 좌측 PK/FK 배지 폭을 재서 컬럼명 가용폭을 계산.
        const markStr = (c.unique ? "◆ " : "") + (c.indexed ? "# " : "");
        const rightW = measureWidth(markStr + typeStr, `11px ${FONT_MONO}`);
        const badgeW = measureWidth("PK", `700 12px ${FONT_MONO}`);
        const nameX = 12 + badgeW + 8;
        const nameAvail = (CARD_W - 12 - rightW) - nameX - 4;
        const displayName = fitText(fullName, nameAvail, `12px ${FONT_MONO}`);
        return (
          <g
            key={c.fieldName}
            transform={`translate(0,${columnsY + i * ROW_H})`}
            onMouseEnter={() => onColumnHover?.(true)}
            onMouseLeave={() => onColumnHover?.(false)}
          >
            {/* 투명 hit area — 글자 사이 빈 공간에서도 hover 가 끊기지 않게 + 풀 텍스트 툴팁 */}
            <rect x={0} y={0} width={CARD_W} height={ROW_H} fill="transparent">
              <title>{fullName} : {typeStr}</title>
            </rect>
            {/* 컬럼명 — mono 로 세로 정렬. PK/FK 는 색상 텍스트 배지(이모지 대신). */}
            <text x={12} y={16} fontSize={12} fontFamily={FONT_MONO} style={{ pointerEvents: "none" }}>
              <tspan
                fontWeight={700}
                fill={c.primaryKey ? COLUMN_MARK.pk : c.foreignKey ? COLUMN_MARK.fk : UI.textMuted}
              >
                {c.primaryKey ? "PK" : c.foreignKey ? "FK" : "  "}
              </tspan>
              <tspan dx={8} fill={UI.text}>{displayName}</tspan>
            </text>
            {/*
              우측 우편함: [unique ◆] [indexed #] type[* if not nullable]
              - ◆ : @Column(unique) 또는 @Table(uniqueConstraints) 에 포함
              - # : @Table(indexes) 의 columnList 에 포함 (PK 의 자동 index 는 제외)
            */}
            <text x={CARD_W - 12} y={16} fontSize={11} textAnchor="end" fontFamily={FONT_MONO} style={{ pointerEvents: "none" }}>
              {c.unique && <tspan fill={COLUMN_MARK.unique}>◆ </tspan>}
              {c.indexed && <tspan fill={COLUMN_MARK.indexed}># </tspan>}
              <tspan fill={UI.textMuted}>{typeStr}</tspan>
            </text>
          </g>
        );
      })}
    </g>
  );
}

function shortType(t: string): string {
  const i = t.lastIndexOf(".");
  return i < 0 ? t : t.slice(i + 1);
}

const zoomBtnStyle: React.CSSProperties = controlButton;
