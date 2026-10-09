import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { Engine, FilterSet } from 'adblock-rs';

const root = new URL('../../components/brave_shields/resources/bundled/', import.meta.url);
const json = async name => JSON.parse(await readFile(new URL(name, root), 'utf8'));

test('every bundled payload matches recorded provenance', async () => {
  const provenance = await json('provenance.json');
  assert.equal(Object.keys(provenance.files).length, 7);
  for (const [name, expected] of Object.entries(provenance.files)) {
    const data = await readFile(new URL(name, root));
    assert.equal(createHash('sha256').update(data).digest('hex'), expected, name);
    assert.ok(data.length > 100, name);
  }
});

test('catalog components have matching lists and maintain their policy split', async () => {
  const catalog = await json('catalog.json');
  assert.equal(catalog.length, 5);
  assert.equal(catalog.filter(entry => entry.first_party_protections).length, 2);
  for (const entry of catalog) {
    assert.equal(entry.default_enabled, true);
    assert.equal(entry.permission_mask, 1);
    const data = await readFile(new URL(`${entry.list_text_component.component_id}.txt`, root), 'utf8');
    assert.ok(data.includes('! Source: https://'));
    assert.ok(!/^!#(?:if |else$|endif$)/m.test(data));
  }
});

test('resource library includes the standard set-constant scriptlet', async () => {
  const resources = await json('resources.json');
  assert.ok(resources.some(resource => resource.name === 'set-constant.js'
    || resource.aliases?.includes('set-constant.js')));
});

test('the real engine blocks the ad fixture and permits ordinary resources', async () => {
  const filters = new FilterSet();
  for (const entry of await json('catalog.json')) {
    filters.addFilters(await readFile(new URL(`${entry.list_text_component.component_id}.txt`, root), 'utf8'),
      { permissions: entry.permission_mask });
  }
  const engine = new Engine(filters);
  assert.equal(engine.check('http://localhost:18081/showbanner.js',
    'http://127.0.0.1:18081/shields.html', 'script'), true);
  assert.equal(engine.check('http://127.0.0.1:18081/shields-control.js',
    'http://127.0.0.1:18081/shields.html', 'script'), false);
  assert.equal(engine.check('https://en.wikipedia.org/wiki/Ad_blocking',
    'https://en.wikipedia.org/wiki/Ad_blocking', 'document'), false);
});

test('bundled scriptlets resolve dependencies and retain trusted-resource restrictions', async () => {
  const filters = new FilterSet();
  filters.addFilters('example.com##+js(set, tvShieldsProbe, true)');
  const engine = new Engine(filters);
  assert.equal(engine.urlCosmeticResources('https://example.com/').injected_script, '');
  engine.useResources(await json('resources.json'));
  assert.ok(engine.urlCosmeticResources('https://example.com/').injected_script.includes('tvShieldsProbe'));
  const untrusted = new FilterSet();
  untrusted.addFilters('example.com##+js(trusted-set-constant, tvTrustedProbe, true)');
  const untrustedEngine = new Engine(untrusted);
  untrustedEngine.useResources(await json('resources.json'));
  assert.equal(untrustedEngine.urlCosmeticResources('https://example.com/').injected_script, '');
});
