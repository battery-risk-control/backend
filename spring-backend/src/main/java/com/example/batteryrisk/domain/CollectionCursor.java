package com.example.batteryrisk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "collection_cursors")
public class CollectionCursor {
    @Id
    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "cursor_value", length = 200)
    private String cursorValue;

    protected CollectionCursor() {}

    public CollectionCursor(String source) {
        this.source = source;
    }

    public void markSuccess(String newCursorValue) {
        this.lastSuccessAt = Instant.now();
        if (newCursorValue != null) {
            this.cursorValue = newCursorValue;
        }
    }

    public String getSource() { return source; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public String getCursorValue() { return cursorValue; }
}
