"""Domain-specific exceptions for the service layer.

These exceptions carry business-logic semantics and are translated into
HTTP responses (e.g., ``HTTPException``) by the router layer.
"""


class AuthenticationError(Exception):
    """Raised when API-key or credential verification fails."""


class ServerNotFoundError(Exception):
    """Raised when a requested VJ server does not exist or is inactive."""


class ShowNotFoundError(Exception):
    """Raised when a requested show does not exist."""


class ShowAlreadyEndedError(Exception):
    """Raised when attempting to end a show that is already ended."""


class ShowFullError(Exception):
    """Raised when a show has reached its maximum DJ capacity."""


class ServerOfflineError(Exception):
    """Raised when the owning VJ server is inactive/offline."""


class ConnectCodeNotFoundError(Exception):
    """Raised when a connect code does not resolve to an active show."""


class OwnershipError(Exception):
    """Raised when a server does not own the requested resource."""


class SessionNotFoundError(Exception):
    """Raised when a DJ session does not exist or is already disconnected."""
