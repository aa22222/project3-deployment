(() => {
    const picker = document.getElementById('page-language');
    const status = document.getElementById('language-status');
    const originals = new WeakMap(), cache = new Map();
    const excluded = 'script, style, svg, #language-tools, [translate="no"]';
    let language = 'en', running = false, dirty = false, timer;
    const observer = new MutationObserver(records => {
        if (records.some(record => !(record.target.parentElement || record.target).closest?.(excluded))) schedule();
    });
    const observe = () => observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['aria-label', 'placeholder', 'title'] });

    function entries() {
        const result = [];
        function capture(node, attribute) {
            const current = attribute ? node.getAttribute(attribute) : node.nodeValue;
            if (!current || !/\p{L}/u.test(current)) return;
            if (!originals.has(node)) originals.set(node, new Map());
            const saved = originals.get(node);
            if (!saved.has(attribute) || saved.get(attribute).rendered !== current) saved.set(attribute, { source: current, rendered: current });
            const state = saved.get(attribute), numbers = [];
            // Send text templates rather than order numbers, totals, or point balances.
            const text = state.source.trim().replace(/\d+(?:[.,]\d+)*/g, number => `{${numbers.push(number) - 1}}`);
            result.push({ node, attribute, current, state, text, numbers });
        }
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) if (!walker.currentNode.parentElement.closest(excluded)) capture(walker.currentNode, null);
        document.querySelectorAll('[aria-label], [placeholder], [title]').forEach(node => {
            if (!node.closest(excluded)) for (const attribute of ['aria-label', 'placeholder', 'title']) capture(node, attribute);
        });
        return result;
    }
    function apply(list, target) {
        observer.disconnect();
        const decoder = document.createElement('textarea');
        for (const entry of list) {
            const { node, attribute, current, state, numbers, text } = entry;
            if (!node.isConnected || (attribute ? node.getAttribute(attribute) : node.nodeValue) !== current) continue;
            let value = state.source;
            if (target === 'es') {
                decoder.innerHTML = cache.get(text);
                const translated = decoder.value;
                if (numbers.some((_, index) => !translated.includes(`{${index}}`))) throw new Error('Translation changed number placeholders');
                value = state.source.match(/^\s*/)[0] + translated.replace(/\{(\d+)\}/g, (match, index) => numbers[index] ?? match) + state.source.match(/\s*$/)[0];
            }
            state.rendered = value;
            if (attribute) node.setAttribute(attribute, value);
            else node.nodeValue = value; // Never insert provider output as page HTML.
        }
    }
    function schedule() {
        if (running) { dirty = true; return; }
        if (language !== 'es') return;
        clearTimeout(timer);
        timer = setTimeout(() => translate('es'), 120);
    }
    async function translate(target) {
        if (running) { dirty = true; return; }
        running = true; picker.disabled = true;
        status.textContent = target === 'es' ? 'Translating… / Traduciendo…' : '';
        try {
            const list = entries();
            if (target === 'es') {
                const missing = [...new Set(list.map(entry => entry.text))].filter(text => !cache.has(text));
                while (missing.length) {
                    const batch = []; let length = 0;
                    while (missing.length && batch.length < 16 && length + missing[0].length <= 20000) {
                        const text = missing.shift(); batch.push(text); length += text.length;
                    }
                    if (!batch.length) throw new Error('Text too long');
                    const response = await fetch('/api/translate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ target, texts: batch }), signal: AbortSignal.timeout(30000) });
                    const result = await response.json();
                    if (!response.ok || !Array.isArray(result.translations) || result.translations.length !== batch.length) throw new Error('Translation unavailable');
                    result.translations.forEach((text, index) => cache.set(batch[index], text));
                }
            }
            apply(list, target);
            document.documentElement.lang = language = target;
            status.textContent = '';
        } catch {
            apply(entries(), 'en');
            document.documentElement.lang = language = 'en';
            status.textContent = 'Translation unavailable. English is still available. / Traducción no disponible. Puede continuar en inglés.';
        } finally {
            picker.value = language; picker.disabled = false; running = false; observe();
            if (dirty) { dirty = false; schedule(); }
        }
    }
    picker.addEventListener('change', () => translate(picker.value));
    observe();
})();
