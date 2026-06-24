import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.jpa3d"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
        // verifyPlugin 이 EAP(2026.2-EAP-SNAPSHOT) IDE 를 받을 수 있도록 스냅샷 채널 추가.
        snapshots()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JSON 직렬화 — IntelliJ Platform 도 내부적으로 Jackson 을 쓰지만,
    // 명시 의존을 두는 편이 안전 (Platform 의 클래스 가시성이 버전마다 변동).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // JUnit 5 — Platform 의존 없는 순수 단위 테스트용 (DdlExporter, ExportConverter 등).
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Vintage engine — JUnit 3/4 호환. BasePlatformTestCase 계열(LightJavaCodeInsightFixtureTestCase)이
    // JUnit 3 TestCase 를 상속하므로 vintage 가 있어야 실행됨.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    // JUnit 4 — BasePlatformTestCase 의 supertype junit.framework.TestCase 가 여기 들어있음 (컴파일 의존).
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // IntelliJ IDEA Community 2024.3 를 타겟. 추후 Ultimate JPA 모듈 의존이 필요해지면 IU 로 전환.
        // (2024.3 = build 243 — addBrowseFolderListener(project, descriptor) 등 정식 API 기준선.)
        intellijIdeaCommunity("2024.3")

        // Java PSI (PsiClass, PsiAnnotation 등) 를 위해 bundled Java plugin 필요.
        // ToolWindow / JCEF 는 platform core 에 있어 추가 의존 불필요.
        bundledPlugin("com.intellij.java")
        // Kotlin 엔티티 분석/테스트 — UAST 가 Kotlin 을 파싱하려면 번들 Kotlin 플러그인이 필요.
        // (Kotlin non-null 타입, 주생성자 프로퍼티 등 언어 특성 검증의 전제.)
        bundledPlugin("org.jetbrains.kotlin")
        plugins()

        // (2.16.0 부터 instrumentationTools() 제거 — 코드 instrumentation 이 자동 구성됨)

        // Analyzer 테스트는 LightJavaCodeInsightFixtureTestCase 를 상속해 PSI/UAST 픽스처가 필요.
        // 이 프레임워크가 JUnit5TestSessionListener 를 자동 등록하는데, 그게 JUnit5 launcher 부트스트랩
        // 시 instantiate 실패해 모든 테스트가 깨졌었음.
        // → tasks.test 에 `idea.test.junit5.disabled=true` 시스템 프로퍼티로 listener 비활성.
        // (DdlExporterTest 같은 순수 JUnit5 테스트는 platform setup 불필요, vintage 로 도는 PSI 테스트는
        //  자체 setUp 에서 IdeaTestApplication 을 초기화하므로 둘 다 정상.)
        testFramework(TestFrameworkType.Platform)
        // LightJavaCodeInsightFixtureTestCase 는 Java 플러그인 측 테스트 프레임워크에 들어있음.
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // 마켓플레이스 publish 설정.
    //   ORG_GRADLE_PROJECT_publishToken 환경변수 (또는 gradle.properties 의 publishToken) 로 전달.
    //   토큰은 https://plugins.jetbrains.com/author/me/tokens 에서 발급.
    publishing {
        token = providers.gradleProperty("publishToken")
        // 첫 출시는 default. 베타/EAP 트랙으로 올리려면 ["beta"] 등.
        channels = listOf("default")
    }

    // 코드 서명 (선택). 인증서는 https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    //   - signPlugin.certificateChain / privateKey / password 를 gradle.properties 로
    //   - 미설정 시 signPlugin task 가 자동으로 skip 되어 unsigned zip 그대로 배포됨
    signing {
        certificateChain = providers.gradleProperty("certificateChain")
        privateKey = providers.gradleProperty("privateKey")
        password = providers.gradleProperty("privateKeyPassword")
    }

    // 바이너리 호환성 검사 — 배포 전 `./gradlew :idea-plugin:verifyPlugin` 로 실행.
    // recommended() 가 sinceBuild(243)~최신 호환 범위에서 *받을 수 있는* IDE 빌드를 자동 선택한다
    //   (현재 기준 2024.3 / 2025.1 / 2025.2 / 2025.3 / 2026.1 + 최신 EAP — Marketplace 검증 대상과 일치).
    // 특정 버전만 검증하려면 ides { } 안에서 create(IntelliJPlatformType.IntellijIdeaCommunity, "<build>") 로 지정.
    // 주의 1: recommended() 는 IntelliJ Platform Gradle Plugin 2.16+ 에서만 Gradle 9 와 호환된다(2.1.x 는 깨짐).
    // 주의 2: 각 IDE 를 통째로 받아(버전당 ~1.5GB dmg + 압축해제) 합계 20~30GB 디스크가 필요하다.
    pluginVerification {
        ides {
            recommended()
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

// Windows 에선 npm 이 npm.cmd 로 노출된다.
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

// npm 실행 파일을 절대경로로 해석한다.
// Gradle 데몬/IntelliJ 에서 띄운 빌드는 PATH 가 로그인 셸과 달라(예: /opt/homebrew/bin 누락)
// 단순히 "npm" 으로는 "Cannot run program npm" 으로 실패할 수 있다.
// PATH 를 먼저 뒤지고, 못 찾으면 macOS Homebrew / 일반 설치 경로를 보강한 뒤,
// 그래도 없으면 "npm" 으로 둔다(onlyIf 가 최종적으로 가용성을 판단).
val npmExecutable: String = run {
    val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
    val fallbackDirs = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    (pathDirs + fallbackDirs)
        .map { File(it, npmCmd) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
        ?: npmCmd
}

val buildViewer by tasks.registering(Exec::class) {
    description = "viewer 를 npm run build 로 빌드 (viewer 소스 변경 시 자동 재빌드)"
    workingDir = viewerDir.asFile

    // 입력 추적 — viewer 소스/설정이 바뀌면 Gradle 이 up-to-date 를 깨고 재빌드한다.
    // (이전엔 dist 존재만으로 스킵해, 소스를 고쳐도 옛 번들이 그대로 plugin 에 들어갔다.)
    inputs.dir(viewerDir.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(viewerDir.dir("public")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        viewerDir.file("package.json"),
        viewerDir.file("package-lock.json"),
        viewerDir.file("index.html"),
        viewerDir.file("vite.config.ts"),
        viewerDir.file("tsconfig.json")
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    // 출력 = dist. 입력이 그대로면 이 task 는 UP-TO-DATE 로 스킵된다(불필요한 npm 빌드 회피).
    outputs.dir(viewerDist)

    commandLine(npmExecutable, "run", "build")

    // npm 이 없는 환경(헤드리스 CI 등)에서 plugin 빌드만 시도하는 경우의 안전장치 —
    // npm 을 못 찾으면 빌드를 건너뛰고 디스크의 기존 dist 를 그대로 쓴다.
    onlyIf {
        val available = try {
            ProcessBuilder(npmExecutable, "-v").redirectErrorStream(true).start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
        if (!available) {
            logger.warn("npm 을 찾을 수 없어 viewer 빌드를 건너뜁니다 — 기존 viewer/dist 를 사용합니다.")
        }
        available
    }
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

// buildSearchableOptions 는 설정 UI 인덱싱을 위해 헤드리스 IDE 를 띄우는데,
// 이미 실행 중인 IDE 인스턴스와 충돌(External instance command received)해 배포 빌드를 깨뜨린다.
// 검색 옵션 사전 인덱싱은 선택 기능이라 비활성 — 설정 검색은 런타임에 정상 동작한다.
tasks.named("buildSearchableOptions") {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    // Platform testFramework 가 등록하는 JUnit5TestSessionListener 비활성 — 위 코멘트 참조.
    systemProperty("idea.test.junit5.disabled", "true")
}
