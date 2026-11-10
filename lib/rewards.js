const express = require('express');
const crypto = require('node:crypto');

const REWARD_POINTS = 50;
const DISCOUNT_CENTS = 500;
const failure = (status, message) => Object.assign(new Error(message), { status });
const hash = value => crypto.createHash('sha256').update(value).digest();

function normalizeContact(value) {
    if (typeof value !== 'string' || value.length > 254) throw failure(400, 'Enter a valid phone number or email.');
    const contact = value.trim();
    if (contact.includes('@')) {
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact)) throw failure(400, 'Enter a valid email.');
        return { type: 'email', value: contact.toLowerCase() };
    }
    if (!/^\+?[\d\s().-]+$/.test(contact)) throw failure(400, 'Enter a valid phone number.');
    const digits = contact.replace(/\D/g, '');
    if (!contact.startsWith('+') && !(digits.length === 10 || (digits.length === 11 && digits.startsWith('1')))) throw failure(400, 'Enter a 10-digit US phone number or include the country code.');
    const phone = contact.startsWith('+') || digits.length === 11 ? `+${digits}` : `+1${digits}`;
    if (!/^\+[1-9]\d{7,14}$/.test(phone)) throw failure(400, 'Enter a 10-digit US phone number or include the country code.');
    return { type: 'phone', value: phone };
}

// Server-side delivery only. No provider credentials or recipient details are logged.
async function sendMessage(contact, text) {
    let response;
    if (contact.type === 'phone') {
        const { TWILIO_ACCOUNT_SID: sid, TWILIO_AUTH_TOKEN: token, TWILIO_FROM_NUMBER: from } = process.env;
        if (!sid || !token || !from) throw failure(503, 'Text messages are unavailable right now. Please order without rewards or use email.');
        response = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(sid)}/Messages.json`, {
            method: 'POST', signal: AbortSignal.timeout(10000),
            headers: { Authorization: `Basic ${Buffer.from(`${sid}:${token}`).toString('base64')}`, 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ To: contact.value, From: from, Body: text })
        });
    } else {
        if (!process.env.RESEND_API_KEY || !process.env.REWARDS_EMAIL_FROM) throw failure(503, 'Email messages are unavailable right now. Please order without rewards or use a phone number.');
        response = await fetch('https://api.resend.com/emails', {
            method: 'POST', signal: AbortSignal.timeout(10000),
            headers: { Authorization: `Bearer ${process.env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ from: process.env.REWARDS_EMAIL_FROM, to: [contact.value], subject: 'Boba Shop Rewards', text })
        });
    }
    if (!response.ok) throw failure(503, 'The message could not be sent. Please try again later.');
}

