// 멀티모듈 모놀리식 — 단일 빌드(이 settings 하나) 안에 여러 Gradle 서브프로젝트.
// 모듈은 분리돼 있지만 빌드/DB(영속성 컨텍스트)는 공유하므로 모듈 경계를 넘는 진짜 @ManyToOne 이 존재한다.
rootProject.name = "shop-modular"

include(":user", ":catalog", ":order")
