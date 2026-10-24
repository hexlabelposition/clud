package dev.clud.server.auth.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

interface InviteRepository extends JpaRepository<Invite, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Invite> findByTokenHash(String tokenHash);

}
