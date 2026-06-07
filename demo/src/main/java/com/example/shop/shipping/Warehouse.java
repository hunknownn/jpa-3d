package com.example.shop.shipping;

import com.example.shop.common.Address;
import com.example.shop.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "warehouses",
        uniqueConstraints = @UniqueConstraint(name = "uq_warehouses_code", columnNames = "code")
)
public class Warehouse extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Embedded
    private Address location;

    @OneToMany(mappedBy = "warehouse", fetch = FetchType.LAZY)
    private Set<Inventory> inventory = new HashSet<>();

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Address getLocation() {
        return location;
    }

    public Set<Inventory> getInventory() {
        return inventory;
    }
}
