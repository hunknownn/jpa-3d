// MSA product-service — 독립 빌드/DB.
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
