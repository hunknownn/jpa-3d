import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.jpa3d"
version = "0.2.3"

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
        // IntelliJ IDEA Community 2024.2 를 타겟. 추후 Ultimate JPA 모듈 의존이 필요해지면 IU 로 전환.
        intellijIdeaCommunity("2024.2")

        // Java PSI (PsiClass, PsiAnnotation 등) 를 위해 bundled Java plugin 필요.
        // ToolWindow / JCEF 는 platform core 에 있어 추가 의존 불필요.
        bundledPlugin("com.intellij.java")
        plugins()

        // instrumentCode task 가 NotNull 등 어노테이션 인식을 위해 필요
        instrumentationTools()

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
            sinceBuild = "242"
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

    // pluginVerification 블록은 IntelliJ Platform Gradle Plugin 2.1 의 일부 메서드가
    // Gradle 9 에서 컨테이너 등록 API 와 충돌해 활성화 시 빌드가 깨진다.
    // 호환성 검사는 별도로 `./gradlew :idea-plugin:verifyPlugin` 으로 실행 가능.
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

tasks.test {
    useJUnitPlatform()
    // Platform testFramework 가 등록하는 JUnit5TestSessionListener 비활성 — 위 코멘트 참조.
    systemProperty("idea.test.junit5.disabled", "true")
}
