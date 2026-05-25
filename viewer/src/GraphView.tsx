import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef } from "react";
import ForceGraph3D, { ForceGraphMethods } from "react-force-graph-3d";
import * as THREE from "three";
import { GraphData, GraphLink, GraphNode, Relation } from "./types";

export interface GraphHandle {
  zoomIn(): void;
  zoomOut(): void;
  fit(): void;
}

const RELATION_COLOR: Record<Relation, string> = {
  EXTENDS: "#ff7b7b",
  IMPLEMENTS: "#ffb86b",
  ONE_TO_MANY: "#10b981",
  MANY_TO_ONE: "#14b8a6",
  ONE_TO_ONE: "#06b6d4",
  MANY_TO_MANY: "#6366f1",
  USES_ENTITY: "#eab308"
};

interface Props {
  data: GraphData;
  onNodeSelect: (n: GraphNode) => void;
  onNodeReseed: (n: GraphNode) => void;
  highlightedIds?: Set<string>;
  /** 하이라이트의 기준이 된 노드 — highlightedIds 가 활성일 때 항상 포함됨 */
  highlightBaseId?: string;
  /** 표시 디테일. 1=이름만 / 2=+컬럼 / 3=+Repository. 카드 sprite 의 컬럼 영역에 영향. */
  level?: 1 | 2 | 3;
  width: number;
  height: number;
  /** 좌클릭을 PAN 으로 (기본 ROTATE). 데모 데이터로 평면 그래프 보일 때 유용. */
  grabMode?: boolean;
}

// === 3D 카드 sprite ===
//
// 노드를 평범한 sphere 가 아니라, 2D 뷰의 EntityCard 와 비슷한 형태(이름/상속 배지/컬럼)
// 로 보이는 canvas 텍스처 sprite 로 렌더한다. sprite 는 항상 카메라 빌보드라
// 어느 각도에서 봐도 가독성을 유지한다.

const CARD_DPR = 2;
const CARD_W = 260;
const CARD_HEADER_H = 32;
const CARD_INH_H = 18;
const CARD_ROW_H = 20;
const CARD_FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';

const INHERITANCE_COLOR_3D: Record<string, string> = {
  SINGLE_TABLE: "#92400e",
  JOINED: "#6d28d9",
  TABLE_PER_CLASS: "#0e7490"
};
const INHERITANCE_LABEL_3D: Record<string, string> = {
  SINGLE_TABLE: "SINGLE",
  JOINED: "JOINED",
  TABLE_PER_CLASS: "TPC"
};

