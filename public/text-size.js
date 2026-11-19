(() => {
    const controls = document.querySelectorAll('[data-text-size]');
    if (!controls.length) return;

    const key = 'order-page-text-size';
    const levels = [90, 100, 110, 120, 130];
    let size = 100;
    try {
        const saved = Number(localStorage.getItem(key));
        if (levels.includes(saved)) size = saved;
    } catch (_) {}

    const status = document.getElementById('text-size-status');
    const apply = announce => {
        document.body.style.zoom = `${size}%`;
        controls.forEach(button => {
            if (button.dataset.textSize === 'reset') {
                button.textContent = `${size}%`;
                button.disabled = size === 100;
            } else {
                button.disabled = (button.dataset.textSize === 'increase' && size === levels.at(-1)) ||
                    (button.dataset.textSize === 'decrease' && size === levels[0]);
            }
        });
        if (announce && status) status.textContent = `Text size ${size} percent`;
    };

    controls.forEach(button => button.addEventListener('click', () => {
        if (button.dataset.textSize === 'reset') size = 100;
        else {
            const index = levels.indexOf(size);
            size = levels[Math.max(0, Math.min(levels.length - 1, index + (button.dataset.textSize === 'increase' ? 1 : -1)))];
        }
        try { localStorage.setItem(key, String(size)); } catch (_) {}
        apply(true);
    }));

    apply(false);
})();
