import { GraphData, GraphNode } from "./types";

/**
 * IntelliJ plugin 브리지.
 *
 * 두 가지 모드를 지원한다:
 * 1. 임베드 모드 (plugin 안의 JCEF):
 *    - 호스트가 `window.__JPA3D_BRIDGE__` 를 주입한다.
 *    - 모든 호출은 그 브리지를 통해 JBCefJSQuery 등으로 전달된다.
 * 2. 스탠드얼론 모드 (브라우저 단독 개발):
 *    - `/fixtures/*.json` 정적 파일을 fetch 해서 동작한다.
 *    - PSI 분석기 없이도 UI 개발이 가능하도록 한다.
 *
 * 브리지는 비동기 콜백 한 쌍(`request`)으로만 추상화한다.
 */

export interface ErdRequest {
  scope: "all" | "seed";
  seed?: string;
  depth?: number;
  level: 1 | 2 | 3;
}

interface Jpa3dBridge {
  /**
   * 호스트(IntelliJ plugin)에 요청을 보내고 결과 JSON 을 받는다.
   * `kind` 로 분기하며, 페이로드는 `args` 로 전달.
   */
  request(kind: "erd" | "search" | "navigate", args: unknown): Promise<unknown>;
}

declare global {
  interface Window {
    __JPA3D_BRIDGE__?: Jpa3dBridge;
  }
}

function hasBridge(): boolean {
  return typeof window !== "undefined" && window.__JPA3D_BRIDGE__ != null;
}

/**
 * 브리지가 곧 주입될 수도 있으므로(JCEF 의 onLoadEnd 가 React mount 이후일 수 있음)
 * 최대 `timeoutMs` 까지 `jpa3d:bridge-ready` 이벤트를 기다린다.
 * 그래도 안 오면 null 로 떨어져 fixture fallback 으로 간다.
 */
function awaitBridge(timeoutMs = 1500): Promise<Jpa3dBridge | null> {
  if (hasBridge()) return Promise.resolve(window.__JPA3D_BRIDGE__!);
  return new Promise((resolve) => {
    let done = false;
    const onReady = () => {
      if (done) return;
      done = true;
      window.removeEventListener("jpa3d:bridge-ready", onReady);
      resolve(window.__JPA3D_BRIDGE__ ?? null);
    };
    window.addEventListener("jpa3d:bridge-ready", onReady);
    setTimeout(() => {
      if (done) return;
      done = true;
      window.removeEventListener("jpa3d:bridge-ready", onReady);
      resolve(hasBridge() ? window.__JPA3D_BRIDGE__! : null);
    }, timeoutMs);
  });
}

export async function fetchErd(opts: ErdRequest): Promise<GraphData> {
  const bridge = await awaitBridge();
  if (bridge) {
    return bridge.request("erd", opts) as Promise<GraphData>;
  }
  // 스탠드얼론 fallback: 미리 만들어둔 fixture
  const res = await fetch("fixtures/erd.json");
  if (!res.ok) throw new Error(`fixture erd.json missing: ${res.status}`);
  return res.json();
}

/**
 * IDE 의 해당 클래스 소스로 점프 요청. plugin 모드에서만 의미 있음.
 * 스탠드얼론(브라우저) 에서는 no-op.
 */
export async function navigateToSource(fqn: string): Promise<void> {
  const bridge = await awaitBridge();
  if (!bridge) return;
  await bridge.request("navigate", { fqn });
}

export async function searchErd(q: string, includeRepositories = true): Promise<GraphNode[]> {
  if (!q.trim()) return [];
  const bridge = await awaitBridge();
  if (bridge) {
    return bridge.request("search", { q, includeRepositories }) as Promise<GraphNode[]>;
  }
  // 스탠드얼론 fallback: 전체 노드 목록에서 필터
  const res = await fetch("fixtures/erd.json");
  if (!res.ok) return [];
  const data: GraphData = await res.json();
  const needle = q.toLowerCase();
  return data.nodes.filter((n) => {
    if (!includeRepositories && n.entity == null) return false;
    return n.name.toLowerCase().includes(needle) || n.id.toLowerCase().includes(needle);
  });
}
