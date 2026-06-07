// JPA 3D 데모 프로젝트.
//   플러그인이 @Entity / 관계 / 상속 / Spring Data 리포지토리를 분석하는 데 필요한
//   jakarta.persistence + spring-data-jpa 만 의존으로 둔다. 실행은 하지 않으며,
//   IDE 가 클래스패스를 해석해 PSI 분석이 가능하도록 하는 것이 목적이다.
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    implementation("org.springframework.data:spring-data-jpa:3.2.5")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
