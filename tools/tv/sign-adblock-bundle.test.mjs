// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, generateKeyPairSync, verify } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createSignedBundle, FILTER_IDS, MAGIC } from './sign-adblock-bundle.mjs';

const directory = fileURLToPath(new URL('../../components/brave_shields/resources/bundled/', import.meta.url));
const published = '2026-10-10T00:00:00.000Z';
const { privateKey, publicKey } = generateKeyPairSync('ed25519');

test('the real snapshot is deterministic and signed without private-key material', async () => {
  const bundle = await createSignedBundle(directory, privateKey, 1, published);
  assert.deepEqual(bundle, await createSignedBundle(directory, privateKey, 1, published));
  assert.deepEqual(bundle.subarray(0, 8), MAGIC);
  const signature = bundle.subarray(8, 72);
  const payload = bundle.subarray(72);
  assert.ok(verify(null, payload, publicKey, signature));
  assert.equal(verify(null, payload, generateKeyPairSync('ed25519').publicKey, signature), false);
  const modified = Buffer.from(payload);
  modified[modified.length - 1] ^= 1;
  assert.equal(verify(null, modified, publicKey, signature), false);
  const data = JSON.parse(payload);
  assert.equal(data.sequence, 1);
  assert.equal(data.published, published);
  assert.equal(data.engine, 'adblock-rs-0.13.2');
  assert.deepEqual(Object.keys(data.filters), FILTER_IDS);
  assert.equal(data.resources, await readFile(join(directory, 'resources.json'), 'utf8'));
  for (const id of FILTER_IDS) {
    assert.equal(data.filters[id], await readFile(join(directory, `${id}.txt`), 'utf8'));
  }
  assert.equal(bundle.includes(privateKey.export({ format: 'der', type: 'pkcs8' })), false);
});

test('bad signing metadata and modified snapshot files are rejected', async () => {
  await assert.rejects(createSignedBundle(directory, publicKey, 1, published), /private key/);
  await assert.rejects(createSignedBundle(directory, privateKey, 0, published), /Sequence/);
  await assert.rejects(createSignedBundle(directory, privateKey, 1.5, published), /Sequence/);
  await assert.rejects(createSignedBundle(directory, privateKey, 2147483648, published), /Sequence/);
  await assert.rejects(createSignedBundle(directory, privateKey, 1, 'bad-date'), /timestamp/);
  const temp = await mkdtemp(join(tmpdir(), 'tv-filter-signing-'));
  try {
    const provenance = JSON.parse(await readFile(join(directory, 'provenance.json'), 'utf8'));
    // The catalog is the first input; its altered bytes must fail before signing.
    await writeFile(join(temp, 'provenance.json'), JSON.stringify(provenance));
    await writeFile(join(temp, 'catalog.json'), 'changed');
    await assert.rejects(createSignedBundle(temp, privateKey, 1, published), /hash mismatch/);
    provenance.files['unexpected.txt'] = createHash('sha256').update('unexpected').digest('hex');
    await writeFile(join(temp, 'provenance.json'), JSON.stringify(provenance));
    await assert.rejects(createSignedBundle(temp, privateKey, 1, published), /provenance/);
  } finally {
    await rm(temp, { recursive: true });
  }
});

test('the CLI refuses to replace an existing artifact', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'tv-filter-signing-cli-'));
  try {
    const key = join(temp, 'publisher.pem');
    const output = join(temp, 'filters.bundle');
    await writeFile(key, privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
    await writeFile(output, 'last-good-artifact');
    const run = spawnSync(process.execPath, [
      fileURLToPath(new URL('./sign-adblock-bundle.mjs', import.meta.url)),
      '--key', key, '--output', output, '--sequence', '1', '--published', published,
    ], { encoding: 'utf8' });
    assert.notEqual(run.status, 0);
    assert.match(run.stderr, /EEXIST/);
    assert.equal(await readFile(output, 'utf8'), 'last-good-artifact');
  } finally {
    await rm(temp, { recursive: true });
  }
});
