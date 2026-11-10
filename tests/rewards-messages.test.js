const { test } = require('node:test');
const assert = require('node:assert/strict');
const { sendMessage } = require('../lib/rewards');

test('Twilio and Resend requests keep credentials server-side and fail clearly when unavailable', async t => {
    const keys = ['TWILIO_ACCOUNT_SID', 'TWILIO_AUTH_TOKEN', 'TWILIO_FROM_NUMBER', 'RESEND_API_KEY', 'REWARDS_EMAIL_FROM'];
    const saved = Object.fromEntries(keys.map(key => [key, process.env[key]]));
    t.after(() => {
        for (const key of keys) {
            if (saved[key] === undefined) delete process.env[key];
            else process.env[key] = saved[key];
        }
    });
    for (const key of keys) delete process.env[key];
    const requests = [];
    const fetchMock = t.mock.method(globalThis, 'fetch', async (url, options) => {
        requests.push({ url, options });
        return new Response('', { status: 201 });
    });
    await assert.rejects(sendMessage({ type: 'phone', value: '+12025550101' }, 'Test'), error => error.status === 503);
    await assert.rejects(sendMessage({ type: 'email', value: 'member@example.com' }, 'Test'), error => error.status === 503);
    assert.equal(requests.length, 0);
    Object.assign(process.env, { TWILIO_ACCOUNT_SID: 'AC_test', TWILIO_AUTH_TOKEN: 'test-token', TWILIO_FROM_NUMBER: '+12025550100', RESEND_API_KEY: 'test-key', REWARDS_EMAIL_FROM: 'Rewards <rewards@example.com>' });
    await sendMessage({ type: 'phone', value: '+12025550101' }, 'Your code: 123456');
    assert.equal(requests[0].url, 'https://api.twilio.com/2010-04-01/Accounts/AC_test/Messages.json');
    assert.equal(requests[0].options.body.get('To'), '+12025550101');
    assert.equal(requests[0].options.body.get('From'), '+12025550100');
    assert.equal(requests[0].options.headers.Authorization, `Basic ${Buffer.from('AC_test:test-token').toString('base64')}`);
    await sendMessage({ type: 'email', value: 'member@example.com' }, 'Your code: 123456');
    assert.equal(requests[1].url, 'https://api.resend.com/emails');
    assert.deepEqual(JSON.parse(requests[1].options.body).to, ['member@example.com']);
    assert.equal(requests[1].options.headers.Authorization, 'Bearer test-key');
    fetchMock.mock.mockImplementation(async () => new Response('', { status: 400 }));
    await assert.rejects(sendMessage({ type: 'phone', value: '+12025550101' }, 'Test'), error => error.status === 503);
});
