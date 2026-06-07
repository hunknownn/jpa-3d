package com.example.shop.review;

import com.example.shop.catalog.Product;
import com.example.shop.common.AuditableEntity;
import com.example.shop.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "reviews",
        indexes = @Index(name = "idx_reviews_rating", columnList = "rating"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reviews_author_product",
                columnNames = {"author_id", "product_id"}
        )
)
public class Review extends AuditableEntity {

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "comment", length = 2000)
    private String comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public User getAuthor() {
        return author;
    }

    public Product getProduct() {
        return product;
    }
}
