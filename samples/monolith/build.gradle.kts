// 일반 모놀리식 샘플 — 단일 Gradle 모듈, 단일 패키지(com.shop).
// 모든 엔티티가 한 모듈 안에 있어 JPA-3D 가 MONOLITH 로 감지한다.
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
