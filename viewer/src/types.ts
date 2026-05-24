export type Relation =
  | "EXTENDS" | "IMPLEMENTS"
  | "ONE_TO_MANY" | "MANY_TO_ONE" | "ONE_TO_ONE" | "MANY_TO_MANY"
  | "USES_ENTITY";

export interface ColumnInfo {
  fieldName: string;
  columnName?: string | null;
  javaType: string;
  primaryKey: boolean;
  nullable: boolean;
  unique: boolean;
  length?: number | null;
  generatedValue?: string | null;
}

export interface EntityInfo {
  kind: "entity" | "mappedSuperclass" | "embeddable";
  tableName?: string | null;
  columns: ColumnInfo[];
}

export interface GraphNode {
  id: string;
  name: string;
  pkg: string;
  kind: string;
  stereotypes: string[];
  entity?: EntityInfo | null;
}

export interface GraphLink {
  source: string;
  target: string;
  relation: Relation;
  weight: number;
  label?: string | null;
}

export interface GraphData {
  seed: string;
  depth: number;
  nodes: GraphNode[];
  links: GraphLink[];
}