function createRewards({ pool, mock = false, deliver = sendMessage }) {
    const router = express.Router();
    const customers = new Map(), challenges = new Map(), sessions = new Map(), limits = new Map();
    let nextId = 1;
    const accountView = customer => ({
        points: Number(customer.points), smsOptIn: customer.sms_opt_in,
        phone: customer.phone ? `•••${customer.phone.slice(-4)}` : null,
        email: customer.email ? `${customer.email[0]}•••@${customer.email.split('@')[1]}` : null,
        rewardPoints: REWARD_POINTS, discount: DISCOUNT_CENTS / 100
    });
    function cleanExpired() {
        for (const map of [challenges, sessions, limits]) for (const [key, item] of map) if (item.expires <= Date.now()) map.delete(key);
    }
    function session(token) {
        const member = typeof token === 'string' ? sessions.get(token) : null;
        if (!member || member.expires <= Date.now()) throw failure(401, 'Rewards sign-in expired. Please verify your phone or email again.');
        return member;
    }
    async function getCustomer(id, client = pool, lock = false) {
        const customer = mock ? customers.get(Number(id)) : (await client.query(
            `SELECT * FROM rewards_customer WHERE customer_id = $1${lock ? ' FOR UPDATE' : ''}`, [id]
        )).rows[0];
        if (!customer) throw failure(401, 'Please sign in to rewards again.');
        return customer;
    }
    const route = handler => async (req, res) => {
        res.set('Cache-Control', 'no-store');
        try { await handler(req, res); }
        catch (error) {
            if (!error.status) console.error('Rewards request failed:', error.code || 'unexpected error');
            res.status(error.status || 500).json({ error: error.status ? error.message : 'Rewards are unavailable. Please try again.' });
        }
    };
    router.post('/code', route(async (req, res) => {
        cleanExpired();
        const contact = normalizeContact(req.body?.contact);
        const customerId = req.body?.token ? session(req.body.token).customerId : null;
        const key = `contact:${contact.value}`, ipKey = `ip:${req.ip}`;
        const ipLimit = limits.get(ipKey) || { count: 0, expires: Date.now() + 15 * 60000 };
        if (limits.has(key) || ipLimit.count >= 20 || challenges.size >= 1000) throw failure(429, 'Please wait before requesting another code.');
        limits.set(key, { expires: Date.now() + 60000 });
        limits.set(ipKey, { ...ipLimit, count: ipLimit.count + 1 });
        const code = String(crypto.randomInt(100000, 1000000));
        if (!mock) await deliver(contact, `Boba Shop Rewards: your sign-in code is ${code}. It expires in 5 minutes. Do not share this code. Reply STOP to unsubscribe from texts.`);
        const challengeId = crypto.randomBytes(24).toString('hex');
        challenges.set(challengeId, { contact, customerId, codeHash: hash(code), attempts: 0, expires: Date.now() + 5 * 60000, smsOptIn: req.body?.smsOptIn === true && contact.type === 'phone' });
        res.json({ challengeId, ...(mock ? { demoCode: code } : {}) });
    }));
    router.post('/verify', route(async (req, res) => {
        cleanExpired();
        const challenge = challenges.get(req.body?.challengeId);
        if (!challenge || challenge.attempts >= 5) throw failure(400, 'Code expired or invalid. Request a new code.');
        challenge.attempts++;
        if (challenge.attempts === 5) challenges.delete(req.body.challengeId);
        if (typeof req.body?.code !== 'string' || !crypto.timingSafeEqual(challenge.codeHash, hash(req.body.code))) throw failure(400, 'Code expired or invalid. Request a new code if needed.');
        challenges.delete(req.body.challengeId);
        limits.delete(`contact:${challenge.contact.value}`);
        const { type, value } = challenge.contact;
        let customer;
        if (mock) {
            const existing = [...customers.values()].find(item => item[type] === value);
            if (challenge.customerId && existing && existing.customer_id !== challenge.customerId) throw failure(409, 'That contact belongs to another rewards account. Sign out to use it.');
            customer = challenge.customerId ? await getCustomer(challenge.customerId) : existing;
            if (customer?.[type] && customer[type] !== value) throw failure(409, 'This account already has that contact type linked.');
            if (!customer) {
                customer = { customer_id: nextId++, phone: null, email: null, points: 0, sms_opt_in: false };
                customers.set(customer.customer_id, customer);
            }
            customer[type] = value;
            if (challenge.smsOptIn) customer.sms_opt_in = true;
        } else {
            try {
                if (challenge.customerId) {
                    const existing = await getCustomer(challenge.customerId);
                    if (existing[type] && existing[type] !== value) throw failure(409, 'This account already has that contact type linked.');
                    customer = (await pool.query(
                        `UPDATE rewards_customer SET ${type} = $1, sms_opt_in = sms_opt_in OR $2 WHERE customer_id = $3 AND (${type} IS NULL OR ${type} = $1) RETURNING *`,
                        [value, challenge.smsOptIn, challenge.customerId]
                    )).rows[0];
                    if (!customer) throw failure(409, 'This account already has that contact type linked.');
                } else {
                    // type is the validated literal "phone" or "email", never user-provided SQL.
                    customer = (await pool.query(
                        `INSERT INTO rewards_customer (${type}, sms_opt_in) VALUES ($1, $2) ON CONFLICT (${type}) DO UPDATE SET sms_opt_in = rewards_customer.sms_opt_in OR EXCLUDED.sms_opt_in RETURNING *`,
                        [value, challenge.smsOptIn]
                    )).rows[0];
                }
            } catch (error) {
                if (error.code === '23505') throw failure(409, 'That contact belongs to another rewards account. Sign out to use it.');
                throw error;
            }
        }
        const token = crypto.randomBytes(32).toString('hex');
        sessions.set(token, { customerId: Number(customer.customer_id), expires: Date.now() + 30 * 60000 });
        res.json({ token, account: accountView(customer) });
    }));
    router.post('/preferences', route(async (req, res) => {
        const customer = await getCustomer(session(req.body?.token).customerId);
        if (typeof req.body?.smsOptIn !== 'boolean' || (!customer.phone && req.body.smsOptIn)) throw failure(400, 'Verify a phone number before enabling reward texts.');
        if (mock) customer.sms_opt_in = req.body.smsOptIn;
        else await pool.query('UPDATE rewards_customer SET sms_opt_in = $1 WHERE customer_id = $2', [req.body.smsOptIn, customer.customer_id]);
        res.json({ smsOptIn: req.body.smsOptIn });
    }));
    router.post('/sign-out', route(async (req, res) => {
        sessions.delete(req.body?.token);
        res.json({ ok: true });
    }));

    async function prepareOrder(token, redeem, subtotalCents, client) {
        if (redeem !== undefined && typeof redeem !== 'boolean') throw failure(400, 'Choose whether to use your reward.');
        if (!token) {
            if (redeem) throw failure(400, 'Sign in to rewards before redeeming points.');
            return { totalCents: subtotalCents, discountCents: 0 };
        }
        const customer = await getCustomer(session(token).customerId, client, true);
        if (redeem && (Number(customer.points) < REWARD_POINTS || subtotalCents < DISCOUNT_CENTS)) throw failure(400, 'You need 50 points and an order of at least $5 to use this reward.');
        const discountCents = redeem ? DISCOUNT_CENTS : 0;
        return { customer, totalCents: subtotalCents - discountCents, discountCents, pointsRedeemed: redeem ? REWARD_POINTS : 0, pointsEarned: Math.floor((subtotalCents - discountCents) / 100) };
    }
    async function completeOrder(reward, orderId, client) {
        if (!reward.customer) return;
        if (mock) {
            if (reward.customer.points < reward.pointsRedeemed) throw failure(400, 'Not enough points remain. Please sign in again to refresh your balance.');
            reward.customer.points += reward.pointsEarned - reward.pointsRedeemed;
            reward.points = reward.customer.points;
        } else {
            const result = await client.query('UPDATE rewards_customer SET points = points + $1 - $2 WHERE customer_id = $3 RETURNING points', [reward.pointsEarned, reward.pointsRedeemed, reward.customer.customer_id]);
            reward.points = Number(result.rows[0].points);
            await client.query('INSERT INTO rewards_activity (order_id, customer_id, points_earned, points_redeemed, discount_amount) VALUES ($1, $2, $3, $4, $5)', [orderId, reward.customer.customer_id, reward.pointsEarned, reward.pointsRedeemed, (reward.discountCents / 100).toFixed(2)]);
        }
    }
    async function notify(reward, orderId) {
        if (!reward.customer?.phone || !reward.customer.sms_opt_in) return 'off';
        if (mock) return 'demo';
        const available = reward.points >= REWARD_POINTS ? 'You have a $5 reward available on an order of $5 or more!' : `${REWARD_POINTS - reward.points} more points unlocks $5 off.`;
        try {
            await deliver({ type: 'phone', value: reward.customer.phone }, `Boba Shop Rewards: order #${orderId} earned ${reward.pointsEarned} points${reward.pointsRedeemed ? ` and used ${reward.pointsRedeemed} points` : ''}. Balance: ${reward.points}. ${available} Reply STOP to unsubscribe, HELP for help.`);
            return 'queued';
        } catch {
            // The committed order and points remain successful if messaging is unavailable.
            console.error('Reward text could not be queued. Check the messaging provider configuration.');
            return 'failed';
        }
    }
    async function orderResult(reward, orderId) {
        if (!reward.customer) return null;
        return { points: reward.points, pointsEarned: reward.pointsEarned, pointsRedeemed: reward.pointsRedeemed, notification: await notify(reward, orderId) };
    }
    return { router, prepareOrder, completeOrder, orderResult };
}

module.exports = { createRewards, normalizeContact, sendMessage };
