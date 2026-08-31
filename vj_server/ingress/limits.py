"""Immutable public-ingress resource limits."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class RouteLimits:
    max_connections: int
    max_message_bytes: int
    messages_per_second: int
    max_auth_attempts: int
    idle_timeout_seconds: float
    lifetime_seconds: float

    def __post_init__(self) -> None:
        values = (
            self.max_connections,
            self.max_message_bytes,
            self.messages_per_second,
            self.max_auth_attempts,
        )
        if any(value <= 0 for value in values):
            raise ValueError("route integer limits must be positive")
        if self.idle_timeout_seconds <= 0 or self.lifetime_seconds <= 0:
            raise ValueError("route timeout limits must be positive")
        if self.idle_timeout_seconds > self.lifetime_seconds:
            raise ValueError("route idle timeout cannot exceed lifetime")


@dataclass(frozen=True, slots=True)
class IngressLimits:
    max_connections: int = 256
    max_request_body_bytes: int = 65_536
    max_static_asset_bytes: int = 8 * 1024 * 1024
    initial_header_timeout_seconds: float = 5.0
    tls_handshake_timeout_seconds: float = 5.0
    keepalive_timeout_seconds: float = 10.0
    max_request_line_bytes: int = 4_096
    max_header_field_bytes: int = 512
    max_header_count: int = 32
    max_header_bytes: int = 16_384
    admin: RouteLimits = field(
        default_factory=lambda: RouteLimits(8, 262_144, 60, 5, 120.0, 43_200.0)
    )
    preview: RouteLimits = field(
        default_factory=lambda: RouteLimits(64, 65_536, 30, 5, 60.0, 14_400.0)
    )
    dj: RouteLimits = field(default_factory=lambda: RouteLimits(64, 65_536, 120, 5, 30.0, 43_200.0))

    def __post_init__(self) -> None:
        if self.max_connections <= 0 or self.max_connections > 10_000:
            raise ValueError("max_connections is outside the supported range")
        if self.max_request_body_bytes <= 0 or self.max_request_body_bytes > 16 * 1024 * 1024:
            raise ValueError("max_request_body_bytes is outside the supported range")
        if self.max_static_asset_bytes <= 0 or self.max_static_asset_bytes > 64 * 1024 * 1024:
            raise ValueError("max_static_asset_bytes is outside the supported range")
        if (
            self.initial_header_timeout_seconds <= 0
            or self.tls_handshake_timeout_seconds <= 0
            or self.keepalive_timeout_seconds <= 0
        ):
            raise ValueError("ingress transport timeouts must be positive")
        parser_limits = (
            self.max_request_line_bytes,
            self.max_header_field_bytes,
            self.max_header_bytes,
        )
        if any(value <= 0 or value > 65_536 for value in parser_limits):
            raise ValueError("ingress parser limits are outside the supported range")
        if self.max_header_count <= 0 or self.max_header_count > 128:
            raise ValueError("max_header_count is outside the supported range")
        if self.max_header_count * self.max_header_field_bytes > self.max_header_bytes:
            raise ValueError("header count and field size exceed the aggregate byte limit")
