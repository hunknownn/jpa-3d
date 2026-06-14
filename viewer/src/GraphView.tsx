import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useReducer, useRef } from "react";
import ForceGraph3D, { ForceGraphMethods } from "react-force-graph-3d";
import * as THREE from "three";
import { GraphData, GraphLink, GraphNode } from "./types";
import {
  UI, CARD3D, RELATION_COLOR, BOUNDARY_STYLE,
  INHERITANCE_COLOR, KIND_COLOR, COLUMN_MARK, hexToRgba, kindKey,
  FONT_MONO, controlButton, RADIUS
} from "./theme";
import { IconFit } from "./Icons";
import { t, relationLabel, inheritanceLabel } from "./i18n";
import { fitText, escapeHtml } from "./textFit";

export interface GraphHandle {
  zoomIn(): void;
  zoomOut(): void;
  fit(): void;
  /**
   * 현재 WebGL canvas 를 PNG dataURL 로 캡처.
   * preserveDrawingBuffer 가 꺼진 기본 설정에선 toDataURL 직전에 한 번 render 를 다시 호출해야
   * back buffer 가 비어있지 않다 — 같은 JS turn 안에서 sync 호출하면 정상 캡처된다.
   */
  snapshotPng(): string;
}

interface Props {
  data: GraphData;
  /** 단일 클릭 — 소스 파일로 이동(2D 와 동일). @param split Cmd/Ctrl 을 누른 채 클릭 → 에디터 분할로 열기. */
  onNodeSelect: (n: GraphNode, split?: boolean) => void;
  /** 더블 클릭 — 그 노드를 중심(seed)으로 다시 탐색. */
  onNodeReseed: (n: GraphNode) => void;
  /** 우클릭 — 그 노드를 분석에서 제거(연관 고립 노드는 연쇄 제거, 툴바로 복원). */
  onNodeRemove: (n: GraphNode) => void;
  highlightedIds?: Set<string>;
  /** 하이라이트의 기준이 된 노드 — highlightedIds 가 활성일 때 항상 포함됨 */
  highlightBaseId?: string;
  /** 컬럼 표시 여부 — 가까이서 볼 때 펼쳐지는 상세 카드의 컬럼 영역에 영향. */
  showColumns?: boolean;
  width: number;
  height: number;
  /** 좌클릭을 PAN 으로 (기본 ROTATE). 데모 데이터로 평면 그래프 보일 때 유용. */
  grabMode?: boolean;
}

// === 레이아웃 상수 ===
//
// 깊이(층)에 의미를 부여한다: 루트(중심/최다연결)에서의 BFS 거리를 수직 층으로 매핑.
// Y 만 핀 고정하고 x/z 는 force 시뮬레이션에 맡겨 평면 내에서 자연스럽게 퍼지게 한다.
// 회전(orbit)하면 "중심에서 몇 홉" 이 수직 위치로 즉시 읽힌다.
const LAYER_GAP = 92;     // 층 간 수직 간격(world)
const BOX_SIZE = 11;      // 노드 3D 베이스 박스 한 변
const CARD_GAP = 6;       // 박스 상단 ↔ 카드 하단 여백

// 거리 LOD — 카메라가 LOD_NEAR 보다 가까운 노드만 상세 카드(컬럼)를 펼치고,
// 그보다 멀면 이름만 있는 compact 라벨을 보여준다. (per-frame onBeforeRender 로 판정)
const LOD_NEAR = 150;

// === 카드(상세) 렌더 상수 ===
const CARD_DPR = 2;
const CARD_W = 260;
const CARD_HEADER_H = 32;
const CARD_INH_H = 18;
const CARD_ROW_H = 20;
const CARD_FONT = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
const CARD_MONO = FONT_MONO;
const COL_INDENT = 26;
const DETAIL_WORLD_SCALE = 0.2;
const COMPACT_WORLD_SCALE = 0.16;

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

// === compact 라벨 sprite (멀리서 항상 보이는 이름 칩) ===
// 좌측에 kind 색 막대 + 이름. 작고 가벼워 멀리서도 어지럽지 않다.
function makeCompactLabelSprite(n: GraphNode): THREE.Sprite {
  const name = n.name;
  const kindColor = KIND_COLOR[kindKey(n.entity)];
  const fontSize = 26;
  const padX = 13;
  const barW = 7;

  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d")!;
  ctx.font = `600 ${fontSize}px ${CARD_FONT}`;
  const textW = Math.ceil(ctx.measureText(name).width);
  const w = barW + padX + textW + padX;
  const h = fontSize + 16;

  canvas.width = w * CARD_DPR;
  canvas.height = h * CARD_DPR;
  ctx.scale(CARD_DPR, CARD_DPR);

  ctx.fillStyle = "rgba(30, 41, 59, 0.92)";
  roundRect(ctx, 0, 0, w, h, 8);
  ctx.fill();
  ctx.strokeStyle = "rgba(71, 85, 105, 0.9)";
  ctx.lineWidth = 1.5;
  ctx.stroke();

  // 좌측 kind 색 막대 (둥근 좌변 안에 클리핑)
  ctx.save();
  roundRect(ctx, 0, 0, w, h, 8);
  ctx.clip();
  ctx.fillStyle = kindColor;
  ctx.fillRect(0, 0, barW, h);
  ctx.restore();

  ctx.fillStyle = "#f1f5f9";
  ctx.font = `600 ${fontSize}px ${CARD_FONT}`;
  ctx.textBaseline = "middle";
  ctx.textAlign = "left";
  ctx.fillText(name, barW + padX, h / 2 + 1);

  const texture = new THREE.CanvasTexture(canvas);
  texture.minFilter = THREE.LinearFilter;
  const mat = new THREE.SpriteMaterial({ map: texture, transparent: true, depthWrite: false });
  const sprite = new THREE.Sprite(mat);
  sprite.scale.set(w * COMPACT_WORLD_SCALE, h * COMPACT_WORLD_SCALE, 1);
  return sprite;
}

