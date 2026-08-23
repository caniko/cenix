import { expect, test } from "bun:test";
import { auditApk, auditManifestXml } from "../src/audit-manifest.ts";

const okXml = `
<manifest>
  <application android:debuggable="false">
    <activity android:exported="true">
      <intent-filter>
        <category android:name="android.intent.category.HOME" />
      </intent-filter>
    </activity>
  </application>
</manifest>
`;

test("accepts a HOME launcher without INTERNET", () => {
  expect(auditManifestXml(okXml)).toEqual([]);
});

test("rejects INTERNET permission", () => {
  const findings = auditManifestXml(
    `<manifest><uses-permission android:name="android.permission.INTERNET"/></manifest>`,
  );
  expect(findings.some((item) => item.code === "INTERNET")).toBe(true);
});

test("rejects debuggable release manifests", () => {
  const findings = auditManifestXml(`<application android:debuggable="true"/>`);
  expect(findings.some((item) => item.code === "DEBUGGABLE")).toBe(true);
});

test("apk missing jni library is rejected", () => {
  const findings = auditApk(emptyZip());
  expect(findings.some((item) => item.code === "JNI")).toBe(true);
});

function emptyZip(): Uint8Array {
  // Minimal empty ZIP: local nothing + EOCD.
  return Uint8Array.from([
    0x50, 0x4b, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  ]);
}
