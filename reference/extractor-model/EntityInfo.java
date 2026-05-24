package com.analyzer.extractor.model;

import java.util.List;

/**
 * JPA Entity / MappedSuperclass / Embeddable 클래스의 ERD 메타데이터.
 * Node.entity 에 부착되어 ERD 뷰에서 테이블 카드 형태로 렌더링된다.
 *
 * @param kind        "entity" / "mappedSuperclass" / "embeddable"
 * @param tableName   @Table.name. 미지정이면 null (뷰어에서 클래스 simple name 사용)
 * @param columns     필드별 컬럼 정보. 선언 순서 유지.
 */
public record EntityInfo(
        String kind,
        String tableName,
        List<ColumnInfo> columns
) {
}
