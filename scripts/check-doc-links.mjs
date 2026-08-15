import { access, readFile, readdir } from "node:fs/promises";
import path from "node:path";

async function markdownFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    if (
      entry.name === "node_modules" ||
      entry.name === "target" ||
      entry.name === ".git"
    )
      continue;
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await markdownFiles(candidate)));
    else if (entry.name.endsWith(".md")) files.push(candidate);
  }
  return files;
}

const failures = [];
for (const file of await markdownFiles(process.cwd())) {
  const source = await readFile(file, "utf8");
  for (const match of source.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)) {
    const target = match[1].split("#", 1)[0];
    if (!target || /^(?:https?:|mailto:)/.test(target)) continue;
    const resolved = path.resolve(
      path.dirname(file),
      decodeURIComponent(target),
    );
    try {
      await access(resolved);
    } catch {
      failures.push(`${path.relative(process.cwd(), file)} -> ${target}`);
    }
  }
}

if (failures.length) {
  console.error(`Broken documentation links:\n${failures.join("\n")}`);
  process.exitCode = 1;
} else {
  console.log("Documentation links are valid.");
}
