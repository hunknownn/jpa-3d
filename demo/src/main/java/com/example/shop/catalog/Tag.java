package com.example.shop.catalog;

import com.example.shop.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "tags",
        uniqueConstraints = @UniqueConstraint(name = "uq_tags_name", columnNames = "name")
)
public class Tag extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 40)
    private String name;

    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    private Set<Product> products = new HashSet<>();

    public String getName() {
        return name;
    }

    public Set<Product> getProducts() {
        return products;
    }
}
