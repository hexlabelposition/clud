package dev.clud.server.users.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface UserRepository extends JpaRepository<User, UUID> {

	// Must match the lower(username) unique index; derived IgnoreCase queries use upper().
	@Query("select u from User u where lower(u.username) = lower(:username)")
	Optional<User> findByUsernameIgnoreCase(String username);

}
