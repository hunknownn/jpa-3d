package com.analyzer.extractor;

import com.analyzer.extractor.model.CallSite;
import com.analyzer.extractor.model.ColumnInfo;
import com.analyzer.extractor.model.EntityInfo;
import com.analyzer.extractor.model.FieldInfo;
import com.analyzer.extractor.model.MethodInfo;
import com.analyzer.extractor.model.Node;
import com.analyzer.extractor.model.Relation;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * .class 한 개를 ASM 으로 읽어 {@link GraphIndex} 에 노드/엣지 적재.
 * - extends/implements
 * - field type
 * - method signature (param/return)
 * - INVOKE* (CALLS), NEW (NEW)
 * - class-level annotations (ANNOTATED_BY)  ※ stereotype 추출도 같이
 */
public final class ClassIndexer {

    private static final int ASM_API = Opcodes.ASM9;

    private final GraphIndex index;
    private final Predicate<String> includeFqn; // true = 분석/엣지 포함

    public ClassIndexer(GraphIndex index, Predicate<String> includeFqn) {
        this.index = index;
        this.includeFqn = includeFqn;
    }

    public void index(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new Visitor(), ClassReader.SKIP_FRAMES);
    }

    private boolean keep(String fqn) { return includeFqn.test(fqn); }

    private static String fqn(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static String pkg(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    private final class Visitor extends ClassVisitor {

        private String thisFqn;
        private String kind;
        private String sourceFile; // 예: "ClassIndexer.java"
        private final List<String> stereotypes = new ArrayList<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        private final List<FieldInfo> fields = new ArrayList<>();

        // ERD 메타데이터 — @Entity / @MappedSuperclass / @Embeddable 클래스에서만 채워진다.
        private String entityKind;     // "entity" / "mappedSuperclass" / "embeddable"
        private String entityTable;    // @Table.name
        private final List<ColumnInfo> columns = new ArrayList<>();

        Visitor() { super(ASM_API); }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source; // 예: "ClassIndexer.java"
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.thisFqn = fqn(name);
            this.kind = (access & Opcodes.ACC_INTERFACE) != 0 ? "interface"
                    : (access & Opcodes.ACC_ENUM) != 0 ? "enum"
                    : (access & Opcodes.ACC_ANNOTATION) != 0 ? "annotation"
                    : "class";

            if (!keep(thisFqn)) { thisFqn = null; return; }

            if (superName != null && !"java/lang/Object".equals(superName)) {
                String s = fqn(superName);
                if (keep(s)) index.addEdge(thisFqn, s, Relation.EXTENDS);
            }
            if (interfaces != null) {
                for (String i : interfaces) {
                    String s = fqn(i);
                    if (keep(s)) index.addEdge(thisFqn, s, Relation.IMPLEMENTS);
                }
            }

            // Spring Data Repository 인터페이스를 직접 상속한 경우, 첫 제네릭 타입 파라미터를
            // 대상 Entity로 보고 USES_ENTITY 엣지를 만든다. (예: JpaRepository<User, Long> → User)
            // 다단계 상속(중간 BaseRepo 경유)은 1차에서는 미지원.
            if (signature != null) extractRepositoryEntity(signature);
        }

        private void extractRepositoryEntity(String classSignature) {
            new SignatureReader(classSignature).accept(new SignatureVisitor(ASM_API) {
                @Override
                public SignatureVisitor visitInterface() {
                    return new RepositoryInterfaceVisitor();
                }
            });
        }

        private final class RepositoryInterfaceVisitor extends SignatureVisitor {
            private String ifaceInternal;
            private int argIndex;
            private String firstArgInternal;

            RepositoryInterfaceVisitor() { super(ASM_API); }

            @Override
            public void visitClassType(String name) {
                if (ifaceInternal == null) ifaceInternal = name;
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                if (argIndex++ != 0) return new SignatureVisitor(ASM_API) {};
                return new SignatureVisitor(ASM_API) {
                    @Override
                    public void visitClassType(String name) {
                        if (firstArgInternal == null) firstArgInternal = name;
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (ifaceInternal == null || firstArgInternal == null) return;
                if (!isSpringDataRepository(fqn(ifaceInternal))) return;
                String entityFqn = fqn(firstArgInternal);
                if (keep(entityFqn)) {
                    index.addEdge(thisFqn, entityFqn, Relation.USES_ENTITY);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (thisFqn == null) return null;
            String annFqn = fqn(Type.getType(descriptor).getInternalName());
            stereotypes.add(simple(annFqn));
            if (keep(annFqn)) {
                index.addEdge(thisFqn, annFqn, Relation.ANNOTATED_BY);
            }
            // ERD 분류: 같은 클래스에 여러 개 붙는 일은 없지만 우선순위는 entity > mappedSuperclass > embeddable
            String ek = entityKindOf(annFqn);
            if (ek != null && entityKind == null) entityKind = ek;
            if (isTableAnnotation(annFqn)) {
                return new AnnotationVisitor(ASM_API) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("name".equals(name) && value instanceof String s && !s.isEmpty()) {
                            entityTable = s;
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (thisFqn == null) return null;
            for (String t : referencedTypes(Type.getType(descriptor))) {
                if (keep(t)) index.addEdge(thisFqn, t, Relation.HAS_FIELD);
            }
            boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
            // 컴파일러가 만든 합성 필드(this$0 등)는 표시 목록에서 제외
            if (!synthetic) {
                fields.add(new FieldInfo(
                        name,
                        Type.getType(descriptor).getClassName(),
                        modifiers(access)
                ));
            }
            if (synthetic) return null;

            // JPA 어노테이션 파싱을 위해 FieldVisitor 반환.
            // 컬렉션 필드라면 제네릭 element 타입을 미리 추출해둔다 (List<User> → com.foo.User).
            Type rawType = Type.getType(descriptor);
            String fieldJavaType = rawType.getClassName();
            String rawTypeFqn = rawType.getSort() == Type.OBJECT ? rawType.getClassName() : null;
            String elementInternal = firstTypeArgument(signature);
            String elementTypeFqn = elementInternal == null ? null : fqn(elementInternal);
            return new JpaFieldVisitor(name, fieldJavaType, rawTypeFqn, elementTypeFqn, access);
        }

        /**
         * 필드의 JPA 연관관계 어노테이션을 수집하고 visitEnd 시점에 JPA Relation 엣지를 발생시킨다.
         *
         * 수집 대상:
         * - @OneToMany / @ManyToOne / @OneToOne / @ManyToMany — 관계 종류
         * - mappedBy — 비주인 측 표시 (post-process에서 양방향 통합에 사용)
         * - targetEntity — Class 값 지정 시 대상 타입을 명시적으로 지정
         * - @JoinTable.name — 다대다 조인 테이블명
         *
         * 이 단계에서는 mappedBy 유무와 무관하게 raw하게 엣지를 모두 만들어둔다.
         * 양방향 통합(주인 방향으로 1개 엣지)은 이후 후처리에서 수행한다.
         */
        private final class JpaFieldVisitor extends FieldVisitor {
            private final String fieldName;
            private final String fieldJavaType;  // 사람이 읽는 타입명 (e.g. "java.util.List")
            private final String rawTypeFqn;     // 필드 raw 타입 (e.g. java.util.List, com.foo.User)
            private final String elementTypeFqn; // 제네릭 첫 인자 (List<User>의 User), 없으면 null
            private final int access;

            private Relation jpaRelation;
            private String mappedBy;
            private String targetEntity;
            private String joinTableName;

            // 컬럼 메타데이터
            private boolean primaryKey;
            private boolean isTransient;
            private String columnName;
            private boolean nullable = true;
            private boolean unique = false;
            private Integer length;
            private String generatedValue;

            JpaFieldVisitor(String fieldName, String fieldJavaType, String rawTypeFqn,
                            String elementTypeFqn, int access) {
                super(ASM_API);
                this.fieldName = fieldName;
                this.fieldJavaType = fieldJavaType;
                this.rawTypeFqn = rawTypeFqn;
                this.elementTypeFqn = elementTypeFqn;
                this.access = access;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                String annFqn = fqn(Type.getType(descriptor).getInternalName());

                Relation r = jpaRelationOf(annFqn);
                if (r != null) {
                    jpaRelation = r;
                    return new AnnotationVisitor(ASM_API) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("mappedBy".equals(name) && value instanceof String s && !s.isEmpty()) {
                                mappedBy = s;
                            } else if ("targetEntity".equals(name) && value instanceof Type t
                                    && t.getSort() == Type.OBJECT) {
                                String fq = fqn(t.getInternalName());
                                if (!"java.lang.Void".equals(fq)) targetEntity = fq;
                            }
                        }
                    };
                }
                if (isJoinTable(annFqn)) {
                    return new AnnotationVisitor(ASM_API) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("name".equals(name) && value instanceof String s && !s.isEmpty()) {
                                joinTableName = s;
                            }
                        }
                    };
                }
                if (isIdAnnotation(annFqn)) {
                    primaryKey = true;
                    return null;
                }
                if (isTransientAnnotation(annFqn)) {
                    isTransient = true;
                    return null;
                }
                if (isColumnAnnotation(annFqn)) {
                    return new AnnotationVisitor(ASM_API) {
                        @Override
                        public void visit(String name, Object value) {
                            switch (name) {
                                case "name" -> { if (value instanceof String s && !s.isEmpty()) columnName = s; }
                                case "nullable" -> { if (value instanceof Boolean b) nullable = b; }
                                case "unique" -> { if (value instanceof Boolean b) unique = b; }
                                case "length" -> { if (value instanceof Integer i) length = i; }
                                default -> { /* ignore */ }
                            }
                        }
                    };
                }
                if (isGeneratedValueAnnotation(annFqn)) {
                    return new AnnotationVisitor(ASM_API) {
                        @Override
                        public void visitEnum(String name, String desc, String value) {
                            if ("strategy".equals(name)) generatedValue = value;
                        }
                    };
                }
                return null;
            }

            @Override
            public void visitEnd() {
                // 1) JPA 관계 엣지 발생
                if (jpaRelation != null) {
                    String target = targetEntity != null ? targetEntity
                            : isCollectionRelation(jpaRelation) && elementTypeFqn != null ? elementTypeFqn
                            : rawTypeFqn;
                    if (target != null && keep(target)) {
                        index.addEdge(thisFqn, target, jpaRelation,
                                buildJpaLabel(fieldName, mappedBy, joinTableName));
                    }
                }

                // 2) ERD 컬럼 등록 — Entity/MappedSuperclass/Embeddable 클래스의 인스턴스 필드만.
                //    @Transient, static, 연관관계(@OneToMany 등) 필드는 컬럼이 아니다.
                if (entityKind == null) return;
                if (isTransient || jpaRelation != null) return;
                if ((access & Opcodes.ACC_STATIC) != 0) return;
                columns.add(new ColumnInfo(
                        fieldName,
                        columnName,
                        fieldJavaType,
                        primaryKey,
                        nullable,
                        unique,
                        length,
                        generatedValue
                ));
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (thisFqn == null) return null;
            Type m = Type.getMethodType(descriptor);
            for (Type p : m.getArgumentTypes()) {
                for (String t : referencedTypes(p)) {
                    if (keep(t)) index.addEdge(thisFqn, t, Relation.PARAM);
                }
            }
            for (String t : referencedTypes(m.getReturnType())) {
                if (keep(t)) index.addEdge(thisFqn, t, Relation.RETURNS);
            }
            // <clinit> / 합성/브릿지 메서드는 사람이 보기엔 노이즈라 MethodInfo 등록 제외 (엣지는 계속 수집)
            boolean noise = "<clinit>".equals(name)
                    || (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
            return new MethodBodyVisitor(noise ? null : name, descriptor, access);
        }

        @Override
        public void visitEnd() {
            if (thisFqn == null) return;
            EntityInfo entity = entityKind == null
                    ? null
                    : new EntityInfo(entityKind, entityTable, List.copyOf(columns));
            index.putNode(new Node(
                    thisFqn,
                    simple(thisFqn),
                    pkg(thisFqn),
                    kind,
                    new ArrayList<>(new LinkedHashSet<>(stereotypes)),
                    List.copyOf(methods),
                    List.copyOf(fields),
                    sourceFile,
                    entity
            ));
        }

        /**
         * 메서드 본문: INVOKE* / NEW 명령에서 호출/생성된 타입 수집.
         * methodName 이 null 이면 noise 메서드라 MethodInfo 는 등록하지 않고 엣지만 추가.
         */
        private final class MethodBodyVisitor extends MethodVisitor {
            private final String methodName;       // null 이면 MethodInfo 미등록
            private final String methodDescriptor;
            private final int methodAccess;
            private final Set<String> usedTypes = new LinkedHashSet<>();
            private final List<CallSite> calls = new ArrayList<>();
            private int currentLine = -1;
            private int startLine = -1; // 메서드 첫 라인

            MethodBodyVisitor(String methodName, String methodDescriptor, int methodAccess) {
                super(ASM_API);
                this.methodName = methodName;
                this.methodDescriptor = methodDescriptor;
                this.methodAccess = methodAccess;
            }

            @Override
            public void visitLineNumber(int line, org.objectweb.asm.Label start) {
                this.currentLine = line;
                if (this.startLine < 0 || line < this.startLine) this.startLine = line;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                        String descriptor, boolean isInterface) {
                String t = fqn(owner);
                if (t != null && keep(t)) {
                    index.addEdge(thisFqn, t, Relation.CALLS);
                    usedTypes.add(t);
                }
                // CallSite 는 외부 클래스 (keep=false) 도 기록 — 호출 흐름 시각화는 외부 호출도 보여줘야 함
                if (t != null && methodName != null) {
                    calls.add(new CallSite(calls.size(), t, name, descriptor, invokeKind(opcode), currentLine));
                }
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (opcode == Opcodes.NEW) {
                    String t = fqn(type);
                    if (t != null && keep(t)) {
                        index.addEdge(thisFqn, t, Relation.NEW);
                        usedTypes.add(t);
                    }
                }
            }

            @Override
            public void visitEnd() {
                if (methodName == null) return; // noise 메서드: MethodInfo 등록 생략
                Type m = Type.getMethodType(methodDescriptor);
                List<String> paramTypes = new ArrayList<>(m.getArgumentTypes().length);
                for (Type p : m.getArgumentTypes()) paramTypes.add(p.getClassName());
                methods.add(new MethodInfo(
                        methodName,
                        methodDescriptor,
                        m.getReturnType().getClassName(),
                        paramTypes,
                        modifiers(methodAccess),
                        List.copyOf(usedTypes),
                        List.copyOf(calls),
                        startLine
                ));
            }
        }
    }

    /** Type 에서 참조하는 클래스 FQN 들을 추출 (배열 -> 원소, primitive -> 무시) */
    private static Set<String> referencedTypes(Type t) {
        Set<String> out = new LinkedHashSet<>();
        collect(t, out);
        return out;
    }

    private static void collect(Type t, Set<String> out) {
        switch (t.getSort()) {
            case Type.OBJECT -> out.add(t.getClassName());
            case Type.ARRAY -> collect(t.getElementType(), out);
            default -> { /* primitive / void: 무시 */ }
        }
    }

    /** INVOKE* opcode -> 사람이 읽기 좋은 호출 종류 */
    private static String invokeKind(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "virtual";
            case Opcodes.INVOKESTATIC -> "static";
            case Opcodes.INVOKEINTERFACE -> "interface";
            case Opcodes.INVOKESPECIAL -> "special"; // 생성자 / private / super
            case Opcodes.INVOKEDYNAMIC -> "dynamic"; // 람다, indy 등
            default -> "unknown";
        };
    }

    /** JPA 관계 어노테이션 FQN을 Relation 값으로 매핑. jakarta / javax 둘 다 지원. */
    private static Relation jpaRelationOf(String annFqn) {
        return switch (annFqn) {
            case "jakarta.persistence.OneToMany", "javax.persistence.OneToMany" -> Relation.ONE_TO_MANY;
            case "jakarta.persistence.ManyToOne", "javax.persistence.ManyToOne" -> Relation.MANY_TO_ONE;
            case "jakarta.persistence.OneToOne", "javax.persistence.OneToOne" -> Relation.ONE_TO_ONE;
            case "jakarta.persistence.ManyToMany", "javax.persistence.ManyToMany" -> Relation.MANY_TO_MANY;
            default -> null;
        };
    }

    private static boolean isJoinTable(String annFqn) {
        return "jakarta.persistence.JoinTable".equals(annFqn)
                || "javax.persistence.JoinTable".equals(annFqn);
    }

    /** ERD 노드로 다룰 JPA 클래스 분류. */
    private static String entityKindOf(String annFqn) {
        return switch (annFqn) {
            case "jakarta.persistence.Entity", "javax.persistence.Entity" -> "entity";
            case "jakarta.persistence.MappedSuperclass", "javax.persistence.MappedSuperclass" -> "mappedSuperclass";
            case "jakarta.persistence.Embeddable", "javax.persistence.Embeddable" -> "embeddable";
            default -> null;
        };
    }

    private static boolean isTableAnnotation(String annFqn) {
        return "jakarta.persistence.Table".equals(annFqn) || "javax.persistence.Table".equals(annFqn);
    }

    private static boolean isIdAnnotation(String annFqn) {
        return "jakarta.persistence.Id".equals(annFqn) || "javax.persistence.Id".equals(annFqn);
    }

    private static boolean isColumnAnnotation(String annFqn) {
        return "jakarta.persistence.Column".equals(annFqn) || "javax.persistence.Column".equals(annFqn);
    }

    private static boolean isGeneratedValueAnnotation(String annFqn) {
        return "jakarta.persistence.GeneratedValue".equals(annFqn)
                || "javax.persistence.GeneratedValue".equals(annFqn);
    }

    private static boolean isTransientAnnotation(String annFqn) {
        return "jakarta.persistence.Transient".equals(annFqn)
                || "javax.persistence.Transient".equals(annFqn);
    }

    private static final Set<String> SPRING_DATA_REPOSITORIES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.reactive.ReactiveCrudRepository",
            "org.springframework.data.repository.reactive.ReactiveSortingRepository",
            "org.springframework.data.repository.reactive.RxJava3CrudRepository",
            "org.springframework.data.repository.reactive.RxJava3SortingRepository",
            "org.springframework.data.mongodb.repository.MongoRepository",
            "org.springframework.data.mongodb.repository.ReactiveMongoRepository",
            "org.springframework.data.repository.kotlin.CoroutineCrudRepository",
            "org.springframework.data.repository.kotlin.CoroutineSortingRepository"
    );

    private static boolean isSpringDataRepository(String fqn) {
        return SPRING_DATA_REPOSITORIES.contains(fqn);
    }

    private static boolean isCollectionRelation(Relation r) {
        return r == Relation.ONE_TO_MANY || r == Relation.MANY_TO_MANY;
    }

    /** ERD 엣지에 부착할 라벨 — 필드명, mappedBy, join table 정보를 결합. */
    private static String buildJpaLabel(String fieldName, String mappedBy, String joinTable) {
        StringBuilder sb = new StringBuilder(fieldName);
        if (mappedBy != null) sb.append(" (mappedBy=").append(mappedBy).append(')');
        if (joinTable != null) sb.append(" (join: ").append(joinTable).append(')');
        return sb.toString();
    }

    /**
     * 필드/메서드 signature에서 첫 번째 제네릭 타입 인자의 internal name을 반환한다.
     * 예) "Ljava/util/List&lt;Lcom/foo/User;&gt;;" → "com/foo/User"
     * signature가 null이거나 제네릭이 없으면 null.
     */
    static String firstTypeArgument(String signature) {
        if (signature == null) return null;
        String[] holder = new String[1];
        new SignatureReader(signature).acceptType(new SignatureVisitor(ASM_API) {
            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                if (holder[0] != null) return new SignatureVisitor(ASM_API) {};
                return new SignatureVisitor(ASM_API) {
                    @Override
                    public void visitClassType(String name) {
                        if (holder[0] == null) holder[0] = name;
                    }
                };
            }
        });
        return holder[0];
    }

    /** ASM access 플래그 -> ["public", "static", ...] */
    private static List<String> modifiers(int access) {
        List<String> out = new ArrayList<>(4);
        if ((access & Opcodes.ACC_PUBLIC) != 0) out.add("public");
        if ((access & Opcodes.ACC_PROTECTED) != 0) out.add("protected");
        if ((access & Opcodes.ACC_PRIVATE) != 0) out.add("private");
        if ((access & Opcodes.ACC_STATIC) != 0) out.add("static");
        if ((access & Opcodes.ACC_FINAL) != 0) out.add("final");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) out.add("abstract");
        return out;
    }
}
