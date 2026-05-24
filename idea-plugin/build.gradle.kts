import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.jpa3d"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Community 2024.2 를 타겟. 추후 Ultimate JPA 모듈 의존이 필요해지면 IU 로 전환.
        intellijIdeaCommunity("2024.2")

        // JCEF (JBCefBrowser) 와 ToolWindow API 만 사용 — 별도 bundled plugin 의존 없음.
        bundledPlugins()
        plugins()

        // instrumentCode task 가 NotNull 등 어노테이션 인식을 위해 필요
        instrumentationTools()

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

/**
 * viewer 빌드 결과(`viewer/dist`)를 plugin 리소스의 `web/` 으로 복사한다.
 *
 * - viewer 가 아직 빌드돼있지 않으면 `npm run build` 를 한 번 돌린다.
 * - JBCefBrowser 가 `file://` 로 직접 읽거나, 후속 단계에서 plugin 내장 정적 서버로 서빙할 수 있다.
 */
val viewerDir = rootProject.layout.projectDirectory.dir("viewer")
val viewerDist = viewerDir.dir("dist")

// resources 루트(generated) 아래 `web/` 디렉터리로 복사 → classloader 가
// `web/index.html` 로 조회 가능. 다른 리소스(META-INF) 와 네임스페이스 충돌 회피.
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")

val buildViewer by tasks.registering(Exec::class) {
    description = "viewer 를 npm run build 로 빌드 (dist 가 비어있을 때만)"
    workingDir = viewerDir.asFile
    // npm 이 없는 환경에서 plugin 빌드만 시도하는 경우를 위해 viewer/dist 가 이미 있으면 스킵
    onlyIf { !viewerDist.asFile.resolve("index.html").exists() }
    commandLine("npm", "run", "build")
}

val copyViewer by tasks.registering(Copy::class) {
    description = "viewer/dist → plugin 리소스 web/"
    dependsOn(buildViewer)
    from(viewerDist)
    into(generatedResourcesDir.map { it.dir("web") })
}

sourceSets {
    main {
        resources {
            srcDir(generatedResourcesDir)
        }
    }
}

tasks.named("processResources") {
    dependsOn(copyViewer)
}
