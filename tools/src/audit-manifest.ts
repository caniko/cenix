#!/usr/bin/env bun

import { readFileSync, existsSync } from "node:fs";

export type Finding = { code: string; message: string };
export type AuditResult = { ok: boolean; findings: Finding[] };

const INTERNET = "android.permission.INTERNET";

export function auditManifestXml(xml: string): Finding[] {
  const findings: Finding[] = [];
  if (xml.includes(INTERNET)) {
    findings.push({ code: "INTERNET", message: "INTERNET permission is forbidden" });
  }
  if (/android:debuggable\s*=\s*"true"/i.test(xml)) {
    findings.push({ code: "DEBUGGABLE", message: "release manifest must not be debuggable" });
  }
  if (!xml.includes("android.intent.category.HOME")) {
    findings.push({ code: "HOME", message: "HOME category is required" });
  }
  return findings;
}

export function auditApk(bytes: Uint8Array): Finding[] {
  const findings: Finding[] = [];
  const files = listZip(bytes);
  const so = files.filter((name) => name.endsWith("libcenix_jni.so"));
  if (so.length === 0) {
    findings.push({ code: "JNI", message: "libcenix_jni.so is missing from the APK" });
  }
  const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  if (text.includes(INTERNET)) {
    findings.push({ code: "INTERNET", message: "INTERNET permission string found in APK" });
  }
  return findings;
}

export function auditPaths(paths: string[]): AuditResult {
  const findings: Finding[] = [];
  for (const path of paths) {
    if (!existsSync(path)) {
      findings.push({ code: "MISSING", message: `missing ${path}` });
      continue;
    }
    const bytes = new Uint8Array(readFileSync(path));
    if (path.endsWith(".apk")) {
      findings.push(...auditApk(bytes));
    } else {
      findings.push(...auditManifestXml(new TextDecoder().decode(bytes)));
    }
  }
  return { ok: findings.length === 0, findings };
}

function listZip(bytes: Uint8Array): string[] {
  const names: string[] = [];
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let i = 0; i < bytes.length - 30; i++) {
    if (view.getUint32(i, true) !== 0x04034b50) continue;
    const nameLen = view.getUint16(i + 26, true);
    const extraLen = view.getUint16(i + 28, true);
    const name = new TextDecoder().decode(bytes.subarray(i + 30, i + 30 + nameLen));
    names.push(name);
    const compressed = view.getUint32(i + 18, true);
    i += 30 + nameLen + extraLen + compressed - 1;
  }
  return names;
}

if (import.meta.main) {
  const paths = process.argv.slice(2);
  if (paths.length === 0) {
    console.error("usage: bun src/audit-manifest.ts <manifest.xml|app.apk>...");
    process.exit(2);
  }
  const result = auditPaths(paths);
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.ok ? 0 : 1);
}
