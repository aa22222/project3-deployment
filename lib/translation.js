const express = require('express');

function createTranslationRouter() {
    const router = express.Router();
    const cache = new Map(), limits = new Map();
    router.post('/', async (req, res) => {
        res.set('Cache-Control', 'no-store');
        const { texts, target } = req.body || {};
        if (!['en', 'es'].includes(target) || !Array.isArray(texts) || !texts.length || texts.length > 128 ||
            texts.some(text => typeof text !== 'string' || !text.trim() || text.length > 1500) ||
            texts.reduce((sum, text) => sum + text.length, 0) > 20000) {
            return res.status(400).json({ error: 'Choose English or Spanish and a valid batch of text.' });
        }
        if (target === 'en') return res.json({ translations: texts });
        const now = Date.now();
        for (const [key, value] of limits) if (value.expires <= now) limits.delete(key);
        const limit = limits.get(req.ip) || { count: 0, expires: now + 60000 };
        if (++limit.count > 30) return res.status(429).json({ error: 'Please wait before translating again.' });
        limits.set(req.ip, limit);
        const missing = [...new Set(texts)].filter(text => !cache.has(text) || cache.get(text).expires <= now);
        const resolved = new Map(texts.map(text => [text, cache.get(text)?.text]));
        const controller = new AbortController();
        try {
            // Anonymous requests only: no key, billing account, or paid fallback.
            await Promise.all(Array.from({ length: Math.min(4, missing.length) }, async () => {
                while (missing.length && !controller.signal.aborted) {
                    const text = missing.shift(), translated = [];
                    // At most 121 Unicode code points keeps each segment below 500 bytes.
                    for (const segment of text.match(/.{1,120}(?:\s|$)|.{1,120}/gsu)) {
                        const url = new URL('https://api.mymemory.translated.net/get');
                        url.search = new URLSearchParams({ q: segment, langpair: 'en|es', mt: '1' });
                        const response = await fetch(url, { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(6000)]) });
                        if (!response.ok) throw new Error('Translation provider unavailable');
                        const result = await response.json(), value = result.responseData?.translatedText;
                        if (Number(result.responseStatus) !== 200 || result.quotaFinished || typeof value !== 'string' || !value.trim() || /^MYMEMORY WARNING/i.test(value)) throw new Error('Translation limit or invalid response');
                        translated.push(value.trim());
                    }
                    const value = translated.join(' ');
                    if (cache.size >= 2000) cache.delete(cache.keys().next().value);
                    cache.set(text, { text: value, expires: now + 86400000 });
                    resolved.set(text, value);
                }
            }));
            res.json({ translations: texts.map(text => resolved.get(text)) });
        } catch {
            controller.abort();
            res.status(503).json({ error: 'Translation is unavailable. Please continue in English or try again.' });
        }
    });
    return router;
}

module.exports = { createTranslationRouter };
