import { createHash, randomUUID } from "node:crypto";
import path from "node:path";
import {
  mkdir,
  open,
  readFile,
  readdir,
  rm,
  unlink,
  writeFile,
} from "node:fs/promises";
import { RequestAuthenticationError, StorageError } from "../domain/errors.mjs";
import { STATE_DIRECTORY } from "../domain/storage-route.mjs";

function keyFor(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

async function readJson(file) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

export class LocalStateStore {
  constructor(storageRoot, adapter) {
    this.adapter = adapter;
    this.stateRoot = path.join(storageRoot, STATE_DIRECTORY);
    this.replayRoot = path.join(this.stateRoot, "replay");
    this.lockRoot = path.join(this.stateRoot, "locks");
    this.stagingRoot = path.join(this.stateRoot, "staging");
    this.replayWrites = 0;
  }

  async initialize() {
    await mkdir(this.replayRoot, { recursive: true, mode: 0o700 });
    await mkdir(this.lockRoot, { recursive: true, mode: 0o700 });
    await mkdir(this.stagingRoot, { recursive: true, mode: 0o700 });
  }

  async createStagingFile() {
    const file = path.join(this.stagingRoot, `${randomUUID()}.stage`);
    const handle = await open(file, "wx", 0o600);
    return { file, handle };
  }

  async removeStagingFile(file) {
    if (!file) return;
    await rm(file, { force: true });
  }

  async consumeRequestId(jti, expiresAt) {
    const file = path.join(this.replayRoot, keyFor(`${this.adapter}\0${jti}`));
    const record = JSON.stringify({ expiresAt });
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const handle = await open(file, "wx", 0o600);
        try {
          await handle.writeFile(record, "utf8");
        } finally {
          await handle.close();
        }
        this.replayWrites += 1;
        if (this.replayWrites % 100 === 0)
          void this.cleanupExpiredReplayEntries();
        return;
      } catch (error) {
        if (error?.code !== "EEXIST") throw error;
        const existing = await readJson(file);
        if (
          existing &&
          Number(existing.expiresAt) <= Math.floor(Date.now() / 1000)
        ) {
          await rm(file, { force: true });
          continue;
        }
        throw new RequestAuthenticationError();
      }
    }
    throw new RequestAuthenticationError();
  }

  async cleanupExpiredReplayEntries() {
    const now = Math.floor(Date.now() / 1000);
    try {
      const entries = await readdir(this.replayRoot);
      await Promise.all(
        entries.slice(0, 1000).map(async (name) => {
          const file = path.join(this.replayRoot, name);
          const record = await readJson(file);
          if (!record || Number(record.expiresAt) <= now)
            await rm(file, { force: true });
        }),
      );
    } catch {
      // Cleanup is opportunistic. Authentication does not depend on this pass.
    }
  }

  lockFile(documentPath) {
    return path.join(this.lockRoot, keyFor(`${this.adapter}\0${documentPath}`));
  }

  async currentLock(documentPath) {
    return readJson(this.lockFile(documentPath));
  }

  async acquireLock(documentPath, owner) {
    const file = this.lockFile(documentPath);
    const record = { documentPath, owner, createdAt: new Date().toISOString() };
    try {
      const handle = await open(file, "wx", 0o600);
      try {
        await handle.writeFile(JSON.stringify(record), "utf8");
      } finally {
        await handle.close();
      }
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      const existing = await readJson(file);
      if (!existing || existing.owner !== owner) {
        throw new StorageError(409, "The document is locked by another owner");
      }
    }
  }

  async releaseLock(documentPath, owner) {
    const file = this.lockFile(documentPath);
    const existing = await readJson(file);
    if (!existing) return;
    if (existing.owner !== owner) {
      throw new StorageError(409, "The lock belongs to another owner");
    }
    await unlink(file).catch((error) => {
      if (error?.code !== "ENOENT") throw error;
    });
  }

  async requireUnlocked(documentPath) {
    if (await this.currentLock(documentPath)) {
      throw new StorageError(409, "The document is locked");
    }
  }
}
