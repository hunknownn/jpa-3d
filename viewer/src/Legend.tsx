import { useState } from "react";
import {
  UI, RELATION_COLOR, RELATION_DASH,
  KIND_COLOR, INHERITANCE_COLOR, COLUMN_MARK, RADIUS,
  ARCH_COLOR
} from "./theme";
import { IconLegend } from "./Icons";
import { ArchitectureMode, EdgeBoundary, Relation } from "./types";
import { t, relationLabel, kindLabel, inheritanceLabel } from "./i18n";

// 화면에 실제로 나타나는 관계만, 의미 순서대로.
const REL_ORDER: Relation[] = [
  "ONE_TO_MANY", "MANY_TO_ONE", "ONE_TO_ONE", "MANY_TO_MANY", "EXTENDS", "USES_ENTITY"
];
const KIND_ORDER = ["entity", "mappedSuperclass", "embeddable", "repository"] as const;
const INH_ORDER = ["SINGLE_TABLE", "JOINED", "TABLE_PER_CLASS"];
const BOUNDARY_ORDER: EdgeBoundary[] = ["INTRA", "CROSS_FK", "CROSS_SOFT"];

/**
 * 접고 펼 수 있는 범례. theme.ts 의 토큰을 그대로 읽어
 * "화면의 색 == 범례의 색" 을 보장한다. 좌하단 고정.
 */
export default function Legend({ view, architecture, modules }: {
  view: "2d" | "3d";
  architecture?: ArchitectureMode | null;
  modules?: string[];
}) {
  const [open, setOpen] = useState(false);
  // 모듈 경계 섹션은 모듈이 둘 이상(= 경계가 존재)일 때만 의미가 있다.
  const showBoundary = (modules?.length ?? 0) >= 2;

  return (
    <div style={{ position: "absolute", left: 16, bottom: 16, zIndex: 20, fontSize: 12 }}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        title={t("legend.button.title")}
        style={{
          padding: "5px 10px", fontSize: 12,
          background: UI.panel, color: UI.textDim,
          border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.control, cursor: "pointer",
          display: "flex", alignItems: "center", gap: 6
        }}
      >
        <IconLegend size={13} /> {t("legend.button")}
      </button>

      {open && (
        <div style={{
          marginTop: 6, width: 248, maxHeight: "60vh", overflowY: "auto",
          background: UI.panel, color: UI.text,
          border: `1px solid ${UI.borderStrong}`, borderRadius: RADIUS.container,
          padding: "10px 12px", lineHeight: 1.5,
          boxShadow: "0 6px 20px rgba(0,0,0,0.4)"
        }}>
          {view === "3d" && (
            <div style={{
              color: UI.textMuted, fontSize: 11, lineHeight: 1.5,
              marginBottom: 10, paddingBottom: 8, borderBottom: `1px solid ${UI.border}`
            }}>
              {t("legend.3d.layer.before")}<b style={{ color: UI.text }}>{t("legend.3d.layer.word")}</b>{t("legend.3d.layer.after")}
            </div>
          )}
          <Section title={t("legend.section.relations")}>
            {REL_ORDER.map((rel) => (
              <Row key={rel}
                swatch={<LineSwatch color={RELATION_COLOR[rel]} dashed={!!RELATION_DASH[rel]} />}
                label={relationLabel(rel)}
                hint={relHint(rel)}
              />
            ))}
            <div style={{ color: UI.textMuted, fontSize: 11, marginTop: 4 }}>
              {view === "2d"
                ? t("legend.relations.foot.2d")
                : t("legend.relations.foot.3d")}
            </div>
          </Section>

          {showBoundary && (
            <Section title={t("legend.section.boundary")}>
              {architecture && (
                <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 6 }}>
                  <span style={{
                    width: 8, height: 8, borderRadius: 2, flexShrink: 0,
                    background: ARCH_COLOR[architecture] ?? UI.textMuted
                  }} />
                  <span style={{ color: UI.text, fontWeight: 600 }}>{t(`arch.${architecture}`)}</span>
                  <span style={{ color: UI.textMuted, fontSize: 11, marginLeft: "auto" }}>
                    {(modules?.length ?? 0)} mod
                  </span>
                </div>
              )}
              {BOUNDARY_ORDER.map((b) => (
                <Row key={b}
                  swatch={<BoundarySwatch boundary={b} />}
                  label={t(`legend.boundary.${b}`)}
                />
              ))}
            </Section>
          )}

          <Section title={t("legend.section.kinds")}>
            {KIND_ORDER.map((k) => (
              <Row key={k}
                swatch={<BoxSwatch color={KIND_COLOR[k]} />}
                label={kindLabel(k)}
              />
            ))}
          </Section>

          <Section title={t("legend.section.columns")}>
            <Row swatch={<Glyph>🔑</Glyph>} label={t("legend.col.pk")} />
            <Row swatch={<Glyph>🔗</Glyph>} label={t("legend.col.fk")} />
            <Row swatch={<Glyph color={COLUMN_MARK.unique}>◆</Glyph>} label={t("legend.col.unique")} />
            <Row swatch={<Glyph color={COLUMN_MARK.indexed}>#</Glyph>} label={t("legend.col.indexed")} />
            <Row swatch={<Glyph color={UI.textMuted}>*</Glyph>} label={t("legend.col.notnull")} />
          </Section>

          <Section title={t("legend.section.inheritance")}>
            {INH_ORDER.map((s) => (
              <Row key={s}
                swatch={<BoxSwatch color={INHERITANCE_COLOR[s]} />}
                label={inheritanceLabel(s)}
                hint={inhHint(s)}
              />
            ))}
          </Section>
        </div>
      )}
    </div>
  );
}

// 카디널리티 관계의 힌트는 어노테이션명(언어 무관). 구조/참조 관계만 번역.
const REL_ANNOTATION: Partial<Record<Relation, string>> = {
  ONE_TO_MANY: "@OneToMany",
  MANY_TO_ONE: "@ManyToOne",
  ONE_TO_ONE: "@OneToOne",
  MANY_TO_MANY: "@ManyToMany"
};

function relHint(rel: Relation): string | undefined {
  if (rel === "EXTENDS") return t("legend.relHint.EXTENDS");
  if (rel === "USES_ENTITY") return t("legend.relHint.USES_ENTITY");
  return REL_ANNOTATION[rel];
}

function inhHint(strategy: string): string {
  return t(`legend.inhHint.${strategy}`, undefined, strategy);
}

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

/** 모듈 경계 분류 스와치 — 색이 아닌 **두께/대시**로 의미를 전달(화면 규칙과 동일). */
function BoundarySwatch({ boundary }: { boundary: EdgeBoundary }) {
  const style: Record<EdgeBoundary, { w: number; color: string; dashed: boolean }> = {
    INTRA: { w: 2, color: UI.textMuted, dashed: false },
    CROSS_FK: { w: 4, color: UI.text, dashed: false },
    CROSS_SOFT: { w: 2, color: RELATION_COLOR.SOFT_REF ?? UI.textMuted, dashed: true }
  };
  const s = style[boundary];
  return <span style={{ display: "inline-block", width: 20, height: 0, borderTop: `${s.w}px ${s.dashed ? "dashed" : "solid"} ${s.color}` }} />;
}

function BoxSwatch({ color }: { color: string }) {
  return <span style={{ display: "inline-block", width: 14, height: 14, background: color, borderRadius: 3 }} />;
}

function Glyph({ children, color }: { children: React.ReactNode; color?: string }) {
  return <span style={{ color, fontSize: 12 }}>{children}</span>;
}
