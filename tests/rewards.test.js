const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const net = require('node:net');
let server, base;
before(async () => {
    const listener = net.createServer();
    await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
    const port = listener.address().port;
    await new Promise(resolve => listener.close(resolve));
    base = `http://127.0.0.1:${port}`;
    server = spawn(process.execPath, ['index.js', '--mock'], { cwd: require('node:path').join(__dirname, '..'), env: { ...process.env, PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'] });
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Mock server did not start')), 10000);
        server.once('error', reject);
        server.once('exit', code => { clearTimeout(timer); reject(new Error(`Server exited: ${code}`)); });
        server.stdout.on('data', data => { if (data.toString().includes('listening')) { clearTimeout(timer); resolve(); } });
    });
});
after(async () => {
    if (server && server.exitCode === null) {
        const exited = new Promise(resolve => server.once('exit', resolve));
        server.kill();
        await exited;
    }
});
async function post(path, body) {
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
}
async function join(contact, extra = {}) {
    const code = await post('/api/rewards/code', { contact, ...extra });
    assert.equal(code.status, 200);
    assert.match(code.body.demoCode, /^\d{6}$/);
    const verified = await post('/api/rewards/verify', { challengeId: code.body.challengeId, code: code.body.demoCode });
    assert.equal(verified.status, 200);
    return verified.body;
}
const order = (token, quantity, redeemReward = false) => post('/orders', { rewardsToken: token, redeemReward, paymentMethod: 'Cash', items: [{ menuItemId: 1, quantity, customizations: [] }] });

test('verified members earn points, redeem server-priced discounts, and retain their balance across checkouts', async () => {
    const member = await join('(202) 555-0101', { smsOptIn: true });
    assert.equal(member.account.points, 0);
    const purchase = await order(member.token, 11);
    assert.equal(purchase.status, 200);
    assert.equal(purchase.body.total, '54.89');
    assert.equal(purchase.body.rewards.pointsEarned, 54);
    assert.equal(purchase.body.rewards.points, 54);
    assert.equal(purchase.body.rewards.notification, 'demo');
    const redemption = await order(member.token, 2, true);
    assert.equal(redemption.status, 200);
    assert.equal(redemption.body.subtotal, '9.98');
    assert.equal(redemption.body.discount, '5.00');
    assert.equal(redemption.body.total, '4.98');
    assert.equal(redemption.body.rewards.points, 8);
    const rejoined = await join('+12025550101');
    assert.equal(rejoined.account.points, 8);
    const rejected = await order(member.token, 2, true);
    assert.equal(rejected.status, 400);
    assert.equal((await order(member.token, 1)).body.rewards.points, 12);
    assert.equal((await post('/api/rewards/preferences', { token: member.token, smsOptIn: false })).status, 200);
    assert.equal((await order(member.token, 1)).body.rewards.notification, 'off');
});

test('phone and email can be verified and linked to the same member without merging strangers', async () => {
    const member = await join('member@example.com');
    await order(member.token, 2);
    const linked = await join('+12025550102', { token: member.token, smsOptIn: true });
    assert.equal(linked.account.points, 9);
    assert.ok(linked.account.phone);
    assert.ok(linked.account.email);
    assert.equal((await join('+12025550102')).account.points, 9);
    const other = await join('other@example.com');
    const conflict = await post('/api/rewards/code', { contact: '+12025550102', token: other.token });
    const verified = await post('/api/rewards/verify', { challengeId: conflict.body.challengeId, code: conflict.body.demoCode });
    assert.equal(verified.status, 409);
});

test('codes are single-use, throttle repeat requests, limit attempts and reject invalid credentials', async () => {
    assert.equal((await post('/api/rewards/code', { contact: 'invalid' })).status, 400);
    const challenge = await post('/api/rewards/code', { contact: 'wrong-code@example.com' });
    assert.equal((await post('/api/rewards/code', { contact: 'wrong-code@example.com' })).status, 429);
    const wrong = challenge.body.demoCode === '000000' ? '000001' : '000000';
    for (let i = 0; i < 5; i++) assert.equal((await post('/api/rewards/verify', { challengeId: challenge.body.challengeId, code: wrong })).status, 400);
    assert.equal((await post('/api/rewards/verify', { challengeId: challenge.body.challengeId, code: challenge.body.demoCode })).status, 400);
    const valid = await post('/api/rewards/code', { contact: 'single-use@example.com' });
    assert.equal((await post('/api/rewards/verify', { challengeId: valid.body.challengeId, code: valid.body.demoCode })).status, 200);
    assert.equal((await post('/api/rewards/verify', { challengeId: valid.body.challengeId, code: valid.body.demoCode })).status, 400);
    assert.equal((await order('made-up-token', 1)).status, 401);
    assert.equal((await order(undefined, 2, true)).status, 400);
});

test('invalid orders do not earn points, small orders cannot redeem, and concurrent redemptions cannot double-spend', async () => {
    const member = await join('concurrent@example.com');
    assert.equal((await order(member.token, -1)).status, 400);
    assert.equal((await order(member.token, 11)).body.rewards.points, 54);
    assert.equal((await order(member.token, 1, true)).status, 400);
    const responses = await Promise.all([order(member.token, 2, true), order(member.token, 2, true)]);
    assert.equal(responses.filter(response => response.status === 200).length, 1);
    assert.equal(responses.filter(response => response.status === 400).length, 1);
    assert.equal((await order(member.token, 1)).body.rewards.points, 12);
    assert.equal((await post('/orders', { paymentMethod: 'Cash', items: [{ menuItemId: 1, quantity: 1 }] })).status, 200);
});


test('mobile checkout retries and concurrent requests create one order', async () => {
    const mobile = await fetch(base + '/mobile');
    assert.equal(mobile.status, 200);
    const html = await mobile.text();
    assert.match(html, /mobile-page/);
    assert.match(html, /Image coming soon/);
    const key = require('node:crypto').randomUUID();
    const body = { paymentMethod: 'Card', items: [{ menuItemId: 1, quantity: 2, customizations: ['Boba'] }] };
    const checkout = async payload => {
        const response = await fetch(base + '/orders', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(payload) });
        return { status: response.status, body: await response.json() };
    };
    const results = await Promise.all([checkout(body), checkout(body), checkout(body)]);
    results.forEach(result => { assert.equal(result.status, 200); assert.equal(result.body.total, '11.96'); assert.equal(result.body.orderId, results[0].body.orderId); });
    assert.deepEqual(await checkout(body), results[0]);
    assert.equal((await checkout({ ...body, paymentMethod: 'Cash' })).status, 409);
});
