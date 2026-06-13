// 루트 — 공통 설정만. 실제 엔티티는 각 서브프로젝트(:user, :catalog, :order)에.
subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    dependencies {
        add("implementation", "jakarta.persistence:jakarta.persistence-api:3.1.0")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
}
