"""Tests for DJManagerMixin — pure logic functions (no WebSocket)."""

from vj_server.models import ConnectCode, DJConnection

# ============================================================================
# DJManagerMixin helpers that can be tested without a full server
# ============================================================================


class FakeDJManager:
    """Minimal stub exposing DJManagerMixin methods for testing.

    Only includes the attributes that the testable methods reference.
    """

    def __init__(self):
        self._djs = {}
        self._active_dj_id = None
        self._dj_queue = []
        self._dj_presets = {}
        self._connect_codes = {}
        self._auth_attempts = {}
        self._auth_last_cleanup = 0.0
        self._auth_rate_limit_window = 60.0
        self._auth_rate_limit_max = 10

    # Import methods from the mixin
    from vj_server.dj_manager import DJManagerMixin

    active_dj = property(DJManagerMixin.active_dj.fget)
    _get_active_dj = DJManagerMixin._get_active_dj
    _dj_profile_dict = DJManagerMixin._dj_profile_dict
    _get_dj_roster = DJManagerMixin._get_dj_roster
    _check_auth_rate_limit = DJManagerMixin._check_auth_rate_limit
    _cleanup_expired_codes = DJManagerMixin._cleanup_expired_codes


# ============================================================================
# active_dj property
# ============================================================================


class TestActiveDJ:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_no_active_dj(self):
        assert self.mgr.active_dj is None

    def test_active_dj_exists(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None)
        self.mgr._djs["dj1"] = dj
        self.mgr._active_dj_id = "dj1"
        assert self.mgr.active_dj is dj

    def test_active_dj_id_stale(self):
        """If active_dj_id points to a disconnected DJ, return None."""
        self.mgr._active_dj_id = "gone"
        assert self.mgr.active_dj is None


# ============================================================================
# _dj_profile_dict
# ============================================================================


class TestDJProfileDict:
    def test_basic_profile(self):
        mgr = FakeDJManager()
        dj = DJConnection(
            dj_id="dj1",
            dj_name="Cool DJ",
            websocket=None,
            avatar_url="https://example.com/avatar.png",
            slug="cool-dj",
            bio="I play music",
            genres="EDM, House",
            color_palette=["#ff0000", "#00ff00"],
            block_palette=["DIAMOND_BLOCK"],
        )
        result = mgr._dj_profile_dict(dj)
        assert result["dj_id"] == "dj1"
        assert result["dj_name"] == "Cool DJ"
        assert result["avatar_url"] == "https://example.com/avatar.png"
        assert result["slug"] == "cool-dj"
        assert result["color_palette"] == ["#ff0000", "#00ff00"]

    def test_profile_with_none_fields(self):
        mgr = FakeDJManager()
        dj = DJConnection(dj_id="dj1", dj_name="DJ", websocket=None)
        result = mgr._dj_profile_dict(dj)
        assert result["avatar_url"] is None
        assert result["slug"] is None


# ============================================================================
# _get_dj_roster
# ============================================================================


class TestGetDJRoster:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_empty_roster(self):
        assert self.mgr._get_dj_roster() == []

    def test_single_dj(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None, bpm=128.0)
        self.mgr._djs["dj1"] = dj
        self.mgr._dj_queue = ["dj1"]
        self.mgr._active_dj_id = "dj1"

        roster = self.mgr._get_dj_roster()
        assert len(roster) == 1
        assert roster[0]["dj_id"] == "dj1"
        assert roster[0]["is_active"] is True
        assert roster[0]["bpm"] == 128.0

    def test_roster_sorted_by_queue_position(self):
        for i in range(3):
            dj = DJConnection(dj_id=f"dj{i}", dj_name=f"DJ {i}", websocket=None)
            self.mgr._djs[f"dj{i}"] = dj
        self.mgr._dj_queue = ["dj2", "dj0", "dj1"]

        roster = self.mgr._get_dj_roster()
        assert [r["dj_id"] for r in roster] == ["dj2", "dj0", "dj1"]

    def test_inactive_dj_marked(self):
        dj = DJConnection(dj_id="dj1", dj_name="DJ One", websocket=None)
        self.mgr._djs["dj1"] = dj
        self.mgr._dj_queue = ["dj1"]
        self.mgr._active_dj_id = "dj_other"

        roster = self.mgr._get_dj_roster()
        assert roster[0]["is_active"] is False


# ============================================================================
# _check_auth_rate_limit
# ============================================================================


class TestAuthRateLimit:
    def setup_method(self):
        self.mgr = FakeDJManager()
        self.mgr._auth_rate_limit_max = 3
        self.mgr._auth_rate_limit_window = 60.0

    def test_allows_under_limit(self):
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is False
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is False

    def test_blocks_at_limit(self):
        for _ in range(3):
            self.mgr._check_auth_rate_limit("1.2.3.4")
        assert self.mgr._check_auth_rate_limit("1.2.3.4") is True

    def test_different_ips_independent(self):
        for _ in range(3):
            self.mgr._check_auth_rate_limit("1.1.1.1")
        # Different IP should not be limited
        assert self.mgr._check_auth_rate_limit("2.2.2.2") is False

    def test_cleanup_stale_entries(self):
        self.mgr._auth_attempts = {f"10.0.0.{i}": [0.0] for i in range(60)}
        self.mgr._auth_last_cleanup = 0.0
        # Trigger cleanup by exceeding 50 entries
        self.mgr._check_auth_rate_limit("new_ip")
        # Stale entries should be cleaned
        assert len(self.mgr._auth_attempts) < 60


# ============================================================================
# _cleanup_expired_codes
# ============================================================================


class TestCleanupExpiredCodes:
    def setup_method(self):
        self.mgr = FakeDJManager()

    def test_removes_expired(self):
        expired = ConnectCode(code="TEST-AAAA", created_at=0.0, expires_at=1.0)
        self.mgr._connect_codes["TEST-AAAA"] = expired
        self.mgr._cleanup_expired_codes()
        assert "TEST-AAAA" not in self.mgr._connect_codes

    def test_removes_used(self):
        used = ConnectCode.generate()
        used.used = True
        self.mgr._connect_codes[used.code] = used
        self.mgr._cleanup_expired_codes()
        assert used.code not in self.mgr._connect_codes

    def test_keeps_valid(self):
        valid = ConnectCode.generate()
        self.mgr._connect_codes[valid.code] = valid
        self.mgr._cleanup_expired_codes()
        assert valid.code in self.mgr._connect_codes