/**
 * 노드 hover 툴팁 HTML. 카드 안의 텍스트는 폭에 맞춰 "…" 로 잘리므로, 여기서 전체
 * 컬럼명/타입을 풀어서 보여준다(잘린 값 보완). 사용자 코드 유래 문자열은 이스케이프.
 */
function nodeTooltipHtml(n: GraphNode): string {
  const stereo = n.stereotypes?.length ? " · @" + n.stereotypes.map(escapeHtml).join(", @") : "";
  const cols = n.entity?.columns ?? [];
  const rows = cols.map((c) => {
    const name = escapeHtml(c.columnName ?? c.fieldName);
    const type = escapeHtml(shortType3d(c.javaType) + (c.nullable ? "" : "*"));
    const badge = c.primaryKey ? "PK " : c.foreignKey ? "FK " : "";
    const badgeHtml = badge ? `<span style="color:#fbbf24">${badge}</span>` : "";
    return `<div style="display:flex;justify-content:space-between;gap:16px">`
      + `<span>${badgeHtml}${name}</span>`
      + `<span style="color:#9aa5b1">${type}</span></div>`;
  }).join("");
  const colsHtml = cols.length
    ? `<div style="margin-top:6px;padding-top:6px;border-top:1px solid #334155;`
      + `font-family:ui-monospace,monospace;font-size:11px">${rows}</div>`
    : "";
  return `<div style="padding:6px 10px;background:#111827;border-radius:6px;max-width:380px">`
    + `<b>${escapeHtml(n.name)}</b><br/>`
    + `<span style="color:#9aa5b1">${escapeHtml(n.pkg)}</span><br/>`
    + `<span style="color:#9aa5b1">${escapeHtml(n.kind)}${stereo}</span>`
    + `${colsHtml}</div>`;
}