function shortType3d(t: string): string {
  const i = t.lastIndexOf(".");
  return i < 0 ? t : t.slice(i + 1);
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function makeEntityCardSprite(n: GraphNode, level: 1 | 2 | 3): THREE.Sprite {
  const cols = (level >= 2 && n.entity?.columns) ? n.entity.columns : [];
  const inh = n.entity?.inheritance;
  const inhH = inh ? CARD_INH_H : 0;
  const colsAreaH = cols.length ? cols.length * CARD_ROW_H + 8 : 8;
  const cardH = CARD_HEADER_H + inhH + colsAreaH;

  const canvas = document.createElement("canvas");
  canvas.width = CARD_W * CARD_DPR;
  canvas.height = cardH * CARD_DPR;
  const ctx = canvas.getContext("2d")!;
  ctx.scale(CARD_DPR, CARD_DPR);

  // 본체
  ctx.fillStyle = "#1e293b";
  roundRect(ctx, 0, 0, CARD_W, cardH, 6);
  ctx.fill();
  ctx.strokeStyle = "#334155";
  ctx.lineWidth = 1;
  ctx.stroke();

  // 헤더 색 (kind 별)
  const isEntity = n.entity != null;
  const isMappedSuper = n.entity?.kind === "mappedSuperclass";
  const isEmbeddable = n.entity?.kind === "embeddable";
  const headerBg = isMappedSuper ? "#3730a3" : isEmbeddable ? "#0f766e" : isEntity ? "#1d4ed8" : "#1f8556";

  ctx.save();
  ctx.beginPath();
  ctx.moveTo(0, CARD_HEADER_H);
  ctx.lineTo(0, 6);
  ctx.arcTo(0, 0, 6, 0, 6);
  ctx.lineTo(CARD_W - 6, 0);
  ctx.arcTo(CARD_W, 0, CARD_W, 6, 6);
  ctx.lineTo(CARD_W, CARD_HEADER_H);
  ctx.closePath();
  ctx.fillStyle = headerBg;
  ctx.fill();
  ctx.restore();

  ctx.fillStyle = "#f1f5f9";
  ctx.font = `600 15px ${CARD_FONT}`;
  ctx.textBaseline = "middle";
  ctx.textAlign = "left";
  ctx.fillText(n.name, 12, CARD_HEADER_H / 2);

  const tableName = n.entity?.tableName;
  if (isEntity && tableName && tableName.toLowerCase() !== n.name.toLowerCase()) {
    ctx.fillStyle = "#cbd5e1";
    ctx.font = `italic 11px ${CARD_FONT}`;
    ctx.textAlign = "right";
    ctx.fillText(tableName, CARD_W - 12, CARD_HEADER_H / 2);
  } else if (!isEntity) {
    // Repository: 우측에 작게 "Repository" 부기
    ctx.fillStyle = "rgba(255,255,255,0.7)";
    ctx.font = `italic 10px ${CARD_FONT}`;
    ctx.textAlign = "right";
    ctx.fillText("Repository", CARD_W - 12, CARD_HEADER_H / 2);
  }

  // 상속 배지
  if (inh) {
    const inhColor = INHERITANCE_COLOR_3D[inh.strategy] ?? "#475569";
    const inhLabel = INHERITANCE_LABEL_3D[inh.strategy] ?? inh.strategy;
    ctx.fillStyle = inhColor;
    ctx.globalAlpha = 0.85;
    ctx.fillRect(0, CARD_HEADER_H, CARD_W, CARD_INH_H);
    ctx.globalAlpha = 1;
    ctx.fillStyle = "#ffffff";
    ctx.font = `600 10px ${CARD_FONT}`;
    ctx.textAlign = "left";
    const inhText = inh.discriminatorColumn ? `${inhLabel}  ·  ${inh.discriminatorColumn}` : inhLabel;
    ctx.fillText(inhText, 8, CARD_HEADER_H + CARD_INH_H / 2);
    if (inh.discriminatorValue) {
      ctx.font = `italic 10px ${CARD_FONT}`;
      ctx.textAlign = "right";
      ctx.fillText(`= "${inh.discriminatorValue}"`, CARD_W - 8, CARD_HEADER_H + CARD_INH_H / 2);
    }
  }

  // 컬럼
  const colsStartY = CARD_HEADER_H + inhH;
  cols.forEach((c, i) => {
    const y = colsStartY + i * CARD_ROW_H + CARD_ROW_H / 2;
    ctx.fillStyle = c.primaryKey ? "#fbbf24" : "#e2e8f0";
    ctx.font = `12px ${CARD_FONT}`;
    ctx.textAlign = "left";
    const namePrefix = c.primaryKey ? "🔑 " : "";
    ctx.fillText(namePrefix + (c.columnName ?? c.fieldName), 12, y);

    // 우측: [unique ◆][indexed #] type[* if not nullable]. 오른쪽부터 거꾸로 그려 측정한 너비만큼 좌측으로 이동.
    ctx.font = `11px ${CARD_FONT}`;
    ctx.textAlign = "right";
    let rx = CARD_W - 12;
    const typeStr = shortType3d(c.javaType) + (c.nullable ? "" : "*");
    ctx.fillStyle = "#94a3b8";
    ctx.fillText(typeStr, rx, y);
    rx -= ctx.measureText(typeStr).width;
    if (c.indexed) {
      const s = "# ";
      ctx.fillStyle = "#67e8f9";
      ctx.fillText(s, rx, y);
      rx -= ctx.measureText(s).width;
    }
    if (c.unique) {
      const s = "◆ ";
      ctx.fillStyle = "#fde047";
      ctx.fillText(s, rx, y);
    }
  });

  const texture = new THREE.CanvasTexture(canvas);
  texture.minFilter = THREE.LinearFilter;
  const material = new THREE.SpriteMaterial({
    map: texture,
    transparent: true,
    depthWrite: false
  });
  const sprite = new THREE.Sprite(material);
  // 월드 스케일 — 카드가 ~50 unit 정도 폭이 되도록
  const worldScale = 0.22;
  sprite.scale.set(CARD_W * worldScale, cardH * worldScale, 1);
  return sprite;
}

const DIM_COLOR = "#1f2937";

const GraphView = forwardRef<GraphHandle, Props>(function GraphView(
  { data, onNodeSelect, onNodeReseed, highlightedIds, highlightBaseId, level = 1, width, height, grabMode },
  ref
) {
  const fgRef = useRef<ForceGraphMethods | undefined>(undefined);

  // 카메라를 타깃 기준으로 factor 만큼 멀거나 가깝게 이동
  function zoomBy(factor: number) {
    const fg = fgRef.current as any;
    if (!fg) return;
    const camera = fg.camera();
    const controls = fg.controls();
    const target: THREE.Vector3 = controls?.target ?? new THREE.Vector3(0, 0, 0);
    const dir = camera.position.clone().sub(target).multiplyScalar(factor);
    const next = target.clone().add(dir);
    fg.cameraPosition({ x: next.x, y: next.y, z: next.z }, target, 200);
  }

  useImperativeHandle(ref, () => ({
    zoomIn: () => zoomBy(0.8),
    zoomOut: () => zoomBy(1.25),
    fit: () => fgRef.current?.zoomToFit(400, 80)
  }), []);

  // ForceGraph 는 source/target 을 객체 참조로 바꾸기 때문에 매 렌더 새 객체를 넘긴다.
  const graphData = useMemo(() => ({
    nodes: data.nodes.map(n => ({ ...n })),
    links: data.links.map(l => ({ ...l }))
  }), [data]);

  useEffect(() => {
    const t = setTimeout(() => fgRef.current?.zoomToFit(600, 80), 200);
    return () => clearTimeout(t);
  }, [data.seed, data.depth]);

  // 마우스 버튼 매핑:
  //  - 휠 버튼 드래그: 기본 DOLLY(줌) → PAN(이동)
  //  - 좌클릭: grabMode 가 켜져있으면 PAN(이동), 아니면 ROTATE(회전)
  useEffect(() => {
    const t = setTimeout(() => {
      const controls = fgRef.current?.controls?.() as any;
      if (!controls) return;
      controls.mouseButtons = {
        LEFT: grabMode ? THREE.MOUSE.PAN : THREE.MOUSE.ROTATE,
        MIDDLE: THREE.MOUSE.PAN,
        RIGHT: THREE.MOUSE.PAN
      };
      controls.zoomSpeed = 1.0;
    }, 0);
    return () => clearTimeout(t);
  }, [grabMode]);

  const hlActive = !!highlightedIds && highlightedIds.size > 0;
  // 하이라이트 활성 시 기준 노드는 항상 포함되도록 판정
  function isHighlighted(id: string): boolean {
    return !!highlightedIds && (highlightedIds.has(id) || id === highlightBaseId);
  }
  // 하이라이트 변경 시 ForceGraph 가 색상/크기 캐시를 새로 계산하도록 트리거
  useEffect(() => {
    fgRef.current?.refresh?.();
  }, [highlightedIds, highlightBaseId]);

  function linkEndpointId(end: string | { id?: string }): string {
    return typeof end === "string" ? end : (end?.id ?? "");
  }

  // level 변경 시 ForceGraph 가 nodeThreeObject 를 다시 호출하도록 리프레시
  useEffect(() => {
    fgRef.current?.refresh?.();
  }, [level]);

  return (
    <ForceGraph3D
      ref={fgRef}
      width={width}
      height={height}
      graphData={graphData}
      backgroundColor="#0b1020"
      nodeLabel={(n: any) => `<div style="padding:4px 8px;background:#111827;border-radius:4px">
        <b>${(n as GraphNode).name}</b><br/>
        <span style="color:#9aa5b1">${(n as GraphNode).pkg}</span><br/>
        <span style="color:#9aa5b1">${(n as GraphNode).kind}${
          (n as GraphNode).stereotypes?.length ? " · @" + (n as GraphNode).stereotypes.join(", @") : ""
        }</span>
      </div>`}
      linkColor={(l: any) => {
        const link = l as GraphLink;
        const base = RELATION_COLOR[link.relation] ?? "#666";
        if (!hlActive) return base;
        const s = linkEndpointId((l as any).source);
        const t = linkEndpointId((l as any).target);
        return (isHighlighted(s) || isHighlighted(t)) ? base : DIM_COLOR;
      }}
      linkOpacity={hlActive ? 0.25 : 0.6}
      linkDirectionalArrowLength={3}
      linkDirectionalArrowRelPos={1}

      onNodeClick={(n: any) => onNodeSelect(n as GraphNode)}
      onNodeRightClick={(n: any) => onNodeReseed(n as GraphNode)}
      // 좌클릭: 소스로 점프 / 우클릭: 그 노드를 새 seed 로
      // 노드 수동 드래그 비활성 — 카드 sprite 가 화면을 많이 덮어 드래그가
      // 회전 대신 노드 끌기로 잡혀버리는 문제 회피. 배치는 force 시뮬레이션에 일임.
      enableNodeDrag={false}

      // 노드를 카드 sprite 로 치환 (sphere 비활성). 검색 하이라이트 시 비매칭은 sprite 투명도를 낮춤.
      nodeThreeObjectExtend={false}
      nodeThreeObject={((n: any) => {
        const node = n as GraphNode;
        const sprite = makeEntityCardSprite(node, level);
        if (hlActive && !isHighlighted(node.id)) {
          sprite.material.opacity = 0.18;
        }
        return sprite;
      }) as any}/>
  );
});

export default GraphView;
