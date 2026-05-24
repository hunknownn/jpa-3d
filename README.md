# jpa-3d

IntelliJ plugin that visualizes the JPA Entity model of your project as an interactive **3D / 2D ERD** inside the IDE.

> 한국어: JPA Entity 모델을 IDE 안에서 3D / 2D ERD 로 시각화하는 IntelliJ Plugin.

## Features

- **3D force-directed graph** and **2D orthogonal ERD** (elkjs layered layout)
- Cardinality detection for `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`
- Bidirectional relationship deduplication (drops `mappedBy` mirror edges automatically)
- Spring Data Repository → Entity mapping (`JpaRepository`, `CrudRepository`, …)
- Column metadata: PK, nullable, unique, length, `@GeneratedValue` strategy
- `@MappedSuperclass` / `@Embeddable` with `EXTENDS` edges
- **Java & Kotlin** via UAST — Kotlin annotation use-site targets handled transparently
- Both `jakarta.persistence` and legacy `javax.persistence`

## Usage

1. Open your JPA project in IntelliJ IDEA.
2. Wait for indexing to finish.
3. Open the **JPA 3D** tool window on the right side.

Toggle between 3D / 2D views and adjust detail level (relations only / +columns / +repositories) from the top bar.

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

# Or run the viewer standalone in a browser (uses fixture data)
cd viewer && npm install && npm run dev
```

Requires JDK 21 for the Gradle daemon — pinned in `gradle.properties`.

## Acknowledgements

ERD pipeline ported and adapted from [DepScope](https://github.com/hunknownn/DepScope).
