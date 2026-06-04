# jpa-3d

IntelliJ plugin that visualizes the JPA Entity model of your project as an interactive **3D / 2D ERD** inside the IDE.

> 한국어: JPA Entity 모델을 IDE 안에서 3D / 2D ERD 로 시각화하는 IntelliJ Plugin.

## Install

**From JetBrains Marketplace** (recommended):

[![JetBrains Marketplace](https://img.shields.io/badge/Marketplace-JPA%203D-blue?logo=jetbrains)](https://plugins.jetbrains.com/plugin/31936-jpa-3d)

In IntelliJ IDEA: <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search **"JPA 3D"** → Install.

**From release zip:**

1. Download `JPA-3D-<version>.zip` from the [Marketplace versions page](https://plugins.jetbrains.com/plugin/31936-jpa-3d/versions).
2. <kbd>Settings</kbd> → <kbd>Plugins</kbd> → ⚙ → <kbd>Install Plugin from Disk…</kbd>
3. Restart IDE.

Compatible with IntelliJ IDEA 2024.3+ (build 243; any edition that bundles the Java plugin).

## Usage

1. Open your JPA project in IntelliJ IDEA.
2. Wait for indexing to finish.
3. Open the **JPA 3D** tool window on the right side.
4. Click **↻ 동기화** to (re-)analyze the current project state.

Top bar controls:

| Control | What it does |
|---|---|
| 전체 / 중심 (seed) | Show every entity, or only the subgraph reachable from the selected centers |
| 중심 chips | In 중심 mode, the selected centers — mix of entities and packages — appear as chips; remove individually or clear all |
| 깊이 slider | Adjust the neighborhood depth (BFS hops) in real time — client-side, no re-query; counts the shortest hop from any selected center |
| 표시 (관계만 / +컬럼 / +Repository) | Detail level — bare ERD, with columns, or including Spring Data repositories |
| 상속 ON/OFF | Toggle `EXTENDS` edges (`@MappedSuperclass` chains) |
| 뷰 3D / 2D | Switch renderer |
| 검색 input | Search entities **and** packages; click a result to add it as a center (in 전체 mode it just highlights matches) |

Other interactions:

- **Click** a node — jump to its source file in the IDE.
- **<kbd>Cmd</kbd>/<kbd>Ctrl</kbd>+Click** a node — open its source in a 2×2 editor grid, filling four quadrants then cycling, to compare several entities side by side.
- **Right-click a package** in the Project view → **JPA 3D: 이 패키지 추가** — add that package (with sub-packages) as a center.
- **Drag** a node (2D view) — manually reposition; click "위치초기화" to undo.
- **Right-click** a node — set as new seed.
- **Hover** a column row — highlight all edges connected to that entity.
- **3D minimap** (bottom corner) — click anywhere to fly the camera there while keeping zoom.

## Export

**Tools → JPA 3D: Export…** dumps the current model (or the seed-centered subgraph) to:

- **JSON** — the full entity/relation schema.
- **DDL** — `CREATE TABLE` for **PostgreSQL / MySQL / H2 / Oracle**, with optional `snake_case` naming and `DROP TABLE IF EXISTS` headers.
- **Mermaid** — `erDiagram` source.
- **PNG / SVG** — a snapshot of the current viewer (SVG is 2D-only).

Defaults for view/scope/depth, analysis package filters, and Export options live under **Settings → Tools → JPA 3D**.

## Features

- **3D force-directed graph** and **2D orthogonal ERD** (elkjs layered layout)
- **Multiple centers (seeds)** — focus on several entities and packages at once (mixed), each shown as a removable chip; depth is the shortest hop from any of them
- **Add a package from the Project view** — right-click a package → *JPA 3D: 이 패키지 추가* to center on it (sub-packages included)
- **Split navigation** — <kbd>Cmd</kbd>/<kbd>Ctrl</kbd>+click a node to open its source across a 2×2 editor grid
- **3D minimap** — projected overview of every node with the current view box; click to fly the camera there
- **Connection depth slider** — adjust the neighborhood depth in real time (client-side filtering)
- **Crow's foot cardinality markers** for `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`
- **`@Inheritance` strategy badges** (`SINGLE_TABLE` / `JOINED` / `TABLE_PER_CLASS`) with `@DiscriminatorColumn` / `@DiscriminatorValue`
- **Multi-level Spring Data Repository inheritance** — follows user-defined intermediate interfaces (e.g. `MyBaseRepo<T> extends JpaRepository<T,ID>`)
- **FK columns** on entity cards for `@ManyToOne` / owning `@OneToOne` with `@JoinColumn` name resolution
- **`@Table` indexes / uniqueConstraints** rendered as column glyphs (◆ unique, # indexed)
- Bidirectional relationship deduplication (drops `mappedBy` mirror edges automatically)
- Column metadata: PK, FK, nullable, unique, length, `@GeneratedValue` strategy, `@Column(name=...)`
- `@MappedSuperclass` / `@Embeddable` rendered with `EXTENDS` edges
- **Search highlight** — typing in the search box fades non-matching nodes/edges
- **Manual layout overrides** — drag nodes in 2D; reset anytime
- **Export** to JSON / DDL (PostgreSQL / MySQL / H2 / Oracle) / Mermaid / PNG / SVG
- **Settings page** (Settings → Tools → JPA 3D) — persist viewer, analysis-filter, and Export defaults
- **Java & Kotlin** via UAST — Kotlin annotation use-site targets handled transparently
- Both `jakarta.persistence` and legacy `javax.persistence`
- Analysis runs in a read action and is cached; manual sync button avoids EDT lag

## Repository layout

```
jpa-3d/
├── viewer/           React + Vite renderer (embedded in JCEF)
└── idea-plugin/      IntelliJ plugin (Kotlin)
```

## Build from source

```bash
# Plugin zip
./gradlew :idea-plugin:buildPlugin
# → idea-plugin/build/distributions/idea-plugin-*.zip

# Launch a sandbox IDE with the plugin loaded
./gradlew :idea-plugin:runIde

# Run the viewer standalone in a browser (uses fixture data)
cd viewer && npm install && npm run dev
```

Requires JDK 21 for the Gradle daemon — pinned in `gradle.properties`.

## Acknowledgements

ERD pipeline ported and adapted from [DepScope](https://github.com/hunknownn/DepScope).
