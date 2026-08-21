import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const schemaIndexPath = resolve(repositoryRoot, "protocol", "schemas", "index.json");

const handshakeSchemas = {
  connected: "messages/connected.schema.json",
  ws_auth: "messages/ws-auth.schema.json",
  ws_auth_ok: "messages/ws-auth-ok.schema.json",
};

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function readHandshakeSchema(inventoryKey) {
  return readJson(
    resolve(repositoryRoot, "protocol", "schemas", handshakeSchemas[inventoryKey]),
  );
}

function validationErrors(schema, value) {
  const errors = [];
  if (schema.type === "object") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
      return ["value must be an object"];
    }

    for (const requiredProperty of schema.required ?? []) {
      if (!Object.hasOwn(value, requiredProperty)) {
        errors.push(`missing required property: ${requiredProperty}`);
      }
    }

    for (const [propertyName, propertyValue] of Object.entries(value)) {
      const propertySchema = schema.properties?.[propertyName];
      if (propertySchema === undefined) {
        if (schema.additionalProperties === false) {
          errors.push(`unexpected property: ${propertyName}`);
        }
        continue;
      }

      if (Object.hasOwn(propertySchema, "const") && propertyValue !== propertySchema.const) {
        errors.push(`${propertyName} must equal ${propertySchema.const}`);
      }
      if (propertySchema.type === "string" && typeof propertyValue !== "string") {
        errors.push(`${propertyName} must be a string`);
      }
      if (propertySchema.type === "boolean" && typeof propertyValue !== "boolean") {
        errors.push(`${propertyName} must be a boolean`);
      }
      if (propertySchema.type === "integer" && !Number.isInteger(propertyValue)) {
        errors.push(`${propertyName} must be an integer`);
      }
      if (
        typeof propertyValue === "number" &&
        propertySchema.minimum !== undefined &&
        propertyValue < propertySchema.minimum
      ) {
        errors.push(`${propertyName} is less than ${propertySchema.minimum}`);
      }
      if (
        typeof propertyValue === "string" &&
        propertySchema.minLength !== undefined &&
        propertyValue.length < propertySchema.minLength
      ) {
        errors.push(`${propertyName} is shorter than ${propertySchema.minLength}`);
      }
      if (
        typeof propertyValue === "string" &&
        propertySchema.maxLength !== undefined &&
        propertyValue.length > propertySchema.maxLength
      ) {
        errors.push(`${propertyName} is longer than ${propertySchema.maxLength}`);
      }
      if (propertySchema.enum && !propertySchema.enum.includes(propertyValue)) {
        errors.push(`${propertyName} is not in the enum`);
      }
    }
  }
  return errors;
}

function assertValid(schema, message) {
  assert.deepEqual(validationErrors(schema, message), []);
}

function assertInvalid(schema, message) {
  assert.notDeepEqual(validationErrors(schema, message), []);
}

test("handshake schemas are inventoried at existing paths", () => {
  const inventory = readJson(schemaIndexPath);
  for (const [inventoryKey, relativePath] of Object.entries(handshakeSchemas)) {
    assert.equal(inventory.messages[inventoryKey], relativePath);
    assert.equal(
      existsSync(resolve(repositoryRoot, "protocol", "schemas", relativePath)),
      true,
    );
  }
});

test("all handshake schemas declare optional protocol versions", () => {
  for (const inventoryKey of Object.keys(handshakeSchemas)) {
    const schema = readHandshakeSchema(inventoryKey);
    assert.deepEqual(schema.properties.v, { type: "string" });
    assert.equal(schema.required.includes("v"), false);
  }
});

