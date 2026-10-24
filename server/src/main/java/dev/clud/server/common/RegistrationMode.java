package dev.clud.server.common;

/** Instance registration policy; invite tokens are handled by the auth module. */
public enum RegistrationMode {
	INVITE, OPEN, CLOSED
}
