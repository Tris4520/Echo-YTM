// BotGuard protocol adapted from Metrolist and BgUtils; see THIRD_PARTY_NOTICES.md.
// Echo awaits this async function and disposes the WebView after the result.
const requestKey = 'O43z0dpjhgX20SCx4KAo';
const baseUrl = 'https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/';
const controller = new AbortController();
let timeout;
const deadline = new Promise((_, reject) => {
    timeout = setTimeout(() => { controller.abort(); reject(new Error('Attestation timeout')); }, 10000);
});
let shutdown;
function decodeBase64(value) {
    const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '='));
    return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}
function encodeToken(bytes) {
    if (!(bytes instanceof Uint8Array) || bytes.length === 0 || bytes.length > 4096) throw new Error('Invalid mint output');
    return btoa(String.fromCharCode.apply(null, bytes)).replace(/\+/g, '-').replace(/\//g, '_');
}
async function rpc(method, payload) {
    const response = await fetch(baseUrl + method, {
        method: 'POST', credentials: 'omit', signal: controller.signal,
        headers: {
            'content-type': 'application/json+protobuf',
            'x-goog-api-key': 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw',
            'x-user-agent': 'grpc-web-javascript/0.1'
        },
        body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error('Attestation request failed');
    const text = await response.text();
    if (text.length > 2 * 1024 * 1024) throw new Error('Attestation response too large');
    return JSON.parse(text);
}
async function generate() {
    const raw = typeof preparedChallenge !== 'undefined' && preparedChallenge
        ? preparedChallenge : await rpc('Create', [requestKey]);
    const challenge = typeof raw[1] === 'string'
        ? JSON.parse(new TextDecoder().decode(decodeBase64(raw[1]).map((b) => b + 97))) : raw[0];
    if (!Array.isArray(challenge)) throw new Error('Invalid challenge');
    let interpreter = Array.isArray(challenge[1]) && challenge[1].find((v) => typeof v === 'string' && v);
    if (!interpreter) {
        const wrappedUrl = Array.isArray(challenge[2]) && challenge[2].find((v) => typeof v === 'string' && v);
        const url = new URL(wrappedUrl, 'https://www.youtube.com');
        if (url.protocol !== 'https:' || url.username || url.password || url.port ||
            !['www.youtube.com', 'www.google.com', 'www.gstatic.com'].includes(url.hostname)) throw new Error('Invalid interpreter URL');
        const response = await fetch(url.href, { credentials: 'omit', signal: controller.signal, redirect: 'error' });
        if (!response.ok) throw new Error('Interpreter download failed');
        interpreter = await response.text();
    }
    if (interpreter.length > 2 * 1024 * 1024) throw new Error('Interpreter too large');
    new Function(interpreter)();
    const vm = window[challenge[5]];
    if (!vm || typeof vm.a !== 'function') throw new Error('BotGuard VM unavailable');
    const functions = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('BotGuard initialization timeout')), 4000);
        vm.a(challenge[4], (snapshot, stop) => {
            clearTimeout(timer); shutdown = stop; resolve({ snapshot });
        }, true, undefined, () => {}, [[], []]);
    });
    const signals = [];
    const snapshot = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('BotGuard snapshot timeout')), 4000);
        functions.snapshot((value) => { clearTimeout(timer); resolve(value); }, [undefined, undefined, signals, undefined]);
    });
    const integrity = await rpc('GenerateIT', [requestKey, snapshot]);
    if (typeof signals[0] !== 'function') throw new Error('BotGuard minter unavailable');
    const minter = await signals[0](decodeBase64(integrity[0]));
    if (typeof minter !== 'function') throw new Error('Invalid minter');
    // v0.11.2 selects playerRequestToken for VISITOR_DATA and streamingDataToken for VIDEO_ID binding.
    const visitorToken = encodeToken(await minter(new TextEncoder().encode(visitorData)));
    const videoToken = encodeToken(await minter(new TextEncoder().encode(videoId)));
    return JSON.stringify({ playerRequestToken: visitorToken, streamingDataToken: videoToken,
        visitorData, expiresInSeconds: Number(integrity[1]) });
}
try {
    return await Promise.race([generate(), deadline]);
} finally {
    clearTimeout(timeout); controller.abort();
    if (typeof shutdown === 'function') { try { shutdown(); } catch (_) {} }
}
