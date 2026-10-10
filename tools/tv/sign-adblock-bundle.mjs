// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

import { createHash, createPrivateKey, createPublicKey, sign } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

export const MAGIC = Buffer.from('TVAB0001');
export const MAX_BUNDLE_BYTES = 24 * 1024 * 1024;
export const FILTER_IDS = [
  'adcocjohghhfpidemphmcmlmhnfgikei',
  'bfpgedeaaibpoidldhjcknekahbikncb',
  'cdbbhgbmjhfnhnmgeddbliobbofkgdhe',
  'iodkpdagapdfkphljnddpjlldadblomo',
  'kihnoaefogbkmblfimmibknnmkllbhlf',
];
const sha256 = data => createHash('sha256').update(data).digest('hex');

export async function createSignedBundle(directory, key, sequence, published) {
  if (key.asymmetricKeyType !== 'ed25519' || key.type !== 'private') {
    throw new Error('An Ed25519 private key is required');
  }
  if (!Number.isSafeInteger(sequence) || sequence < 1 || sequence > 2147483647) {
    throw new Error('Sequence must be an integer between 1 and 2147483647');
  }
  if (!Number.isFinite(Date.parse(published))
      || new Date(published).toISOString() !== published) {
    throw new Error('Publication time must be a canonical UTC ISO timestamp');
  }
  const provenance = JSON.parse(await readFile(resolve(directory, 'provenance.json'), 'utf8'));
  const names = ['catalog.json', 'resources.json', ...FILTER_IDS.map(id => `${id}.txt`)];
  if (JSON.stringify(Object.keys(provenance.files).sort()) !== JSON.stringify(names.toSorted())
      || sha256(JSON.stringify(provenance.files)) !== provenance.version) {
    throw new Error('Invalid snapshot provenance');
  }
  const files = {};
  for (const name of names) {
    const bytes = await readFile(resolve(directory, name));
    if (!bytes.length || sha256(bytes) !== provenance.files[name]) {
      throw new Error(`Snapshot hash mismatch: ${name}`);
    }
    files[name] = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  }
  if (!Array.isArray(JSON.parse(files['resources.json']))) {
    throw new Error('Resources must be an array');
  }
  const payload = Buffer.from(JSON.stringify({
    format: 1,
    engine: 'adblock-rs-0.13.2',
    sequence,
    published,
    catalog_sha256: provenance.files['catalog.json'],
    source_version: provenance.version,
    provenance,
    resources: files['resources.json'],
    filters: Object.fromEntries(FILTER_IDS.map(id => [id, files[`${id}.txt`]])),
  }));
  if (payload.length + MAGIC.length + 64 > MAX_BUNDLE_BYTES) {
    throw new Error('Signed bundle exceeds 24 MiB');
  }
  return Buffer.concat([MAGIC, sign(null, payload, key), payload]);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({ options: {
    key: { type: 'string' }, output: { type: 'string' },
    sequence: { type: 'string' }, published: { type: 'string' },
  } });
  if (!values.key || !values.output || !values.sequence || !values.published) {
    throw new Error('Required: --key PATH --output PATH --sequence INTEGER --published UTC_ISO');
  }
  const key = createPrivateKey(await readFile(values.key));
  const directory = fileURLToPath(new URL('../../components/brave_shields/resources/bundled/', import.meta.url));
  const bundle = await createSignedBundle(directory, key, Number(values.sequence), values.published);
  // Refuse to overwrite an existing key, artifact or last-good release.
  await writeFile(values.output, bundle, { flag: 'wx', mode: 0o644 });
  console.log(JSON.stringify({
    bytes: bundle.length, sha256: sha256(bundle), sequence: Number(values.sequence),
    publicKey: createPublicKey(key).export({ format: 'jwk' }).x,
  }));
}
