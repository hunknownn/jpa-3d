// order 모듈 — user/catalog 에 의존. 공유 영속성 컨텍스트라 다른 모듈 엔티티를 직접 @ManyToOne 으로 참조.
dependencies {
    add("implementation", project(":user"))
    add("implementation", project(":catalog"))
}
