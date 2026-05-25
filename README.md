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

Compatible with IntelliJ IDEA 2024.2+ (any edition that bundles the Java plugin).

## Usage

1. Open your JPA project in IntelliJ IDEA.
2. Wait for indexing to finish.
3. Open the **JPA 3D** tool window on the right side.
4. Click **↻ 동기화** to (re-)analyze the current project state.

Top bar controls:

| Control | What it does |
|---|---|
| 전체 / seed 중심 | Show every entity, or only the BFS-reachable subgraph around a seed |
| 표시 (관계만 / +컬럼 / +Repository) | Detail level — bare ERD, with columns, or including Spring Data repositories |
| 상속 ON/OFF | Toggle `EXTENDS` edges (`@MappedSuperclass` chains) |
| 뷰 3D / 2D | Switch renderer |
| 검색 input | Type to highlight matching nodes; click a suggestion to reseed |

Other interactions:

- **Click** a node — jump to its source file in the IDE.
- **Drag** a node (2D view) — manually reposition; click "위치초기화" to undo.
- **Right-click** a node — set as new seed.
- **Hover** a column row — highlight all edges connected to that entity.

## Features

- **3D force-directed graph** and **2D orthogonal ERD** (elkjs layered layout)
- **Crow's foot cardinality markers** for `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`
- **`@Inheritance` strategy badges** (`SINGLE_TABLE` / `JOINED` / `TABLE_PER_CLASS`) with `@DiscriminatorColumn` / `@DiscriminatorValue`
- **Multi-level Spring Data Repository inheritance** — follows user-defined intermediate interfaces (e.g. `MyBaseRepo<T> extends JpaRepository<T,ID>`)
- Bidirectional relationship deduplication (drops `mappedBy` mirror edges automatically)
- Column metadata: PK, nullable, unique, length, `@GeneratedValue` strategy, `@Column(name=...)`
- `@MappedSuperclass` / `@Embeddable` rendered with `EXTENDS` edges
- **Search highlight** — typing in the search box fades non-matching nodes/edges
- **Manual layout overrides** — drag nodes in 2D; reset anytime
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
