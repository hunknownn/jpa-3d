// seed 기반 depth(홉) 필터링의 클라이언트 측 로직.
//
// 백엔드(GraphScope)가 매 depth 마다 부분 그래프를 잘라 보내는 대신, viewer 는
// seed 연결성분 전체를 한 번만 받아두고(=envelope) 여기서 홉 거리를 계산해 로컬로 자른다.
// 그래서 depth 슬라이더는 재요청 없이 즉시 반응한다.
//
// 규칙은 백엔드 [com.jpa3d.model.GraphScope] 와 1:1 로 맞춘다:
//  - seedType="fqn"     : 정확히 그 FQN (singleton)
//  - seedType="package" : pkg 가 seed 와 같거나 그 하위(`seed.` 접두)인 모든 노드 → 멀티 소스
//  - 거리는 방향 무시(undirected) BFS.
import { GraphLink, GraphNode } from "./types";

export const SEED_TYPE_PACKAGE = "package";

/** force-graph 가 link 끝점을 객체로 치환했을 수 있어 양쪽 모양을 모두 받는다. */
export function endpointId(end: string | { id?: string }): string {
  return typeof end === "string" ? end : (end?.id ?? "");
}

/**
 * seed selector 를 출발 노드 id 집합으로 해석한다.
 * 매칭이 없으면 빈 집합 → 호출 측에서 "필터 비활성(전체 표시)" 로 처리.
 */
export function resolveSeedIds(nodes: GraphNode[], seedType: string, seed: string): Set<string> {
  if (!seed) return new Set();
  if (seedType === SEED_TYPE_PACKAGE) {
    return new Set(
      nodes.filter((n) => n.pkg === seed || n.pkg.startsWith(`${seed}.`)).map((n) => n.id)
    );
  }
  return new Set(nodes.filter((n) => n.id === seed).map((n) => n.id));
}

/**
 * [seeds] 에서 무방향 BFS 한 각 노드의 홉 거리(seed 자신=0).
 * 도달 불가 노드는 맵에 포함되지 않는다. seeds 가 비면 빈 맵.
 */
export function bfsDistances(seeds: Set<string>, links: GraphLink[]): Map<string, number> {
  const dist = new Map<string, number>();
  if (seeds.size === 0) return dist;

  const adj = new Map<string, string[]>();
  const link = (a: string, b: string) => {
    const list = adj.get(a);
    if (list) list.push(b);
    else adj.set(a, [b]);
  };
  for (const l of links) {
    const s = endpointId(l.source);
    const t = endpointId(l.target);
    link(s, t);
    link(t, s);
  }

  const queue: string[] = [];
  for (const s of seeds) {
    dist.set(s, 0);
    queue.push(s);
  }
  for (let head = 0; head < queue.length; head++) {
    const cur = queue[head];
    const d = dist.get(cur)!;
    for (const nb of adj.get(cur) ?? []) {
      if (!dist.has(nb)) {
        dist.set(nb, d + 1);
        queue.push(nb);
      }
    }
  }
  return dist;
}
