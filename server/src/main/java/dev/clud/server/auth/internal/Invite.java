package dev.clud.server.auth.internal;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "invites")
class Invite {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_by")
	private UUID usedBy;

	@Column(name = "used_at")
	private Instant usedAt;

	protected Invite() {
	}

	Invite(String tokenHash, UUID createdBy, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.createdBy = createdBy;
		this.expiresAt = expiresAt;
	}

	UUID getId() {
		return id;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	boolean isUsable(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	void use(UUID userId, Instant now) {
		usedBy = userId;
		usedAt = now;
	}

}
