"""Unit tests for app.services.org_service.

Tests organization CRUD, membership, invites, and server assignment
at the service layer (direct DB, no HTTP).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db import Organization, OrgInvite, OrgMember, User, VJServer
from app.services.org_service import (
    assign_server_to_org,
    check_slug_available,
    create_invite,
    create_org,
    deactivate_invite,
    find_active_invite_by_code,
    get_membership,
    get_org_by_id,
    get_org_by_slug,
    get_org_for_invite,
    join_org_with_invite,
    list_active_invites,
    list_org_servers,
    remove_server_from_org,
    update_org,
)
from app.services.password import hash_password


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _create_user(
    session: AsyncSession,
    *,
    email: str = "orgowner@example.com",
    display_name: str = "OrgOwner",
) -> User:
    user = User(
        id=uuid.uuid4(),
        email=email,
        password_hash=hash_password("Testpass123"),
        display_name=display_name,
        email_verified=True,
        is_active=True,
    )
    session.add(user)
    await session.flush()
    return user


async def _create_server(
    session: AsyncSession,
    *,
    name: str = "Test Server",
    org_id: uuid.UUID | None = None,
) -> VJServer:
    server = VJServer(
        id=uuid.uuid4(),
        name=name,
        websocket_url="ws://localhost:9000",
        api_key_hash=hash_password("test-key"),
        jwt_secret="jwt-secret-placeholder",
        org_id=org_id,
    )
    session.add(server)
    await session.flush()
    return server


# ---------------------------------------------------------------------------
# get_org_by_slug / get_org_by_id
# ---------------------------------------------------------------------------


class TestGetOrg:
    async def test_get_by_slug(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="My Org", slug="my-org", owner_id=owner.id)
        found = await get_org_by_slug(db_session, "my-org")
        assert found is not None
        assert found.id == org.id

    async def test_get_by_slug_case_insensitive(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        await create_org(db_session, name="Case Org", slug="case-org", owner_id=owner.id)
        found = await get_org_by_slug(db_session, "CASE-ORG")
        assert found is not None

    async def test_get_by_slug_not_found(self, db_session: AsyncSession) -> None:
        found = await get_org_by_slug(db_session, "nonexistent")
        assert found is None

    async def test_get_by_id(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="ID Org", slug="id-org", owner_id=owner.id)
        found = await get_org_by_id(db_session, org.id)
        assert found is not None
        assert found.slug == "id-org"

    async def test_get_by_id_inactive_filtered(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Inactive", slug="inactive-org", owner_id=owner.id)
        org.is_active = False
        await db_session.flush()
        # active_only=True (default) should not find it
        assert await get_org_by_id(db_session, org.id) is None
        # active_only=False should find it
        assert await get_org_by_id(db_session, org.id, active_only=False) is not None


# ---------------------------------------------------------------------------
# create_org / check_slug_available
# ---------------------------------------------------------------------------


class TestCreateOrg:
    async def test_create_org_and_owner_membership(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(
            db_session,
            name="New Org",
            slug="new-org",
            owner_id=owner.id,
            description="A test org",
        )
        assert org.name == "New Org"
        assert org.slug == "new-org"
        assert org.description == "A test org"
        # Owner should have a membership
        membership = await get_membership(db_session, owner.id, org.id)
        assert membership is not None
        assert membership.role == "owner"

    async def test_slug_available_when_unused(self, db_session: AsyncSession) -> None:
        assert await check_slug_available(db_session, "fresh-slug") is True

    async def test_slug_unavailable_when_taken(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        await create_org(db_session, name="Taken", slug="taken-slug", owner_id=owner.id)
        assert await check_slug_available(db_session, "taken-slug") is False


# ---------------------------------------------------------------------------
# update_org
# ---------------------------------------------------------------------------


class TestUpdateOrg:
    async def test_update_name_and_description(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Old Name", slug="update-org", owner_id=owner.id)
        updated = await update_org(
            db_session, org, name="New Name", description="Updated desc"
        )
        assert updated.name == "New Name"
        assert updated.description == "Updated desc"

    async def test_update_avatar_url(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Avatar Org", slug="avatar-org", owner_id=owner.id)
        updated = await update_org(
            db_session, org, avatar_url="https://example.com/avatar.png"
        )
        assert updated.avatar_url == "https://example.com/avatar.png"

    async def test_partial_update_preserves_other_fields(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(
            db_session, name="Partial", slug="partial-org", owner_id=owner.id, description="orig"
        )
        await update_org(db_session, org, name="Changed")
        assert org.name == "Changed"
        assert org.description == "orig"  # unchanged


# ---------------------------------------------------------------------------
# Server assignment
# ---------------------------------------------------------------------------


class TestServerAssignment:
    async def test_assign_server_to_org(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="S Org", slug="s-org", owner_id=owner.id)
        server = await _create_server(db_session)
        result = await assign_server_to_org(db_session, server.id, org.id)
        assert result is not None
        assert result.org_id == org.id

    async def test_assign_nonexistent_server_returns_none(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="NoSrv", slug="nosrv-org", owner_id=owner.id)
        result = await assign_server_to_org(db_session, uuid.uuid4(), org.id)
        assert result is None

    async def test_list_org_servers(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="List Org", slug="list-org", owner_id=owner.id)
        s1 = await _create_server(db_session, name="Server 1", org_id=org.id)
        s2 = await _create_server(db_session, name="Server 2", org_id=org.id)
        servers = await list_org_servers(db_session, org.id)
        assert len(servers) == 2
        server_ids = {s.id for s in servers}
        assert s1.id in server_ids
        assert s2.id in server_ids

    async def test_remove_server_from_org(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Rm Org", slug="rm-org", owner_id=owner.id)
        server = await _create_server(db_session, org_id=org.id)
        result = await remove_server_from_org(db_session, server.id, org.id)
        assert result is not None
        assert result.org_id is None

    async def test_remove_server_wrong_org_returns_none(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="WrongOrg", slug="wrong-org", owner_id=owner.id)
        server = await _create_server(db_session, org_id=org.id)
        result = await remove_server_from_org(db_session, server.id, uuid.uuid4())
        assert result is None


# ---------------------------------------------------------------------------
# Invites
# ---------------------------------------------------------------------------


class TestInvites:
    async def test_create_invite(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Inv Org", slug="inv-org", owner_id=owner.id)
        invite = await create_invite(
            db_session,
            org_id=org.id,
            code="ABCD1234",
            created_by=owner.id,
            expires_in_hours=24,
            max_uses=10,
        )
        assert invite.code == "ABCD1234"
        assert invite.max_uses == 10
        assert invite.expires_at is not None

    async def test_create_invite_no_expiry(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="NoExp", slug="noexp-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="NOEXP123", created_by=owner.id
        )
        assert invite.expires_at is None

    async def test_list_active_invites(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="List Inv", slug="listinv-org", owner_id=owner.id)
        await create_invite(db_session, org_id=org.id, code="LIST0001", created_by=owner.id)
        await create_invite(db_session, org_id=org.id, code="LIST0002", created_by=owner.id)
        invites = await list_active_invites(db_session, org.id)
        assert len(invites) == 2

    async def test_deactivate_invite(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="Deact", slug="deact-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="DEACT001", created_by=owner.id
        )
        result = await deactivate_invite(db_session, invite.id, org.id)
        assert result is not None
        assert result.is_active is False

    async def test_deactivate_invite_wrong_org_returns_none(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="WrongDeact", slug="wrongd-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="WRONGD01", created_by=owner.id
        )
        result = await deactivate_invite(db_session, invite.id, uuid.uuid4())
        assert result is None

    async def test_find_active_invite_by_code(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="FindInv", slug="findinv-org", owner_id=owner.id)
        await create_invite(db_session, org_id=org.id, code="FIND0001", created_by=owner.id)
        found = await find_active_invite_by_code(db_session, "FIND0001")
        assert found is not None
        assert found.code == "FIND0001"

    async def test_find_inactive_invite_returns_none(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session)
        org = await create_org(db_session, name="InactInv", slug="inactinv-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="INACT001", created_by=owner.id
        )
        await deactivate_invite(db_session, invite.id, org.id)
        found = await find_active_invite_by_code(db_session, "INACT001")
        assert found is None


# ---------------------------------------------------------------------------
# join_org_with_invite
# ---------------------------------------------------------------------------


class TestJoinOrgWithInvite:
    async def test_join_creates_membership_and_increments_use_count(
        self, db_session: AsyncSession
    ) -> None:
        owner = await _create_user(db_session, email="joinowner@example.com")
        org = await create_org(db_session, name="Join Org", slug="join-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="JOIN0001", created_by=owner.id
        )
        new_user = await _create_user(db_session, email="joiner@example.com", display_name="Joiner")
        membership = await join_org_with_invite(
            db_session, user_id=new_user.id, invite=invite
        )
        assert membership.role == "member"
        assert membership.org_id == org.id

        # Refresh invite to check use_count
        await db_session.refresh(invite)
        assert invite.use_count == 1

    async def test_second_join_increments_use_count(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session, email="joinowner2@example.com")
        org = await create_org(db_session, name="Join2", slug="join2-org", owner_id=owner.id)
        invite = await create_invite(
            db_session, org_id=org.id, code="JOIN0002", created_by=owner.id, max_uses=5
        )
        u1 = await _create_user(db_session, email="j1@example.com", display_name="J1")
        u2 = await _create_user(db_session, email="j2@example.com", display_name="J2")
        await join_org_with_invite(db_session, user_id=u1.id, invite=invite)
        await join_org_with_invite(db_session, user_id=u2.id, invite=invite)
        await db_session.refresh(invite)
        assert invite.use_count == 2


# ---------------------------------------------------------------------------
# get_org_for_invite
# ---------------------------------------------------------------------------


class TestGetOrgForInvite:
    async def test_returns_org(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session, email="orgforinv@example.com")
        org = await create_org(
            db_session, name="InvOrg", slug="invorg-slug", owner_id=owner.id
        )
        found = await get_org_for_invite(db_session, org.id)
        assert found is not None
        assert found.id == org.id


# ---------------------------------------------------------------------------
# get_membership
# ---------------------------------------------------------------------------


class TestGetMembership:
    async def test_returns_membership_for_member(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session, email="memb@example.com")
        org = await create_org(db_session, name="Memb Org", slug="memb-org", owner_id=owner.id)
        membership = await get_membership(db_session, owner.id, org.id)
        assert membership is not None
        assert membership.role == "owner"

    async def test_returns_none_for_non_member(self, db_session: AsyncSession) -> None:
        owner = await _create_user(db_session, email="membowner@example.com")
        org = await create_org(db_session, name="No Memb", slug="nomemb-org", owner_id=owner.id)
        result = await get_membership(db_session, uuid.uuid4(), org.id)
        assert result is None
