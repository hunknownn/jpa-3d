// MSA order-service — 독립 빌드/DB. user/product 서비스에 의존하지 않는다.
// 다른 서비스의 엔티티는 import 하지 못하고, PK 를 평범한 Long 필드(userId/productId)로만 참조한다.
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
