import { useState } from "react";
import {
  UI, RELATION_COLOR, RELATION_LABEL, RELATION_DASH,
  KIND_COLOR, KIND_LABEL,
  INHERITANCE_COLOR, INHERITANCE_LABEL, COLUMN_MARK, RADIUS
} from "./theme";
import { Relation } from "./types";

// 화면에 실제로 나타나는 관계만, 의미 순서대로.
const REL_ORDER: Relation[] = [
  "ONE_TO_MANY", "MANY_TO_ONE", "ONE_TO_ONE", "MANY_TO_MANY", "EXTENDS", "USES_ENTITY"
];
const KIND_ORDER = ["entity", "mappedSuperclass", "embeddable", "repository"] as const;
const INH_ORDER = ["SINGLE_TABLE", "JOINED", "TABLE_PER_CLASS"];

/**
 * 접고 펼 수 있는 범례. theme.ts 의 토큰을 그대로 읽어
 * "화면의 색 == 범례의 색" 을 보장한다. 좌하단 고정.
 */
export default function Legend({ view }: { view: "2d" | "3d" }) {
  const [open, setOpen] = useState(false);

  return (
    <div style={{ position: "absolute", left: 16, bottom: 16, zIndex: 20, fontSize: 12 }}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title="범례 보기"
        style={{
          padding: "5px 10px", fontSize: 12,
          background: UI.panel, color: UI.textDim,
          border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control, cursor: "pointer",
          display: "flex", alignItems: "center", gap: 6
        }}
      >
        <span style={{ fontSize: 13 }}>{open ? "▾" : "▸"}</span> 범례
      </button>

      {open && (
        <div style={{
          marginTop: 6, width: 248, maxHeight: "60vh", overflowY: "auto",
          background: UI.panel, color: UI.text,
          border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
          padding: "10px 12px", lineHeight: 1.5,
          boxShadow: "0 6px 20px rgba(0,0,0,0.4)"
        }}>
          <Section title="관계">
            {REL_ORDER.map((rel) => (
              <Row key={rel}
                swatch={<LineSwatch color={RELATION_COLOR[rel]} dashed={!!RELATION_DASH[rel]} />}
                label={RELATION_LABEL[rel]}
                hint={REL_HINT[rel]}
              />
            ))}
            <div style={{ color: UI.textMuted, fontSize: 11, marginTop: 4 }}>
              {view === "2d"
                ? "선 끝 표기 — 막대 │ = 1, 까마귀발 ⪛ = N"
                : "화살표 방향 = 관계의 향하는 쪽"}
            </div>
          </Section>

          <Section title="엔티티 종류">
            {KIND_ORDER.map((k) => (
              <Row key={k}
                swatch={<BoxSwatch color={KIND_COLOR[k]} />}
                label={KIND_LABEL[k]}
              />
            ))}
          </Section>

          <Section title="컬럼 표기">
            <Row swatch={<Glyph>🔑</Glyph>} label="기본키 (PK)" />
            <Row swatch={<Glyph>🔗</Glyph>} label="외래키 (FK)" />
            <Row swatch={<Glyph color={COLUMN_MARK.unique}>◆</Glyph>} label="unique 제약" />
            <Row swatch={<Glyph color={COLUMN_MARK.indexed}>#</Glyph>} label="인덱스 포함" />
            <Row swatch={<Glyph color={UI.textMuted}>*</Glyph>} label="NOT NULL (타입 뒤 *)" />
          </Section>

          <Section title="상속 전략 배지">
            {INH_ORDER.map((s) => (
              <Row key={s}
                swatch={<BoxSwatch color={INHERITANCE_COLOR[s]} />}
                label={INHERITANCE_LABEL[s]}
                hint={INH_HINT[s]}
              />
            ))}
          </Section>
        </div>
      )}
    </div>
  );
}

const REL_HINT: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "@OneToMany",
  MANY_TO_ONE: "@ManyToOne",
  ONE_TO_ONE: "@OneToOne",
  MANY_TO_MANY: "@ManyToMany",
  EXTENDS: "상속 / @MappedSuperclass",
  USES_ENTITY: "Repository → Entity"
};

const INH_HINT: Record<string, string> = {
  SINGLE_TABLE: "단일 테이블",
  JOINED: "조인 테이블",
  TABLE_PER_CLASS: "구체 클래스별 테이블"
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{
        color: UI.textMuted, fontSize: 10, fontWeight: 700, letterSpacing: 0.6,
        textTransform: "uppercase", marginBottom: 4
      }}>
        {title}
      </div>
      {children}
    </div>
  );
}

function Row({ swatch, label, hint }: { swatch: React.ReactNode; label: string; hint?: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "1px 0" }}>
      <span style={{ width: 22, display: "inline-flex", justifyContent: "center", flexShrink: 0 }}>
        {swatch}
      </span>
      <span style={{ color: UI.text }}>{label}</span>
      {hint && <span style={{ color: UI.textMuted, fontSize: 11, marginLeft: "auto" }}>{hint}</span>}
    </div>
  );
}

function LineSwatch({ color, dashed }: { color: string; dashed?: boolean }) {
  return <span style={{ display: "inline-block", width: 20, height: 0, borderTop: `3px ${dashed ? "dashed" : "solid"} ${color}` }} />;
}

function BoxSwatch({ color }: { color: string }) {
  return <span style={{ display: "inline-block", width: 14, height: 14, background: color, borderRadius: 3 }} />;
}

function Glyph({ children, color }: { children: React.ReactNode; color?: string }) {
  return <span style={{ color, fontSize: 12 }}>{children}</span>;
}
