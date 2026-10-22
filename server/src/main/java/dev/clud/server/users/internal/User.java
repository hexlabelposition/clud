package dev.clud.server.users.internal;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import dev.clud.server.users.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "users")
class User {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@NotNull
	@Size(max = 64)
	@Pattern(regexp = "[A-Za-z0-9._-]+")
	@Column(nullable = false, length = 64)
	private String username;

	@Email
	@Size(max = 254)
	@Column(length = 254)
	private String email;

	@NotBlank
	@Size(max = 255)
	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Role role;

	// Null means unlimited; zero means no storage is allowed.
	@PositiveOrZero
	@Column(name = "quota_bytes")
	private Long quotaBytes;

	@Column(nullable = false)
	private boolean disabled;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected User() {
	}

	User(String username, String email, String passwordHash, Role role, Long quotaBytes) {
		this.username = username;
		this.email = normalizeEmail(email);
		this.passwordHash = passwordHash;
		this.role = role;
		this.quotaBytes = quotaBytes;
	}

	// Emails are stored trimmed and lowercased; a blank email means none.
	private static String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		return email.strip().toLowerCase(Locale.ROOT);
	}

	UUID getId() {
		return id;
	}

	String getUsername() {
		return username;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	Role getRole() {
		return role;
	}

	Long getQuotaBytes() {
		return quotaBytes;
	}

	boolean isDisabled() {
		return disabled;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}
