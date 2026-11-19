(() => {
    const widget = document.querySelector('.assistant-widget');
    const panel = document.querySelector('.assistant-panel');
    const minimize = document.querySelector('.assistant-minimize');
    const peek = document.querySelector('.assistant-peek');
    try {
        if (sessionStorage.getItem('menu-assistant-minimized') === 'true') widget.classList.add('is-minimized');
    } catch (_) {}
    minimize.addEventListener('click', () => {
        panel.open = false;
        widget.classList.add('is-minimized');
        try { sessionStorage.setItem('menu-assistant-minimized', 'true'); } catch (_) {}
        peek.focus();
    });
    peek.addEventListener('click', () => {
        widget.classList.remove('is-minimized');
        try { sessionStorage.removeItem('menu-assistant-minimized'); } catch (_) {}
        panel.querySelector('summary').focus();
    });
    const form = document.getElementById('assistant-form'), input = document.getElementById('assistant-message');
    const log = document.getElementById('assistant-log'), status = document.getElementById('assistant-status');
    const send = document.getElementById('assistant-send');
    let history = [], pending;
    const language = () => document.documentElement.lang === 'es' ? 'es' : 'en';
    function addMessage(role, text, lang, items = []) {
        const entry = document.createElement('div'), paragraph = document.createElement('p');
        entry.className = `assistant-turn assistant-${role}`;
        entry.lang = lang;
        paragraph.textContent = `${role === 'user' ? (lang === 'es' ? 'Usted' : 'You') : (lang === 'es' ? 'Asistente' : 'Assistant')}: ${text}`;
        entry.append(paragraph);
        items.forEach(item => {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = `${item.name} · $${Number(item.price).toFixed(2)} · ${lang === 'es' ? 'Ver en el menú' : 'View in menu'}`;
            button.addEventListener('click', () => {
                const target = document.querySelector(`.drink[data-id="${item.id}"]`);
                if (target) { target.scrollIntoView({ block: 'center', behavior: 'smooth' }); target.focus(); }
                else status.textContent = lang === 'es' ? 'Actualice la página para ver el menú actual.' : 'Refresh the page to see the current menu.';
            });
            entry.append(button);
        });
        log.append(entry);
        while (log.children.length > 8) log.firstElementChild.remove();
        log.scrollTop = log.scrollHeight;
    }
    function clear() {
        pending?.abort(); pending = null;
        history = []; log.replaceChildren(); input.value = ''; status.textContent = ''; send.disabled = false; input.disabled = false;
    }
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const text = input.value.trim(), lang = language();
        if (!text || text.length > 500 || pending) return;
        const controller = new AbortController(); pending = controller;
        send.disabled = true; input.disabled = true; status.lang = lang;
        status.textContent = lang === 'es' ? 'Consultando el menú…' : 'Checking the menu…';
        try {
            const response = await fetch('/api/assistant', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: text, history, language: lang }), signal: AbortSignal.any([controller.signal, AbortSignal.timeout(20000)]) });
            const result = await response.json();
            if (pending !== controller) return;
            if (!response.ok) throw new Error(result.error);
            addMessage('user', text, lang);
            addMessage('assistant', result.reply, result.language, result.items);
            const context = result.reply.slice(0, 800) + (result.items.length ? `\nRecommended menu IDs: ${result.items.map(item => item.id).join(', ')}` : '');
            history = [...history, { role: 'user', content: text }, { role: 'assistant', content: context }].slice(-6);
            input.value = ''; status.textContent = '';
        } catch (error) {
            if (pending !== controller) return;
            status.textContent = error.message && !['TimeoutError', 'AbortError', 'TypeError', 'SyntaxError'].includes(error.name) ? error.message
                : lang === 'es' ? 'El asistente no está disponible. Puede seguir usando el menú.' : 'The assistant is unavailable. You can still use the menu.';
        } finally {
            if (pending === controller) { pending = null; send.disabled = false; input.disabled = false; input.focus(); }
        }
    });
    document.getElementById('assistant-clear').addEventListener('click', () => { clear(); input.focus(); });
    document.getElementById('new-order')?.addEventListener('click', clear);
    window.addEventListener('order-completed', clear);
})();
