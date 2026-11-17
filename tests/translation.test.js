const { test } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const { createTranslationRouter } = require('../lib/translation');

test('translation uses only the anonymous free API, validates/caches text, and handles quota/provider failures', async t => {
    const app = express(); app.use(express.json()); app.use('/translate', createTranslationRouter());
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    const endpoint = `http://127.0.0.1:${server.address().port}/translate`;
    const actualFetch = globalThis.fetch;
    const calls = [];
    let providerStatus = 200, malformed = false, quotaFinished = false;
    t.mock.method(globalThis, 'fetch', async (url, options) => {
        if (!String(url).startsWith('https://api.mymemory.translated.net/')) return actualFetch(url, options);
        calls.push({ url, options });
        const q = new URL(url).searchParams.get('q');
        return Response.json(malformed ? {} : { responseStatus: 200, quotaFinished, responseData: { translatedText: `ES ${q}` } }, { status: providerStatus });
    });
    async function translate(body) {
        const response = await actualFetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        return { status: response.status, body: await response.json() };
    }
    for (const body of [
        { target: 'fr', texts: ['Menu'] }, { target: 'es', texts: [] },
        { target: 'es', texts: [1] }, { target: 'es', texts: ['x'.repeat(1501)] },
        { target: 'es', texts: Array(129).fill('Menu') },
        { target: 'es', texts: Array(100).fill('x'.repeat(300)) }
    ]) assert.equal((await translate(body)).status, 400);
    assert.equal(calls.length, 0);
    assert.deepEqual((await translate({ target: 'en', texts: ['Menu'] })).body.translations, ['Menu']);
    const first = await translate({ target: 'es', texts: ['Menu', 'Milk Tea', 'Menu'] });
    assert.equal(first.status, 200);
    assert.deepEqual(first.body.translations, ['ES Menu', 'ES Milk Tea', 'ES Menu']);
    for (const { url, options } of calls) {
        assert.equal(new URL(url).origin, 'https://api.mymemory.translated.net');
        assert.equal(new URL(url).searchParams.get('langpair'), 'en|es');
        assert.equal(new URL(url).searchParams.get('key'), null);
        assert.equal(new URL(url).searchParams.get('de'), null);
        assert.equal(options.headers, undefined);
    }
    await translate({ target: 'es', texts: ['Milk Tea', 'Menu'] });
    assert.equal(calls.length, 2, 'cached text does not consume the daily allowance again');
    await translate({ target: 'es', texts: ['é'.repeat(400)] });
    assert.ok(calls.every(call => Buffer.byteLength(new URL(call.url).searchParams.get('q'), 'utf8') <= 500));
    providerStatus = 429;
    assert.equal((await translate({ target: 'es', texts: ['Unavailable'] })).status, 503);
    providerStatus = 200; malformed = true;
    assert.equal((await translate({ target: 'es', texts: ['Malformed'] })).status, 503);
    malformed = false; quotaFinished = true;
    assert.equal((await translate({ target: 'es', texts: ['Quota reached'] })).status, 503);
});
