// MSA user-service — 독립 빌드/DB. 다른 서비스에 의존하지 않는다.
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
