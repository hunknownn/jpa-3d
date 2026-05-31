package com.jpa3d.export

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.jpa3d.analyzer.Jpa3dAnalyzer
import com.jpa3d.analyzer.JpaStubs

/**
 * 통합 골든 테스트 — 실제 Java 소스를 PSI 픽스처에 올린 뒤
 *   소스 → [Jpa3dAnalyzer] → [ExportConverter] → {DdlExporter / JsonExporter / MermaidExporter}
 * 전체 파이프라인을 한 줄로 흘려보내고 끝단 산출물을 검증한다.
 *
 * 단위 테스트([DdlExporterTest], [com.jpa3d.analyzer.Jpa3dAnalyzerTest])는 각자 손으로 만든
 * 입력으로 한 단계만 본다. 여기서는 그 사이 계약(analyzer 가 채운 필드를 converter 가 보존하고
 * exporter 가 읽는지)이 실제로 맞물리는지 — 특히 FK 타입 상속, SINGLE_TABLE 머지, snake_case 가
 * 끝까지 전달되는지 — 를 회귀로 잡는다.
 */
class ExportPipelineGoldenTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        JpaStubs.install(myFixture)
    }

    // ===================================================================================
    // 헬퍼 — 소스를 분석해 ExportModel 까지 한 번에
    // ===================================================================================

    private fun model(): ExportModel {
        val graph = Jpa3dAnalyzer(project).analyze()
        return ExportConverter.toExportModel(graph, ExportScope.ALL, seed = "", depth = 0)
    }

    /**
     * DDL 을 렌더링하되 식별자 quoting(Postgres `"id"`, MySQL `` `id` ``)을 제거해 반환.
     * 구조(테이블/컬럼/제약 존재 여부)만 검증하려는 골든 테스트에서 dialect quoting 차이에
     * 흔들리지 않게 한다. quoting 자체의 정확성은 [DdlExporterTest] 가 dialect 별로 본다.
     */
    private fun ddl(
        dialect: DdlDialect = DdlDialect.POSTGRES,
        snakeCase: Boolean = true,
        dropExisting: Boolean = false
    ): String = DdlExporter(dialect, snakeCase, dropExisting).render(model())
        .replace("\"", "").replace("`", "")

    // ===================================================================================
    // 단순 엔티티 — CREATE TABLE + PK
    // ===================================================================================

    fun testSingleEntityProducesCreateTableWithPk() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User {
                @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
                @Column(nullable = false) String name;
            }
            """.trimIndent()
        )
        val sql = ddl()
        assertContains(sql, "CREATE TABLE user")
        assertContains(sql, "PRIMARY KEY (id)")
        // name NOT NULL 이 끝까지 전달
        assertTrue("name NOT NULL 누락:\n$sql", Regex("name\\s+VARCHAR\\(255\\)\\s+NOT NULL").containsMatchIn(sql))
    }

    // ===================================================================================
    // ManyToOne FK — FK 컬럼이 타깃 PK 타입을 상속 (VARCHAR(255) 회귀)
    // ===================================================================================

    fun testManyToOneFkInheritsTargetPkType() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class User { @Id Long id; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Purchase {
                @Id Long id;
                @ManyToOne @JoinColumn(name = "user_id", nullable = false) User user;
            }
            """.trimIndent()
        )
        val sql = ddl()
        // 회귀: 예전엔 user_id VARCHAR(255) 로 떨어졌음. 타깃 User.id 가 Long 이므로 BIGINT 여야.
        assertFalse("FK 가 VARCHAR 로 떨어짐 (타입 상속 실패):\n$sql", sql.contains("user_id VARCHAR"))
        assertTrue("user_id BIGINT 누락:\n$sql", Regex("user_id\\s+BIGINT").containsMatchIn(sql))
        // FK ALTER 가 Purchase → user 로 생성
        assertContains(sql, "FOREIGN KEY (user_id) REFERENCES user")
    }

    // ===================================================================================
    // SINGLE_TABLE 상속 — 한 테이블로 머지 + discriminator + 자식 컬럼 흡수
    // ===================================================================================

    fun testSingleTableInheritanceMergesIntoOneTable() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
            @DiscriminatorColumn(name = "animal_type")
            public class Animal { @Id Long id; String name; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @DiscriminatorValue("DOG")
            public class Dog extends Animal { Integer barkLoudness; }
            """.trimIndent()
        )
        val sql = ddl()
        // 부모 테이블 하나만 — Dog 테이블은 따로 생기지 않음
        assertContains(sql, "CREATE TABLE animal")
        assertFalse("SINGLE_TABLE 인데 Dog 테이블이 생성됨:\n$sql", sql.contains("CREATE TABLE dog"))
        // 자식 컬럼이 부모 테이블로 흡수 (snake_case 적용)
        assertContains(sql, "bark_loudness")
        // discriminator 컬럼
        assertContains(sql, "animal_type")
    }

    // ===================================================================================
    // JOINED 상속 — 자식 테이블이 부모 PK 를 자기 PK 이자 부모 참조 FK 로 상속
    // ===================================================================================

    fun testJoinedInheritanceChildHasPkAndFkToParent() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            @Inheritance(strategy = InheritanceType.JOINED)
            public class Vehicle { @Id Long id; String name; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Car extends Vehicle { Integer doors; }
            """.trimIndent()
        )
        val sql = ddl()
        // 부모/자식 둘 다 별도 테이블
        assertContains(sql, "CREATE TABLE vehicle")
        assertContains(sql, "CREATE TABLE car")
        // 자식이 부모 own 컬럼(name)을 복사하지 않음 — JOINED 는 분리 저장
        val carBlock = sql.substringAfter("CREATE TABLE car").substringBefore(");")
        assertFalse("JOINED 자식이 부모 컬럼(name)을 잘못 복사:\n$carBlock", carBlock.contains("name"))
        // 자식 PK = 부모 PK 컬럼(id), 타입도 상속(BIGINT)
        assertTrue("자식에 상속 PK(id BIGINT) 누락:\n$carBlock", Regex("id\\s+BIGINT").containsMatchIn(carBlock))
        assertTrue("자식 PRIMARY KEY(id) 누락:\n$carBlock", carBlock.contains("PRIMARY KEY (id)"))
        // 자식 PK → 부모 PK 참조 FK
        assertTrue("car.id → vehicle.id FK 누락:\n$sql",
            Regex("ALTER TABLE car .*FOREIGN KEY \\(id\\) REFERENCES vehicle \\(id\\)").containsMatchIn(sql))
        // 자식 PK 는 부모에서 받으므로 자체 IDENTITY/생성 절이 붙으면 안 됨
        assertFalse("자식 PK 에 잘못된 IDENTITY 생성절:\n$carBlock",
            carBlock.contains("IDENTITY") || carBlock.contains("nextval"))
    }

    // ===================================================================================
    // @ManyToMany — 조인 테이블 생성 (양방향이어도 owning 한쪽만)
    // ===================================================================================

    fun testManyToManyGeneratesJoinTableOnce() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Course {
                @Id Long id;
                @ManyToMany(mappedBy = "courses") java.util.List<Student> students;
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Student {
                @Id Long id;
                @ManyToMany
                @JoinTable(name = "student_course")
                java.util.List<Course> courses;
            }
            """.trimIndent()
        )
        val sql = ddl()
        // 조인 테이블이 정확히 한 번만 생성 (양방향 중복 방지)
        assertEquals("조인 테이블이 1회만 생성돼야:\n$sql",
            2, sql.split("CREATE TABLE student_course").size)
        val block = sql.substringAfter("CREATE TABLE student_course").substringBefore(");")
        assertTrue("student_id BIGINT 누락:\n$block", Regex("student_id\\s+BIGINT").containsMatchIn(block))
        assertTrue("course_id BIGINT 누락:\n$block", Regex("course_id\\s+BIGINT").containsMatchIn(block))
        assertTrue("복합 PK 누락:\n$block", block.contains("PRIMARY KEY (student_id, course_id)"))
        // 양쪽 FK
        assertTrue("student FK 누락:\n$sql",
            Regex("ALTER TABLE student_course .*FOREIGN KEY \\(student_id\\) REFERENCES student \\(id\\)").containsMatchIn(sql))
        assertTrue("course FK 누락:\n$sql",
            Regex("ALTER TABLE student_course .*FOREIGN KEY \\(course_id\\) REFERENCES course \\(id\\)").containsMatchIn(sql))
        // M:N 은 호스트 엔티티에 FK 컬럼을 만들지 않음 — Student 테이블엔 courses 컬럼 없음
        val studentBlock = sql.substringAfter("CREATE TABLE student (").substringBefore(");")
        assertFalse("M:N 이 호스트 컬럼 생성:\n$studentBlock", studentBlock.contains("courses"))
    }

    // ===================================================================================
    // @SequenceGenerator — CREATE SEQUENCE (Postgres)
    // ===================================================================================

    fun testSequenceGeneratorEmitsCreateSequence() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Invoice {
                @Id
                @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "inv_gen")
                @SequenceGenerator(name = "inv_gen", sequenceName = "invoice_seq")
                Long id;
            }
            """.trimIndent()
        )
        val sql = ddl(DdlDialect.POSTGRES)
        assertContains(sql, "CREATE SEQUENCE invoice_seq")
        // DEFAULT nextval 로 PK 와 연결
        assertTrue("nextval 연결 누락:\n$sql", sql.contains("nextval('invoice_seq')"))
    }

    // ===================================================================================
    // snake_case 변환 — end-to-end (acronym 경계 포함)
    // ===================================================================================

    fun testSnakeCaseAppliedThroughPipeline() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class HttpRequestLog {
                @Id Long id;
                String userAgent;
            }
            """.trimIndent()
        )
        val snake = ddl(snakeCase = true)
        assertContains(snake, "CREATE TABLE http_request_log")
        assertContains(snake, "user_agent")

        // snakeCase=false 면 원본 이름 유지
        val raw = ddl(snakeCase = false)
        assertContains(raw, "HttpRequestLog")
        assertContains(raw, "userAgent")
    }

    // ===================================================================================
    // JSON export — 라운드트립 (Jackson 으로 다시 파싱 가능)
    // ===================================================================================

    fun testJsonExportRoundTrips() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity @Table(name = "accounts")
            public class Account {
                @Id Long id;
                @Column(name = "balance", precision = 19, scale = 2) java.math.BigDecimal balance;
            }
            """.trimIndent()
        )
        val json = JsonExporter.render(model())
        val tree = ObjectMapper().readTree(json)
        val entity = tree["entities"].first { it["name"].asText() == "Account" }
        assertEquals("accounts", entity["tableName"].asText())
        val balance = entity["columns"].first { it["name"].asText() == "balance" }
        assertEquals(19, balance["precision"].asInt())
        assertEquals(2, balance["scale"].asInt())
    }

    // ===================================================================================
    // Mermaid export — 엔티티 + 관계 라인 포함
    // ===================================================================================

    fun testMermaidExportIncludesEntitiesAndRelation() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Author { @Id Long id; }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Book {
                @Id Long id;
                @ManyToOne Author author;
            }
            """.trimIndent()
        )
        val mermaid = MermaidExporter.render(model())
        assertContains(mermaid, "erDiagram")
        assertContains(mermaid, "Author")
        assertContains(mermaid, "Book")
        // 엔티티 블록이 아니라 실제 관계 라인(커넥터 `--` 포함)에 두 엔티티가 함께 등장해야.
        val relLine = mermaid.lines().firstOrNull {
            it.contains("Book") && it.contains("Author") && it.contains("--")
        }
        assertNotNull("Book–Author 관계 라인 누락:\n$mermaid", relLine)
    }

    // ===================================================================================
    // dropExisting — DROP TABLE 선행
    // ===================================================================================

    fun testDropExistingEmitsDropBeforeCreate() {
        myFixture.addClass(
            """
            package com.example;
            import jakarta.persistence.*;
            @Entity
            public class Widget { @Id Long id; }
            """.trimIndent()
        )
        val sql = ddl(dropExisting = true)
        val dropIdx = sql.indexOf("DROP TABLE")
        val createIdx = sql.indexOf("CREATE TABLE")
        assertTrue("DROP TABLE 누락:\n$sql", dropIdx >= 0)
        assertTrue("DROP 이 CREATE 보다 뒤:\n$sql", dropIdx < createIdx)
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue("'$needle' 가 출력에 없음:\n$haystack", haystack.contains(needle))
    }
}