// === 상세 카드 sprite (가까이서 펼쳐지는 풀 카드: 이름/상속/컬럼) ===
function makeEntityCardSprite(n: GraphNode): THREE.Sprite {
  const cols = n.entity?.columns ?? [];
  const inh = n.entity?.inheritance;
  const inhH = inh ? CARD_INH_H : 0;
  const colsAreaH = cols.length ? cols.length * CARD_ROW_H + 8 : 8;
  const cardH = CARD_HEADER_H + inhH + colsAreaH;

  const canvas = document.createElement("canvas");
  canvas.width = CARD_W * CARD_DPR;
  canvas.height = cardH * CARD_DPR;
  const ctx = canvas.getContext("2d")!;
  ctx.scale(CARD_DPR, CARD_DPR);

  ctx.fillStyle = "rgba(30, 41, 59, 0.9)";
  roundRect(ctx, 0, 0, CARD_W, cardH, 6);
  ctx.fill();
  ctx.strokeStyle = "rgba(71, 85, 105, 0.95)";
  ctx.lineWidth = 1;
  ctx.stroke();

  const isEntity = n.entity != null;
  const headerBg = hexToRgba(KIND_COLOR[kindKey(n.entity)], 0.92);

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

  // 우측 부기(테이블명 또는 "Repository") 폭을 먼저 재 두고, 그만큼 빼서 이름 가용폭을 잡는다.
  const tableName = n.entity?.tableName;
  const showTable = isEntity && tableName != null && tableName.toLowerCase() !== n.name.toLowerCase();
  let headerRightW = 0;
  if (showTable) {
    ctx.font = `italic 11px ${CARD_FONT}`;
    headerRightW = ctx.measureText(tableName!).width;
  } else if (!isEntity) {
    ctx.font = `italic 10px ${CARD_FONT}`;
    headerRightW = ctx.measureText("Repository").width;
  }

  const headerFont = `600 15px ${CARD_FONT}`;
  const nameAvail = CARD_W - 12 - 12 - (headerRightW ? headerRightW + 8 : 0);
  ctx.fillStyle = "#f1f5f9";
  ctx.font = headerFont;
  ctx.textBaseline = "middle";
  ctx.textAlign = "left";
  ctx.fillText(fitText(n.name, nameAvail, headerFont), 12, CARD_HEADER_H / 2);

  if (showTable) {
    ctx.fillStyle = "#cbd5e1";
    ctx.font = `italic 11px ${CARD_FONT}`;
    ctx.textAlign = "right";
    ctx.fillText(tableName!, CARD_W - 12, CARD_HEADER_H / 2);
  } else if (!isEntity) {
    ctx.fillStyle = "rgba(255,255,255,0.7)";
    ctx.font = `italic 10px ${CARD_FONT}`;
    ctx.textAlign = "right";
    ctx.fillText("Repository", CARD_W - 12, CARD_HEADER_H / 2);
  }

  if (inh) {
    const inhColor = INHERITANCE_COLOR[inh.strategy] ?? CARD3D.inhFallback;
    const inhLabel = inheritanceLabel(inh.strategy);
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

  const colsStartY = CARD_HEADER_H + inhH;
  cols.forEach((c, i) => {
    const y = colsStartY + i * CARD_ROW_H + CARD_ROW_H / 2;
    ctx.textAlign = "left";
    const badge = c.primaryKey ? "PK" : c.foreignKey ? "FK" : "";
    if (badge) {
      ctx.font = `700 11px ${CARD_MONO}`;
      ctx.fillStyle = c.primaryKey ? COLUMN_MARK.pk : COLUMN_MARK.fk;
      ctx.fillText(badge, 12, y);
    }
    // 우측 블록(◆/# 마커 + 타입) 폭을 먼저 재서 컬럼명 가용폭을 계산 → 충돌/오버플로 방지.
    const typeStr = shortType3d(c.javaType) + (c.nullable ? "" : "*");
    const markStr = (c.unique ? "◆ " : "") + (c.indexed ? "# " : "");
    const colNameFont = `12px ${CARD_MONO}`;
    ctx.font = `11px ${CARD_MONO}`;
    const rightW = ctx.measureText(markStr + typeStr).width;

    const nameX = 12 + COL_INDENT;
    const nameAvail = CARD_W - 12 - nameX - rightW - 6;
    ctx.font = colNameFont;
    ctx.fillStyle = CARD3D.text;
    ctx.textAlign = "left";
    ctx.fillText(fitText(c.columnName ?? c.fieldName, nameAvail, colNameFont), nameX, y);

    ctx.font = `11px ${CARD_MONO}`;
    ctx.textAlign = "right";
    let rx = CARD_W - 12;
    ctx.fillStyle = CARD3D.textMuted;
    ctx.fillText(typeStr, rx, y);
    rx -= ctx.measureText(typeStr).width;
    if (c.indexed) {
      const s = "# ";
      ctx.fillStyle = COLUMN_MARK.indexed;
      ctx.fillText(s, rx, y);
      rx -= ctx.measureText(s).width;
    }
    if (c.unique) {
      const s = "◆ ";
      ctx.fillStyle = COLUMN_MARK.unique;
      ctx.fillText(s, rx, y);
    }
  });

  const texture = new THREE.CanvasTexture(canvas);
  texture.minFilter = THREE.LinearFilter;
  const material = new THREE.SpriteMaterial({ map: texture, transparent: true, depthWrite: false });
  const sprite = new THREE.Sprite(material);
  sprite.scale.set(CARD_W * DETAIL_WORLD_SCALE, cardH * DETAIL_WORLD_SCALE, 1);
  return sprite;
}

// 엣지에 띄우는 관계 라벨 sprite (예: "1:N"). 포커스/강조된 엣지에만 생성한다.
function makeLinkLabelSprite(text: string, color: string): THREE.Sprite {
  const fontSize = 32;
  const pad = 6;
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d")!;
  ctx.font = `bold ${fontSize}px ${CARD_FONT}`;
  const w = Math.ceil(ctx.measureText(text).width) + pad * 2;
  const h = fontSize + pad * 2;
  canvas.width = w;
  canvas.height = h;
  ctx.font = `bold ${fontSize}px ${CARD_FONT}`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.strokeStyle = "rgba(11,16,32,0.92)";
  ctx.lineWidth = 6;
  ctx.strokeText(text, w / 2, h / 2);
  ctx.fillStyle = color;
  ctx.fillText(text, w / 2, h / 2);

  const texture = new THREE.CanvasTexture(canvas);
  texture.minFilter = THREE.LinearFilter;
  const mat = new THREE.SpriteMaterial({ map: texture, transparent: true, depthWrite: false, depthTest: false });
  const sprite = new THREE.Sprite(mat);
  const scale = 0.18;
  sprite.scale.set(w * scale, h * scale, 1);
  return sprite;
}

// 노드 = 3D 베이스 박스(깊이 단서) + compact/detail 카드(빌보드, 거리 LOD).
const _tmpV = new THREE.Vector3();
function makeEntityCardObject(n: GraphNode, showColumns: boolean): THREE.Group {
  const group = new THREE.Group();

  // 3D 베이스 박스 — 조명/회전에 반응해 깊이감을 준다. 엣지는 이 박스 중심으로 모인다.
  const boxGeo = new THREE.BoxGeometry(BOX_SIZE, BOX_SIZE, BOX_SIZE);
  const boxMat = new THREE.MeshPhongMaterial({
    color: KIND_COLOR[kindKey(n.entity)],
    shininess: 30,
    transparent: true
  });
  const box = new THREE.Mesh(boxGeo, boxMat);
  group.add(box);

  const baseY = BOX_SIZE / 2 + CARD_GAP;

  // compact 칩 — 항상 후보. 카드가 펼쳐지면 가려지므로 숨긴다.
  const compact = makeCompactLabelSprite(n);
  compact.position.y = baseY + compact.scale.y / 2;
  group.add(compact);

  // detail 카드 — showColumns 켜졌을 때만 만들고, 가까울 때만 표시.
  let detail: THREE.Sprite | null = null;
  if (showColumns) {
    detail = makeEntityCardSprite(n);
    detail.position.y = baseY + detail.scale.y / 2;
    detail.visible = false;
    group.add(detail);
  }

  // 거리 LOD — 박스는 항상 렌더되므로 그 onBeforeRender 에서 카메라 거리를 재
  // compact ↔ detail 가시성을 매 프레임 토글한다.
  box.onBeforeRender = (_r, _s, camera) => {
    box.getWorldPosition(_tmpV);
    const near = (camera as THREE.Camera).position.distanceTo(_tmpV) < LOD_NEAR;
    if (detail) {
      detail.visible = near;
      compact.visible = !near;
    } else {
      compact.visible = true;
    }
  };

  return group;
}

/** Group 의 모든 머티리얼에 opacity 를 적용 (box + sprites). */
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

function endpointId(end: string | { id?: string }): string {
  return typeof end === "string" ? end : (end?.id ?? "");
}

/** 무방향 BFS 로 루트에서의 거리(층)를 계산. 도달 불가 노드는 결과에 없음(외곽 층 처리). */
function computeLayers(nodes: GraphNode[], links: GraphLink[], rootId: string | undefined): Map<string, number> {
  const layer = new Map<string, number>();
  if (!rootId) return layer;
  const adj = new Map<string, string[]>();
  for (const n of nodes) adj.set(n.id, []);
  for (const l of links) {
    const s = endpointId(l.source as any);
    const t = endpointId(l.target as any);
    adj.get(s)?.push(t);
    adj.get(t)?.push(s);
  }
  const queue = [rootId];
  layer.set(rootId, 0);
  while (queue.length) {
    const cur = queue.shift()!;
    const cl = layer.get(cur)!;
    for (const nb of adj.get(cur) ?? []) {
      if (!layer.has(nb)) {
        layer.set(nb, cl + 1);
        queue.push(nb);
      }
    }
  }
  return layer;
}

interface ModuleCenter { x: number; z: number; }

/**
 * 모듈을 XZ 평면의 링 위 중심점으로 배치. Y 는 이미 BFS depth(층)에 쓰이므로 모듈은 XZ 로 분리한다.
 * 모듈이 1개 이하(일반 모놀리스)면 빈 맵 → 클러스터링 없음(분리 불필요).
 */
function computeModuleCenters(modules: string[] | undefined): Map<string, ModuleCenter> {
  const m = new Map<string, ModuleCenter>();
  const mods = modules ?? [];
  if (mods.length <= 1) return m;
  // 모듈 구역(district)끼리 벌어지되, 기본 카메라 fit 한 화면에 들어오도록 적정 반경.
  const radius = Math.max(220, mods.length * 90);
  mods.forEach((mod, i) => {
    const a = (i / mods.length) * Math.PI * 2;
    m.set(mod, { x: Math.cos(a) * radius, z: Math.sin(a) * radius });
  });
  return m;
}

/**
 * 각 노드를 자기 모듈 중심으로 끌어당기는 d3 커스텀 force (XZ 평면). 같은 모듈끼리 뭉쳐
 * 공간적으로 구역(district)이 분리된다. charge(반발)/link(연결) 와 균형을 이루도록 약하게.
 */
function moduleClusterForce(centers: Map<string, ModuleCenter>, strength: number) {
  let ns: any[] = [];
  const force = (alpha: number) => {
    if (centers.size === 0) return;
    const k = strength * alpha;
    for (const n of ns) {
      const c = centers.get(n.module ?? "");
      if (!c) continue;
      n.vx = (n.vx ?? 0) + (c.x - n.x) * k;
      n.vz = (n.vz ?? 0) + (c.z - n.z) * k;
    }
  };
  (force as any).initialize = (nodes: any[]) => { ns = nodes; };
  return force;
}

const GraphView = forwardRef<GraphHandle, Props>(function GraphView(
  { data, onNodeSelect, onNodeReseed, onNodeRemove, highlightedIds, highlightBaseId, showColumns = false, width, height, grabMode },
  ref
) {
  const fgRef = useRef<ForceGraphMethods | undefined>(undefined);
  // 단일/더블 클릭 구분 타이머
  const clickTimer = useRef<number | null>(null);
  // "다음 엔진 정지 때 카메라를 한 번 맞춰야 함" 플래그. 데이터(구조)가 바뀔 때만 켜고,
  // 그 외(포커스/리프레시로 인한 재시뮬레이션)에선 사용자의 줌을 건드리지 않는다.
  const fitPendingRef = useRef(true);
  // 카메라 조정 방식: 최초 로드는 전체 fit, 이후 구조 변경(reseed 등)은 줌 유지한 채 새 중심으로 recenter.
  const fitModeRef = useRef<"fit" | "recenter">("fit");
  const loadedRef = useRef(false);

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
    fit: () => fgRef.current?.zoomToFit(400, 80),
    snapshotPng: () => {
      const fg = fgRef.current as any;
      if (!fg) return "";
      const renderer = fg.renderer?.();
      const scene = fg.scene?.();
      const camera = fg.camera?.();
      if (!renderer || !scene || !camera) return "";
      renderer.render(scene, camera);
      return (renderer.domElement as HTMLCanvasElement).toDataURL("image/png");
    }
  }), []);

  // 루트 노드 — 중심(seed) 모드면 seed, 아니면 최다 연결(degree) 노드.
  const rootId = useMemo(() => {
    if (!data.nodes.length) return undefined;
    if (data.seed && data.nodes.some((n) => n.id === data.seed)) return data.seed;
    const deg = new Map<string, number>();
    for (const l of data.links) {
      deg.set(l.source, (deg.get(l.source) ?? 0) + 1);
      deg.set(l.target, (deg.get(l.target) ?? 0) + 1);
    }
    let best = data.nodes[0].id;
    let bestDeg = -1;
    for (const n of data.nodes) {
      const d = deg.get(n.id) ?? 0;
      if (d > bestDeg) { bestDeg = d; best = n.id; }
    }
    return best;
  }, [data]);

  // 모듈 → XZ 중심점. 모듈 클러스터 force 와 노드 초기 위치 시드에 공유.
  const moduleCenters = useMemo(() => computeModuleCenters(data.modules), [data.modules]);

  // 층(fy) 핀 고정 + x/z 는 force 에 맡김. ForceGraph 는 매번 새 객체를 받아야 한다.
  // 초기 x/z 는 모듈 중심 근처로 시드해 모듈 분리가 빠르게 수렴하게 한다.
  const graphData = useMemo(() => {
    const layers = computeLayers(data.nodes, data.links, rootId);
    let maxL = 0;
    for (const v of layers.values()) maxL = Math.max(maxL, v);
    const nodes = data.nodes.map((n) => {
      const L = layers.get(n.id) ?? (maxL + 1);
      const c = moduleCenters.get(n.module ?? "");
      const baseX = c?.x ?? 0;
      const baseZ = c?.z ?? 0;
      return {
        ...n,
        fy: -L * LAYER_GAP,
        x: baseX + (Math.random() - 0.5) * (c ? 120 : 220),
        z: baseZ + (Math.random() - 0.5) * (c ? 120 : 220)
      };
    });
    const links = data.links.map((l) => ({ ...l }));
    return { nodes, links };
  }, [data, rootId, moduleCenters]);

  // 양방향/다중 엣지는 살짝 휘어 서로 겹치지 않게 — 쌍별 링크 수 집계.
  const pairCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const l of data.links) {
      const key = l.source < l.target ? `${l.source}|${l.target}` : `${l.target}|${l.source}`;
      m.set(key, (m.get(key) ?? 0) + 1);
    }
    return m;
  }, [data]);

  function isMultiPair(l: GraphLink): boolean {
    const s = endpointId(l.source as any);
    const t = endpointId(l.target as any);
    const key = s < t ? `${s}|${t}` : `${t}|${s}`;
    return (pairCount.get(key) ?? 0) > 1;
  }

  // 검색 하이라이트(외부) — 매칭 노드/엣지만 강조하고 나머지는 흐리게.
  const searchActive = !!highlightedIds && highlightedIds.size > 0;
  const activeSet = searchActive ? highlightedIds : undefined;
  const activeBase = searchActive ? highlightBaseId : undefined;
  const anyActive = !!activeSet && activeSet.size > 0;

  const isOn = useCallback((id: string): boolean => {
    return !!activeSet && (activeSet.has(id) || id === activeBase);
  }, [activeSet, activeBase]);

  // 구조(중심/깊이/루트/노드 수)가 바뀌면 다음 정지 때 한 번 카메라 조정.
  // 최초 로드만 전체 fit, 이후 reseed 등은 줌을 유지한 채 새 중심으로 recenter.
  // (컬럼 토글 등 같은 구조의 재시뮬레이션에선 켜지 않아 사용자가 키워둔 줌을 보존)
  useEffect(() => {
    fitPendingRef.current = true;
    fitModeRef.current = loadedRef.current ? "recenter" : "fit";
    loadedRef.current = true;
  }, [data.seed, data.depth, rootId, data.nodes.length]);

  // 단일클릭=소스 이동 / 더블클릭=중심(seed) 다시 탐색. ForceGraph 는 onNodeClick 만 주므로 직접 판정.
  // (2D 와 동일한 모델: 단일=소스, Cmd/Ctrl+클릭=소스 분할, 더블=중심 보기)
  function handleNodeClick(n: any, e?: MouseEvent) {
    const node = n as GraphNode;
    // Cmd(mac)/Ctrl + 클릭 → 즉시 분할 에디터로 소스 이동.
    if (e && (e.metaKey || e.ctrlKey)) {
      if (clickTimer.current != null) {
        window.clearTimeout(clickTimer.current);
        clickTimer.current = null;
      }
      onNodeSelect(node, true);
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
      onNodeSelect(node); // 단일클릭 — 소스 이동
    }, 250);
  }

  // === 초기 fit / force 튜닝 ===
  // 폴백 — 시뮬레이션이 끝까지 안 멈추는 경우 대비(주 경로는 onEngineStop).
  // 같은 handleEngineStop 을 호출해 fit/recenter 로직과 fitPending 가드를 공유.
  useEffect(() => {
    const t = setTimeout(() => handleEngineStop(), 1800);
    return () => clearTimeout(t);
  }, [data.seed, data.depth, rootId]);

  // force 파라미터 튜닝 — d3ForceLayout 은 init 시 존재하므로 안전.
  // 주의: d3ReheatSimulation() 은 호출하지 않는다. graphData digest(=state.layout 할당) 전에
  // 호출되면 engineRunning 만 켜져 layoutTick 이 state.layout(undefined).tick() 으로 첫 프레임에
  // 크래시한다 → 렌더 루프 사망 → 검정 화면. 초기 digest 가 알아서 시뮬레이션을 돌린다.
  useEffect(() => {
    const fg = fgRef.current as any;
    if (!fg) return;
    const charge = fg.d3Force?.("charge");
    if (charge?.strength) charge.strength(-130);
    const link = fg.d3Force?.("link");
    if (link?.distance) link.distance(64);
    // 모듈 클러스터 force — 모듈이 둘 이상일 때만. 단일/없음이면 제거(null)해 영향 없음.
    fg.d3Force?.("moduleCluster", moduleCenters.size ? moduleClusterForce(moduleCenters, 0.1) : null);
  }, [data, rootId, moduleCenters]);

  // 안개 — 그래프 bbox 기준으로 잡아, 오버뷰에선 안 가리고 가까이 들어갔을 때만 먼 노드를 페이드.
  function applyFog(fg: any) {
    const scene = fg.scene?.();
    const bbox = fg.getGraphBbox?.();
    if (scene && bbox) {
      // 배경이 투명이면 안개를 끈다 — Fog 는 단색으로만 페이드해서 투명 배경(월페이퍼) 위에선
      // 먼 노드가 엉뚱한 색으로 사라지고, THREE.Color 도 "transparent" 를 파싱하지 못한다.
      if (UI.canvas3d === "transparent") {
        scene.fog = null;
        return;
      }
      const span = Math.max(
        bbox.x[1] - bbox.x[0], bbox.y[1] - bbox.y[0], bbox.z[1] - bbox.z[0], 200
      );
      scene.fog = new THREE.Fog(UI.canvas3d, span * 1.2, span * 4.0);
    }
  }

  // 줌(카메라-타깃 거리)을 그대로 유지한 채, 시점만 지정 노드로 옮긴다.
  // - reseed: 새 그래프라 좌표가 전부 바뀌므로 카메라를 그대로 두면 빈 공간을 본다 → 새 중심으로(트윈).
  // - 미니맵 클릭: 멀리 떨어진(연결 끊긴) 노드로 이동. animate=false 로 즉시 팬한다.
  //   (cameraPosition 의 600ms 트윈 중 사용자가 좌클릭 회전을 시작하면 TrackballControls 상태가
  //    꼬여 회전이 깨진다. 카메라·타깃을 같은 델타로 평행이동하면 _eye(상대벡터)가 보존돼 안전.)
  function recenterOn(nodeId: string | undefined, animate = true) {
    const fg = fgRef.current as any;
    if (!fg) return;
    const node = graphData.nodes.find((n) => n.id === nodeId) as any;
    if (!node || node.x == null) { fg.zoomToFit?.(500, 100); return; }
    const cam = fg.camera();
    const controls = fg.controls?.();
    const oldTarget: THREE.Vector3 = controls?.target ?? new THREE.Vector3(0, 0, 0);
    const target = new THREE.Vector3(node.x, node.y, node.z);
    if (animate) {
      const dir = new THREE.Vector3().subVectors(cam.position, oldTarget);
      if (dir.lengthSq() < 1e-6) dir.set(0, 0, 1);
      fg.cameraPosition(
        { x: target.x + dir.x, y: target.y + dir.y, z: target.z + dir.z }, target, 600
      );
    } else {
      // 즉시 팬 — 카메라와 타깃을 동일 델타로 평행이동 + controls.update().
      const delta = new THREE.Vector3().subVectors(target, oldTarget);
      cam.position.add(delta);
      controls?.target?.add?.(delta);
      controls?.update?.();
    }
  }

  // 시뮬레이션이 안정화되면 그때 한 번만 카메라를 조정한다(가드로 사용자의 줌 보존).
  function handleEngineStop() {
    const fg = fgRef.current as any;
    if (!fg || !fitPendingRef.current) return;
    fitPendingRef.current = false;
    if (fitModeRef.current === "fit") {
      fg.zoomToFit(500, 100);
    } else {
      recenterOn(rootId);
    }
    applyFog(fg);
  }

  // 마우스 버튼 매핑 (좌클릭 회전 / 휠·우클릭 이동)
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

  // 강조/포커스/컬럼 변경 시 노드·엣지 객체를 다시 계산하도록 리프레시.
  useEffect(() => {
    fgRef.current?.refresh?.();
  }, [activeSet, activeBase, showColumns]);

  return (
    // 배경을 DOM 레이어(canvas3d)로 두고 WebGL 캔버스는 투명(alpha 0)으로 클리어한다.
    // 노드/카드 스프라이트는 불투명 유지 — 보이는 결과는 기존과 동일하다.
    // (이렇게 분리해 두면, 추후 이 DOM 배경 한 겹만 transparent 로 바꿔 IDE 배경이 비치게 할 수 있다.)
    <div style={{ position: "relative", width, height, background: UI.canvas3d }}>
      <ForceGraph3D
        ref={fgRef}
        width={width}
        height={height}
        graphData={graphData}
        backgroundColor="rgba(0,0,0,0)"
        cooldownTicks={200}
        d3VelocityDecay={0.4}
        onEngineStop={handleEngineStop}
        nodeLabel={(n: any) => nodeTooltipHtml(n as GraphNode)}
        linkColor={(l: any) => {
          const link = l as GraphLink;
          const base = RELATION_COLOR[link.relation] ?? "#666";
          if (!anyActive) return base;
          const s = endpointId((l as any).source);
          const t = endpointId((l as any).target);
          return (isOn(s) || isOn(t)) ? base : DIM_COLOR;
        }}
        linkLabel={(l: any) => {
          const link = l as GraphLink;
          const s = endpointId((l as any).source);
          const t = endpointId((l as any).target);
          return `<div style="padding:3px 7px;background:#111827;border-radius:4px;font-size:12px">
            <b style="color:${RELATION_COLOR[link.relation] ?? "#aaa"}">${relationLabel(link.relation)}</b>
            <span style="color:#9aa5b1"> · ${s.split(".").pop()} → ${t.split(".").pop()}</span>
          </div>`;
        }}
        linkOpacity={anyActive ? 0.16 : 0.5}
        linkWidth={(l: any) => {
          if (anyActive) {
            const s = endpointId((l as any).source);
            const t = endpointId((l as any).target);
            return (isOn(s) || isOn(t)) ? 1.2 : 0;
          }
          // 평상시 — 모듈 경계 분류로 두께 강조 (CROSS_FK 굵게, 그 외 얇은 기본선).
          return BOUNDARY_STYLE[(l as GraphLink).boundary ?? "INTRA"].width3d;
        }}
        linkDirectionalArrowLength={3}
        linkDirectionalArrowRelPos={1}
        linkCurvature={(l: any) => (isMultiPair(l as GraphLink) ? 0.25 : 0)}
        linkCurveRotation={(l: any) => {
          const s = endpointId((l as any).source);
          const t = endpointId((l as any).target);
          return s < t ? 0 : Math.PI;
        }}

        // 관계 라벨 sprite — 강조(포커스/검색)된 엣지에만. 평상시엔 깔끔하게 비움.
        linkThreeObjectExtend={true}
        linkThreeObject={(anyActive ? ((l: any) => {
          const s = endpointId((l as any).source);
          const t = endpointId((l as any).target);
          if (!(isOn(s) && isOn(t))) return undefined as any;
          const rel = (l as GraphLink).relation;
          return makeLinkLabelSprite(relationLabel(rel), RELATION_COLOR[rel] ?? "#aaa");
        }) : undefined) as any}
        linkPositionUpdate={((sprite: any, { start, end }: any) => {
          if (!sprite) return;
          sprite.position.set(
            (start.x + end.x) / 2,
            (start.y + end.y) / 2,
            (start.z + end.z) / 2
          );
        }) as any}

        onNodeClick={(n: any, e: any) => handleNodeClick(n, e)}
        onNodeRightClick={(n: any) => onNodeRemove(n as GraphNode)}
        enableNodeDrag={false}

        nodeThreeObjectExtend={false}
        nodeThreeObject={((n: any) => {
          const node = n as GraphNode;
          const obj = makeEntityCardObject(node, showColumns);
          if (anyActive && !isOn(node.id)) {
            setGroupOpacity(obj, 0.16);
          }
          return obj;
        }) as any}/>

      {/* 미니맵 — 전체 노드를 x/y 평면(층 구조 보존)에 투영 + 현재 뷰 박스. 클릭 시 줌 유지한 채 이동. */}
      <Minimap3D fgRef={fgRef} nodes={graphData.nodes as any} viewW={width} viewH={height} onJump={(id) => recenterOn(id, false)} />

      {/* 화면 컨트롤 — 2D 뷰와 동일 위치/스타일로 줌·맞춤 제공. */}
      <div style={{
        position: "absolute", bottom: 16, right: 16,
        display: "flex", gap: 4, background: UI.panel, padding: 4,
        borderRadius: RADIUS.control, border: `1px solid ${UI.border}`
      }}>
        <button style={ctrlBtnStyle} title={t("ctrl.zoomOut")} onClick={() => zoomBy(1.25)}>−</button>
        <button style={ctrlBtnStyle} title={t("ctrl.zoomIn")} onClick={() => zoomBy(0.8)}>+</button>
        <button style={ctrlBtnStyle} title={t("ctrl.fit")} aria-label={t("ctrl.fit.aria")} onClick={() => fgRef.current?.zoomToFit(400, 80)}><IconFit /></button>
      </div>
    </div>
  );
});

