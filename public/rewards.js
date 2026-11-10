// Keep rewards credentials in memory only; never retain them on a shared kiosk.
window.rewardsWidget = (() => {
    const element = id => document.getElementById(id);
    const contactForm = element('rewards-contact-form'), codeForm = element('rewards-code-form');
    const redeem = element('rewards-redeem'), texts = element('rewards-texts');
    let token = null, account = null, challengeId = null, subtotalCents = 0, locked = false;
    const message = text => element('rewards-message').textContent = text;
    async function request(path, body) {
        const response = await fetch(`/api/rewards/${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || 'Could not access rewards.');
        return result;
    }
    async function submit(form, task) {
        if (locked) return;
        locked = true;
        const button = form.querySelector('button');
        button.disabled = true;
        message('Please wait…');
        try { await task(); } catch (error) { message(error.message); }
        finally { button.disabled = false; locked = false; }
    }
    function update() {
        const eligible = Boolean(account && account.points >= 50 && subtotalCents >= 500);
        redeem.disabled = !eligible;
        if (!eligible) redeem.checked = false;
        if (account) {
            element('rewards-balance').textContent = `${account.points} points`;
            element('rewards-contacts').textContent = [account.phone, account.email].filter(Boolean).join(' · ');
            element('rewards-progress').textContent = account.points < 50
                ? `${50 - account.points} more points unlocks $5 off.`
                : subtotalCents < 500 ? 'Add at least $5 of items to use your reward.' : 'Your $5 reward is available.';
        }
        window.dispatchEvent(new Event('rewards-change'));
    }
    function reset() {
        if (token) request('sign-out', { token }).catch(() => {});
        token = account = challengeId = null;
        contactForm.reset(); codeForm.reset(); redeem.checked = false;
        contactForm.hidden = false; codeForm.hidden = true; element('rewards-account').hidden = true;
        element('rewards-panel').open = false;
        message(''); update();
    }
    contactForm.addEventListener('submit', event => {
        event.preventDefault();
        submit(contactForm, async () => {
            const result = await request('code', { contact: element('rewards-contact').value, token, smsOptIn: element('rewards-opt-in').checked });
            challengeId = result.challengeId;
            contactForm.hidden = true; codeForm.hidden = false; codeForm.reset();
            message(result.demoCode ? `Demo mode — no message sent. Code: ${result.demoCode}` : 'Code sent. Check your texts or email; it expires in 5 minutes.');
            element('rewards-code').focus();
        });
    });
    codeForm.addEventListener('submit', event => {
        event.preventDefault();
        submit(codeForm, async () => {
            const result = await request('verify', { challengeId, code: element('rewards-code').value });
            if (token) request('sign-out', { token }).catch(() => {});
            token = result.token; account = result.account;
            codeForm.hidden = true; contactForm.hidden = true; element('rewards-account').hidden = false;
            contactForm.reset(); codeForm.reset();
            texts.checked = account.smsOptIn; texts.disabled = !account.phone;
            message('Rewards attached to this order. Sign-in clears after checkout.'); update();
        });
    });
    element('rewards-change-contact').addEventListener('click', () => {
        codeForm.hidden = true; contactForm.hidden = false;
        element('rewards-contact').focus(); message('');
    });
    element('rewards-link').addEventListener('click', () => {
        contactForm.hidden = false; element('rewards-contact').focus();
        message('Verify a second contact to use the same points with phone or email.');
    });
    element('rewards-sign-out').addEventListener('click', reset);
    redeem.addEventListener('change', update);
    texts.addEventListener('change', async () => {
        texts.disabled = true;
        try {
            const result = await request('preferences', { token, smsOptIn: texts.checked });
            account.smsOptIn = result.smsOptIn;
            message(result.smsOptIn ? 'Reward texts enabled.' : 'Reward texts turned off.');
        } catch (error) { texts.checked = account.smsOptIn; message(error.message); }
        finally { texts.disabled = false; }
    });
    return {
        updateSubtotal(cents) { subtotalCents = cents; update(); },
        get discountCents() { return account && !redeem.disabled && redeem.checked ? 500 : 0; },
        get checkout() { return { rewardsToken: token, redeemReward: redeem.checked }; },
        reset
    };
})();
