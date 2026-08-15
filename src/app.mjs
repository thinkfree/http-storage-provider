import express from "express";
import { LocalStateStore } from "./repositories/local-state-store.mjs";
import { RequestJwtVerifier } from "./security/request-jwt-verifier.mjs";
import { LocalDirectoryStorageService } from "./services/local-directory-storage-service.mjs";
import { createStorageRouter } from "./routes/storage-router.mjs";
import {
  errorHandler,
  notFoundHandler,
  sendText,
} from "./middleware/error-handler.mjs";

/** Build an Express application with explicit dependencies for testing and replacement. */
export async function createApp(config) {
  const stateStore = new LocalStateStore(config.storageRoot, config.adapter);
  await stateStore.initialize();
  const storageService = new LocalDirectoryStorageService(config, stateStore);
  await storageService.initialize();
  const requestVerifier = new RequestJwtVerifier(config, stateStore);

  const app = express();
  app.disable("x-powered-by");
  app.get("/healthz", (request, response) => {
    void request;
    sendText(response, 200, "ok\n");
  });
  app.use(
    createStorageRouter({
      config,
      stateStore,
      storageService,
      requestVerifier,
    }),
  );
  app.use(notFoundHandler);
  app.use(errorHandler);
  return app;
}
