import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { resources } from "../src/i18n.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src");

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.(jsx?|mjs)$/.test(entry.name) ? [full] : [];
  }));
  return nested.flat();
}

function flatten(value, prefix = "", result = new Set()) {
  for (const [key, child] of Object.entries(value)) {
    const next = prefix ? `${prefix}.${key}` : key;
    if (child && typeof child === "object") flatten(child, next, result);
    else result.add(next);
  }
  return result;
}

const used = new Set();
for (const file of await sourceFiles(root)) {
  const source = await readFile(file, "utf8");
  for (const match of source.matchAll(/\bt\(\s*["']([^"']+)["']/g)) used.add(match[1]);
}

const english = flatten(resources.en.translation);
const missingEnglish = [...used].filter((key) => !english.has(key));

if (missingEnglish.length) {
  console.error(JSON.stringify({ missingEnglish }, null, 2));
  process.exit(1);
}
console.log(`English UI dictionary OK: ${used.size} used keys, ${english.size} available keys`);
