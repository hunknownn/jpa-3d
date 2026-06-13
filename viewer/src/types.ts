export type Relation =
  | "EXTENDS" | "IMPLEMENTS"
  | "ONE_TO_MANY" | "MANY_TO_ONE" | "ONE_TO_ONE" | "MANY_TO_MANY"
  | "USES_ENTITY"
  /** 모듈 경계를 넘는 약한 ID 참조 (엣지 emit 은 PR3). */
  | "SOFT_REF";

/** 프로젝트 아키텍처 형태 (plugin 의 ArchitectureDetector 산출). */
export type ArchitectureMode = "MONOLITH" | "MODULAR_MONOLITH" | "MSA";

/** 엣지의 모듈 경계 분류 (엣지 3종). */
export type EdgeBoundary = "INTRA" | "CROSS_FK" | "CROSS_SOFT";

export interface ColumnInfo {
  fieldName: string;
  columnName?: string | null;
  javaType: string;
  primaryKey: boolean;
  nullable: boolean;
  unique: boolean;
  /** @Table(indexes=...) 의 columnList 에 포함된 컬럼. */
  indexed?: boolean;
  /** @ManyToOne / @OneToOne(owning) 의 FK 컬럼. 엣지도 함께 존재. */
  foreignKey?: boolean;
  /** FK 가 가리키는 entity FQN. foreignKey=true 일 때만. */
  fkTarget?: string | null;
  length?: number | null;
  generatedValue?: string | null;
}

export interface InheritanceInfo {
  /** "SINGLE_TABLE" | "JOINED" | "TABLE_PER_CLASS" */
  strategy: string;
  /** @DiscriminatorColumn(name=...) — 베이스에 정의됨. */
  discriminatorColumn?: string | null;
  /** @DiscriminatorValue("...") — 자식 entity 의 식별 값. */
  discriminatorValue?: string | null;
}

export interface EntityInfo {
  kind: "entity" | "mappedSuperclass" | "embeddable";
  tableName?: string | null;
  columns: ColumnInfo[];
  inheritance?: InheritanceInfo | null;
}

export interface GraphNode {
  id: string;
  name: string;
  pkg: string;
  kind: string;
  stereotypes: string[];
  entity?: EntityInfo | null;
  /** 노드가 속한 논리 모듈 (plugin 의 ModuleResolver 산출). 공간 그룹핑/필터 키. */
  module?: string | null;
}

export interface GraphLink {
  source: string;
  target: string;
  relation: Relation;
  weight: number;
  label?: string | null;
  /** 모듈 경계 분류 (plugin 산출). 엣지 스타일 키. 모듈 미상이면 없음. */
  boundary?: EdgeBoundary | null;
}

export interface GraphData {
  seed: string;
  depth: number;
  nodes: GraphNode[];
  links: GraphLink[];
  /** IDE 인덱싱 진행 중이라 분석이 보류됐음. viewer 는 자동 재시도. */
  indexing?: boolean;
  /** 추론된 아키텍처 형태. 분석 결과가 없으면 없음. */
  architecture?: ArchitectureMode | null;
  /** 등장한 논리 모듈명 목록 (정렬·중복 제거). 모듈 필터 칩 소스. */
  modules?: string[];
}
