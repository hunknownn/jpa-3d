// catalog 모듈 — user 에 의존. ProductReview 가 다른 모듈 user 의 User 를 직접 @ManyToOne 참조(CROSS_FK).
dependencies {
    add("implementation", project(":user"))
}
