# shop-demo — JPA 3D 데모 프로젝트

JPA 3D 플러그인의 스크린샷/데모용 샘플 JPA 모델입니다. 전자상거래 도메인을 본떠
플러그인이 시각화하는 거의 모든 요소를 의도적으로 담았습니다.

## 무엇이 들어있나

- **24개 엔티티** (`com.example.shop.*`) — User, Order, Product, Category, Payment 등
- **모든 관계 유형** — `@OneToOne`, `@OneToMany` / `@ManyToOne`, `@ManyToMany`, 자기참조(Category)
- **3가지 상속 전략**
  - `SINGLE_TABLE` — `Notification` → `EmailNotification` / `PushNotification`
  - `JOINED` — `Payment` → `CardPayment` / `BankTransferPayment`
  - `TABLE_PER_CLASS` — `Document` → `Invoice` / `Receipt`
- **`@MappedSuperclass` 다단계 체인** — `BaseEntity` → `AuditableEntity` → 엔티티
- **`@Embeddable` 값 타입** — `Address`, `Money` (여러 엔티티가 공유)
- **다단계 Spring Data 리포지토리 상속** — `UserRepository` → `AuditableRepository` → `BaseRepository` → `JpaRepository`
- **컬럼 메타데이터** — PK / FK / nullable / unique / length / `@GeneratedValue` / `@Column(name)`
- **`@Table` 인덱스 & 유니크 제약** — 여러 엔티티에 분포

## 사용법

1. IntelliJ IDEA 에서 이 `demo/` 폴더를 **별도 프로젝트로 열기** (Gradle 임포트).
2. 의존성 다운로드 + 인덱싱이 끝날 때까지 대기.
3. 우측 **JPA 3D** 툴윈도우 → 동기화.

> 실행용 애플리케이션이 아닙니다. `jakarta.persistence` + `spring-data-jpa` 의존만 두어
> IDE 가 클래스패스를 해석하고 플러그인이 PSI 분석을 할 수 있게 하는 것이 목적입니다.

컴파일 확인:

```bash
cd demo && ./gradlew compileJava
```