test("connected schema accepts versioned and unversioned contract messages", () => {
  const schema = readHandshakeSchema("connected");
  assert.equal(schema.additionalProperties, true);
  assertValid(schema, {
    type: "connected",
    auth_required: false,
    server_type: "paper",
    message: "ready",
    implementation_detail: "allowed",
  });
  assertValid(schema, {
    type: "connected",
    v: "1.0.0",
    auth_required: true,
    server_type: "fabric",
    version: "2.0.0",
  });

  assertInvalid(schema, {
    auth_required: false,
    server_type: "paper",
  });
  assertInvalid(schema, { type: "connected", server_type: "paper" });
  assertInvalid(schema, { type: "connected", auth_required: false });
  assertInvalid(schema, {
    type: "welcome",
    auth_required: false,
    server_type: "paper",
  });
  assertInvalid(schema, {
    type: "connected",
    auth_required: "false",
    server_type: "paper",
  });
  assertInvalid(schema, {
    type: "connected",
    auth_required: false,
    server_type: "forge",
  });
});

test("ws-auth schema enforces token bounds and a closed object shape", () => {
  const schema = readHandshakeSchema("ws_auth");
  assertValid(schema, { type: "auth", token: "x" });
  assertValid(schema, { type: "auth", v: "1.0.0", token: "x".repeat(1024) });

  assertInvalid(schema, { token: "secret" });
  assertInvalid(schema, { type: "auth" });
  assertInvalid(schema, { type: "ws_auth", token: "secret" });
  assertInvalid(schema, { type: "auth", token: 123 });
  assertInvalid(schema, { type: "auth", token: "" });
  assertInvalid(schema, { type: "auth", token: "x".repeat(1025) });
  assertInvalid(schema, { type: "auth", token: "secret", extra: true });
});

test("ws-auth-ok schema accepts only the versioned or unversioned acknowledgement", () => {
  const schema = readHandshakeSchema("ws_auth_ok");
  assertValid(schema, { type: "auth_ok" });
  assertValid(schema, { type: "auth_ok", v: "1.0.0" });

  assertInvalid(schema, {});
  assertInvalid(schema, { type: "auth" });
  assertInvalid(schema, { type: "auth_ok", v: 1 });
  assertInvalid(schema, { type: "auth_ok", accepted: true });
});

test("emergency state is inventoried and requires authoritative toggle values", () => {
  const inventory = readJson(schemaIndexPath);
  assert.equal(inventory.messages.emergency_state, "messages/emergency-state.schema.json");
  const schema = readJson(resolve(repositoryRoot, "protocol", "schemas", inventory.messages.emergency_state));

  assertValid(schema, {
    type: "emergency_state",
    blackout: true,
    freeze: false,
    emergency_epoch: "server-epoch-a",
    emergency_revision: 0,
  });
  assertValid(schema, {
    type: "emergency_state",
    blackout: false,
    freeze: true,
    request_id: "emergency-123",
    emergency_epoch: "server-epoch-a",
    emergency_revision: 12,
  });
  assertInvalid(schema, { type: "emergency_state", blackout: true, freeze: false });
  assertInvalid(schema, { type: "emergency_state", blackout: true });
  assertInvalid(schema, { type: "emergency_state", blackout: "true", freeze: false });
  assertInvalid(schema, {
    type: "emergency_state",
    blackout: true,
    freeze: false,
    emergency_epoch: "server-epoch-a",
    emergency_revision: -1,
  });

  const vjStateSchema = readJson(
    resolve(repositoryRoot, "protocol", "schemas", "messages", "vj-state.schema.json"),
  );
  assert.deepEqual(vjStateSchema.properties.emergency_revision, {
    type: "integer",
    minimum: 0,
  });
  assert.deepEqual(vjStateSchema.properties.emergency_epoch, {
    type: "string",
    minLength: 1,
    maxLength: 128,
  });
});

test("emergency command and error schemas accept request correlation", () => {
  for (const file of ["trigger-effect.schema.json", "blackout.schema.json", "freeze.schema.json"]) {
    const schema = readJson(resolve(repositoryRoot, "protocol", "schemas", "messages", file));
    assert.deepEqual(schema.properties.request_id, { type: "string", minLength: 1, maxLength: 128 });
  }
  const errorSchema = readJson(resolve(repositoryRoot, "protocol", "schemas", "messages", "error.schema.json"));
  assert.deepEqual(errorSchema.properties.request_id, { type: "string", minLength: 1, maxLength: 128 });
});
