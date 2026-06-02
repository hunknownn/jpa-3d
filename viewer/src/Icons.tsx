// 단색 라인 아이콘 모음 — stroke=currentColor 라 버튼 color 로 제어된다.
// IntelliJ 툴바처럼 액션/뷰 조작을 텍스트 대신 작은 아이콘으로 제공해 compact 하게.

interface IconProps { size?: number }

function svgProps(size: number) {
  return {
    width: size, height: size, viewBox: "0 0 16 16",
    fill: "none", stroke: "currentColor",
    strokeWidth: 1.6, strokeLinecap: "round" as const, strokeLinejoin: "round" as const
  };
}

/** 동기화(원형 화살표). spin=true 면 회전 애니메이션. */
export function IconSync({ size = 14, spin = false }: IconProps & { spin?: boolean }) {
  return (
    <svg {...svgProps(size)} style={spin ? { animation: "jpa3d-spin 0.9s linear infinite" } : undefined}>
      <path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9" />
      <path d="M13.5 2.2V5H10.7" />
    </svg>
  );
}

/** 전체 보기(네 모서리 바깥 화살표). */
export function IconFit({ size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size)}>
      <path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4v-4" />
    </svg>
  );
}

/** 돋보기. */
export function IconSearch({ size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size)}>
      <circle cx="7" cy="7" r="4.5" />
      <path d="M10.5 10.5 14 14" />
    </svg>
  );
}

/** 레이아웃 방향: 가로(LR) / 세로(TB) 흐름 화살표. */
export function IconDirection({ size = 14, horizontal }: IconProps & { horizontal: boolean }) {
  return horizontal ? (
    <svg {...svgProps(size)}>
      <path d="M2.5 8h10M9.5 4.5 13 8l-3.5 3.5" />
    </svg>
  ) : (
    <svg {...svgProps(size)}>
      <path d="M8 2.5v10M4.5 9.5 8 13l3.5-3.5" />
    </svg>
  );
}

/** 위치 초기화(되돌리기 화살표). */
export function IconReset({ size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size)}>
      <path d="M2.5 8a5.5 5.5 0 1 0 1.6-3.9" />
      <path d="M2.5 2.2V5H5.3" />
    </svg>
  );
}

/** 범례(리스트/항목). */
export function IconLegend({ size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size)}>
      <path d="M6 4h8M6 8h8M6 12h8" />
      <circle cx="2.5" cy="4" r="0.9" fill="currentColor" stroke="none" />
      <circle cx="2.5" cy="8" r="0.9" fill="currentColor" stroke="none" />
      <circle cx="2.5" cy="12" r="0.9" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** 표시 옵션(슬라이더/필터). */
export function IconOptions({ size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size)}>
      <path d="M2 5h7M12 5h2M2 11h2M7 11h7" />
      <circle cx="10.5" cy="5" r="1.6" />
      <circle cx="4.5" cy="11" r="1.6" />
    </svg>
  );
}
