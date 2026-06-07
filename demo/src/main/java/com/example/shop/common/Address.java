package com.example.shop.common;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 여러 엔티티(User, Warehouse, Shipment 등)가 @Embedded 로 재사용하는 값 타입.
 */
@Embeddable
public class Address {

    @Column(name = "street", length = 200)
    private String street;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "zip_code", length = 20)
    private String zipCode;

    @Column(name = "country", length = 2)
    private String country;

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getZipCode() {
        return zipCode;
    }

    public String getCountry() {
        return country;
    }
}
