import { createReadStream } from "node:fs";
import {
  access,
  constants,
  lstat,
  mkdir,
  readdir,
  realpath,
  rename,
  rm,
  rmdir,
} from "node:fs/promises";
import path from "node:path";
import { StorageError } from "../domain/errors.mjs";
import {
  requireChildName,
  requirePathSegment,
  STATE_DIRECTORY,
} from "../domain/storage-route.mjs";

/**
 * Local storage implementation that keeps the Express example runnable.
 * Replace this service in production while retaining the router and security
 * middleware contract.
 */
export class LocalDirectoryStorageService {
  constructor(config, stateStore) {
    this.config = config;
    this.stateStore = stateStore;
  }

  async initialize() {
    await mkdir(this.config.storageRoot, { recursive: true, mode: 0o700 });
    this.root = await realpath(this.config.storageRoot);
  }

  async info(segments) {
    const file =
      segments.length === 0 ? this.root : await this.#existingPath(segments);
    const metadata = await lstat(file);
    if (!metadata.isFile() && !metadata.isDirectory()) {
      throw new StorageError(403, "This storage item type is not supported");
    }
    const documentPath = segments.join("/");
    const lock = await this.stateStore.currentLock(documentPath);
    return {
      path: documentPath,
      name: segments.at(-1) || this.config.rootName,
      type: metadata.isDirectory() ? "directory" : "file",
      size: metadata.isDirectory() ? 0 : metadata.size,
      readable: await permission(file, constants.R_OK),
      writable: await permission(file, constants.W_OK),
      locked: lock !== null,
      locker: lock?.owner ?? null,
      createdAt: metadata.birthtime.toISOString(),
      modifiedAt: metadata.mtime.toISOString(),
      revision: revisionFor(metadata),
    };
  }

  async list(segments) {
    const directory = await this.#directory(segments);
    const names = (await readdir(directory))
      .filter((name) => name !== STATE_DIRECTORY)
      .sort();
    if (names.length > 10_000) {
      throw new StorageError(
        413,
        "The directory contains more than 10000 entries",
      );
    }
    const entries = [];
    for (const name of names)
      entries.push(await this.info([...segments, name]));
    return { entries };
  }

  async download(segments) {
    const file = await this.#existingPath(segments);
    const metadata = await lstat(file);
    if (!metadata.isFile())
      throw new StorageError(409, "The requested item is not a file");
    return { stream: createReadStream(file), contentLength: metadata.size };
  }

  async save(segments, stagingFile) {
    if (segments.length === 0)
      throw new StorageError(400, "A document path is required");
    await this.#directory(segments.slice(0, -1));
    const target = await this.#path(segments, true);
    const existing = await lstat(target).catch((error) => {
      if (error?.code === "ENOENT") return null;
      throw error;
    });
    if (existing?.isDirectory()) {
      throw new StorageError(409, "A directory already uses this path");
    }
    await rename(stagingFile, target);
    return revisionFor(await lstat(target));
  }

  async lock(segments, owner) {
    await this.info(segments);
    await this.stateStore.acquireLock(segments.join("/"), owner);
  }

  async unlock(segments, owner) {
    await this.stateStore.releaseLock(segments.join("/"), owner);
  }

  async createDirectory(segments, name) {
    const parent = await this.#directory(segments);
    const target = path.join(parent, requireChildName(name));
    await mkdir(target, { mode: 0o700 }).catch((error) => {
      if (error?.code === "EEXIST")
        throw new StorageError(409, "An item already uses this name");
      throw error;
    });
  }

  async rename(segments, name) {
    if (segments.length === 0)
      throw new StorageError(400, "The storage root cannot be renamed");
    await this.stateStore.requireUnlocked(segments.join("/"));
    const source = await this.#existingPath(segments);
    const target = path.join(path.dirname(source), requireChildName(name));
    const exists = await lstat(target)
      .then(() => true)
      .catch((error) => {
        if (error?.code === "ENOENT") return false;
        throw error;
      });
    if (exists) throw new StorageError(409, "An item already uses this name");
    await rename(source, target);
  }

  async delete(segments) {
    if (segments.length === 0)
      throw new StorageError(400, "The storage root cannot be deleted");
    await this.stateStore.requireUnlocked(segments.join("/"));
    const target = await this.#existingPath(segments);
    const metadata = await lstat(target);
    if (metadata.isDirectory()) await rmdir(target);
    else await rm(target, { force: false });
  }

  async #directory(segments) {
    const directory =
      segments.length === 0 ? this.root : await this.#existingPath(segments);
    const metadata = await lstat(directory);
    if (!metadata.isDirectory())
      throw new StorageError(409, "The parent path is not a directory");
    return directory;
  }

  async #existingPath(segments) {
    return this.#path(segments, false);
  }

  async #path(segments, allowMissingFinal) {
    let current = this.root;
    for (let index = 0; index < segments.length; index += 1) {
      current = path.join(current, requirePathSegment(segments[index]));
      const relative = path.relative(this.root, current);
      if (relative.startsWith("..") || path.isAbsolute(relative)) {
        throw new StorageError(
          400,
          "The document path escapes the storage root",
        );
      }
      try {
        const metadata = await lstat(current);
        if (metadata.isSymbolicLink()) {
          throw new StorageError(
            403,
            "Symbolic links are not available through this Provider",
          );
        }
      } catch (error) {
        if (error instanceof StorageError) throw error;
        if (
          error?.code === "ENOENT" &&
          allowMissingFinal &&
          index === segments.length - 1
        ) {
          return current;
        }
        throw error;
      }
    }
    return current;
  }
}

async function permission(file, flag) {
  try {
    await access(file, flag);
    return true;
  } catch {
    return false;
  }
}

function revisionFor(metadata) {
  return `${Math.trunc(metadata.mtimeMs)}-${metadata.size}`;
}