// === 3D 미니맵 ===
//
// 3D 는 줌인하면 멀리(특히 연결 끊긴) 노드를 화면에서 놓치기 쉽다. 전체 노드를 월드 x/y 평면
// (y=층 이라 깊이 구조가 세로로 보존)에 투영해 작은 레이더로 그리고, 현재 카메라가 보는 영역을
// 사각형으로 겹쳐 표시한다. 점을 클릭하면 가장 가까운 노드로 (현재 줌을 유지한 채) 이동한다.
interface MiniNode { id: string; x: number; y: number; color: string }
function Minimap3D({ fgRef, nodes, viewW, viewH, onJump }: {
  fgRef: React.MutableRefObject<ForceGraphMethods | undefined>;
  nodes: GraphNode[];
  viewW: number;
  viewH: number;
  onJump: (nodeId: string) => void;
}) {
  // 카메라 이동/노드 정착을 반영하기 위해 주기적으로 다시 그린다(작은 SVG라 가벼움).
  const [, force] = useReducer((c: number) => c + 1, 0);
  useEffect(() => {
    const id = window.setInterval(() => force(), 120);
    return () => window.clearInterval(id);
  }, []);

  // 좁거나 낮은 도킹(left/right/bottom)에선 미니맵이 화면을 가리므로 숨긴다.
  if (viewW < 380 || viewH < 320) return null;
  const fg = fgRef.current as any;
  if (!fg) return null;

  const pts: MiniNode[] = [];
  for (const n of nodes) {
    const nx = (n as any).x, ny = (n as any).y;
    if (typeof nx !== "number" || typeof ny !== "number") continue;
    pts.push({ id: n.id, x: nx, y: ny, color: KIND_COLOR[kindKey(n.entity)] });
  }
  if (pts.length < 2) return null;

  // 현재 카메라가 보는 영역(월드 x/y) 추정: 타깃 중심, 크기 = 2·dist·tan(fov/2).
  let view: { cx: number; cy: number; hw: number; hh: number } | null = null;
  try {
    const cam = fg.camera();
    const controls = fg.controls?.();
    const target = controls?.target;
    if (cam && target) {
      const dist = cam.position.distanceTo(target);
      const fov = ((cam.fov ?? 50) * Math.PI) / 180;
      const hh = Math.tan(fov / 2) * dist;
      view = { cx: target.x, cy: target.y, hw: hh * (viewW / viewH), hh };
    }
  } catch { /* 카메라 미준비 — 뷰 박스 생략 */ }

  // 노드 + 뷰 박스를 모두 포함하는 bbox.
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const p of pts) {
    if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
    if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y;
  }
  if (view) {
    minX = Math.min(minX, view.cx - view.hw); maxX = Math.max(maxX, view.cx + view.hw);
    minY = Math.min(minY, view.cy - view.hh); maxY = Math.max(maxY, view.cy + view.hh);
  }
  const spanX = (maxX - minX) || 1;
  const spanY = (maxY - minY) || 1;
  const MM_MAX = Math.max(96, Math.min(168, Math.min(viewW, viewH) * 0.26));
  const s = MM_MAX / Math.max(spanX, spanY);
  const mmW = spanX * s;
  const mmH = spanY * s;
  // 월드 → SVG. y 는 위가 +y 라 반전(루트=y0 가 위로).
  const sx = (wx: number) => (wx - minX) * s;
  const sy = (wy: number) => (maxY - wy) * s;

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
          const wx = minX + (e.clientX - rect.left) / s;
          const wy = maxY - (e.clientY - rect.top) / s;
          let best: MiniNode | null = null, bd = Infinity;
          for (const p of pts) {
            const d = (p.x - wx) ** 2 + (p.y - wy) ** 2;
            if (d < bd) { bd = d; best = p; }
          }
          if (best) onJump(best.id);
        }}
      >
        {view && (
          <rect
            x={sx(view.cx - view.hw)} y={sy(view.cy + view.hh)}
            width={view.hw * 2 * s} height={view.hh * 2 * s}
            fill="rgba(139,92,246,0.14)" stroke={UI.accent} strokeWidth={1}
          />
        )}
        {pts.map((p) => (
          <circle key={p.id} cx={sx(p.x)} cy={sy(p.y)} r={2.6} fill={p.color} opacity={0.9} />
        ))}
      </svg>
    </div>
  );
}

const ctrlBtnStyle: React.CSSProperties = controlButton;

export default GraphView;
