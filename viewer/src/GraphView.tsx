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

  // 본체 — rgba 로 반투명 채움. SpriteMaterial transparent=true 옵션은 텍스처의 알파를
  // 살리겠다는 의미일 뿐이라, 보이는 투명도는 여기 fill alpha 가 결정.
  // 텍스트는 그대로 불투명으로 그릴 것이라 가독성은 유지된다.
  ctx.fillStyle = "rgba(30, 41, 59, 0.82)";
  roundRect(ctx, 0, 0, CARD_W, cardH, 6);
  ctx.fill();
  ctx.strokeStyle = "rgba(51, 65, 85, 0.9)";
  ctx.lineWidth = 1;
  ctx.stroke();

  // 헤더 색 (kind 별). 본체보다 살짝만 진하게 (alpha 0.88) — 위계 유지하면서 비침은 살림.
  const isEntity = n.entity != null;
  const isMappedSuper = n.entity?.kind === "mappedSuperclass";
  const isEmbeddable = n.entity?.kind === "embeddable";
  const headerBg = isMappedSuper ? "rgba(55, 48, 163, 0.88)"
    : isEmbeddable ? "rgba(15, 118, 110, 0.88)"
    : isEntity ? "rgba(29, 78, 216, 0.88)"
    : "rgba(31, 133, 86, 0.88)";

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
    ctx.fillStyle = c.primaryKey ? "#fbbf24" : c.foreignKey ? "#7dd3fc" : "#e2e8f0";
    ctx.font = `12px ${CARD_FONT}`;
    ctx.textAlign = "left";
    const namePrefix = c.primaryKey ? "🔑 " : c.foreignKey ? "🔗 " : "";
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

// === 3D anchor + 카드 라벨 묶음 ===
//
// 노드 원점에 작은 3D sphere 를 둬서 회전·원근에 진짜 반응하는 깊이 단서를 제공한다.
// 카드 sprite 는 sphere 위로 띄워 라벨처럼 동작 (여전히 빌보드, 항상 가독성 유지).
// 엣지는 node origin = sphere 위치로 모임.

const ANCHOR_RADIUS = 3;
const ANCHOR_TO_CARD_GAP = 5;

const ANCHOR_COLOR: Record<string, number> = {
  entity: 0x3b82f6,           // blue
  mappedSuperclass: 0x6366f1, // indigo
  embeddable: 0x14b8a6,       // teal
  repository: 0x22c55e        // green (entity == null)
};

function anchorColorFor(n: GraphNode): number {
  if (n.entity == null) return ANCHOR_COLOR.repository;
  return ANCHOR_COLOR[n.entity.kind] ?? ANCHOR_COLOR.entity;
}

function makeEntityCardObject(n: GraphNode, level: 1 | 2 | 3): THREE.Group {
  const group = new THREE.Group();

  // 3D anchor — Phong 으로 두면 ForceGraph3D 기본 조명 아래 음영이 생겨 회전 시 깊이감.
  const sphereGeo = new THREE.SphereGeometry(ANCHOR_RADIUS, 18, 18);
  const sphereMat = new THREE.MeshPhongMaterial({
    color: anchorColorFor(n),
    shininess: 40,
    transparent: true
  });
  const sphere = new THREE.Mesh(sphereGeo, sphereMat);
  group.add(sphere);

  // 카드 라벨 — sphere 상단 위로 띄움. sprite.position 은 sprite 중심 기준.
  const sprite = makeEntityCardSprite(n, level);
  const cardWorldH = sprite.scale.y;
  sprite.position.y = ANCHOR_RADIUS + ANCHOR_TO_CARD_GAP + cardWorldH / 2;
  group.add(sprite);

  return group;
}

/** Group 의 모든 머티리얼에 opacity 를 적용 (sphere + sprite). */
function setGroupOpacity(group: THREE.Group, opacity: number) {
  group.traverse((obj) => {
    const mat = (obj as any).material as THREE.Material | undefined;
    if (mat && "opacity" in mat) {
      (mat as any).transparent = true;
      (mat as any).opacity = opacity;
    }
  });
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

      // 노드를 (3D anchor sphere + 카드 sprite 라벨) Group 으로 치환.
      // sphere 는 회전/원근에 반응하는 진짜 3D 객체라 깊이 단서 제공,
      // 카드는 그 위에 떠 있는 빌보드 라벨로 가독성 유지.
      nodeThreeObjectExtend={false}
      nodeThreeObject={((n: any) => {
        const node = n as GraphNode;
        const obj = makeEntityCardObject(node, level);
        if (hlActive && !isHighlighted(node.id)) {
          setGroupOpacity(obj, 0.18);
        }
        return obj;
      }) as any}/>
  );
});

export default GraphView;
