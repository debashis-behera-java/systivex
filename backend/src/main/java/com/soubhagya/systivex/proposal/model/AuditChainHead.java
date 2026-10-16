package com.soubhagya.systivex.proposal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single-row head of the governance audit chain (Phase 11 S1, D29).
 *
 * <p>Exactly one row exists ({@code id = 1}, seeded by the V2 migration
 * with {@code last_seq = 0} and the {@code GENESIS} marker). Later slices
 * allocate each chain sequence number under a pessimistic write lock on
 * this row; no repository method here performs that locking — see the
 * repository finder. No lifecycle logic lives here.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    public static final int HEAD_ID = 1;

    public static final String GENESIS_HASH = "GENESIS";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private int id = HEAD_ID;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", length = 64, nullable = false)
    private String lastHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuditChainHead() {}

    public AuditChainHead(long lastSeq, String lastHash) {
        this.lastSeq = lastSeq;
        this.lastHash = lastHash;
        this.updatedAt = Instant.now();
    }

    public int getId() {
        return id;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
        this.updatedAt = Instant.now();
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
