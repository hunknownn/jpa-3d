/**
 * 노드 카드 폭이 고정(CARD_W)이라, 내부 텍스트(엔티티명·컬럼명)가 넘치면 "…" 로 잘라
 * 레이아웃을 안정적으로 유지한다. 잘린 전체 값은 hover(2D `<title>`, 3D 노드 툴팁)로 노출.
 *
 * 가변 노드 폭 대신 truncation 을 택한 이유: 노드가 수십~수백 개 공존하는 그래프라
 * 폭이 균일해야 2D 레이어드 배치/3D 포스 간격과 스프라이트 정렬이 깨지지 않는다.
 *
 * 측정 전용 오프스크린 캔버스 컨텍스트를 모듈에 하나 두고 재사용한다(2D SVG·3D 캔버스 공용).
 */
let measureCtx: CanvasRenderingContext2D | null = null;

function ctx(): CanvasRenderingContext2D {
  if (!measureCtx) {
    measureCtx = document.createElement("canvas").getContext("2d")!;
  }
  return measureCtx;
}

/**
 * `font` 으로 그렸을 때 `maxWidth`(px) 안에 들어가도록 자르고, 잘렸으면 끝에 "…" 를 붙인다.
 * 이분 탐색으로 들어가는 최대 글자 수를 찾는다.
 *
 * @param font canvas `ctx.font` 형식 (예: `12px ui-monospace, monospace`). 측정 정확도를
 *             위해 실제로 그리는 폰트와 같은 값을 넘긴다.
 */
export function fitText(text: string, maxWidth: number, font: string): string {
  if (!text || maxWidth <= 0) return "";
  const c = ctx();
  c.font = font;
  if (c.measureText(text).width <= maxWidth) return text;

  const ellipsis = "…";
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (c.measureText(text.slice(0, mid) + ellipsis).width <= maxWidth) lo = mid;
    else hi = mid - 1;
  }
  return lo > 0 ? text.slice(0, lo) + ellipsis : ellipsis;
}

/** `font` 으로 그렸을 때의 텍스트 폭(px). 우측 부기/타입 블록 공간 확보용. */
export function measureWidth(text: string, font: string): number {
  if (!text) return 0;
  const c = ctx();
  c.font = font;
  return c.measureText(text).width;
}

/** HTML 툴팁에 사용자 코드 유래 문자열(엔티티/컬럼명)을 넣을 때 주입 방지용 이스케이프. */
export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
