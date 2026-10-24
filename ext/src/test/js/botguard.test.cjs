const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/botguard.js'), 'utf8');

function fixture(options = {}) {
    const calls = [];
    const interpreter = `globalThis.TestVM = { a(program, ready) {
        ready((done, args) => {
            args[2].push(async (integrity) => {
                if (integrity[0] !== 123) throw new Error('wrong integrity bytes');
                return async (binding) => new Uint8Array(binding);
            });
            done('mock-snapshot');
        }, () => { globalThis.stopped = true; });
    }};`;
    const challenge = ['message', [interpreter], null, 'hash', 'program', 'TestVM', null, 'experiments'];
    const create = options.scrambled
        ? [null, Buffer.from(Buffer.from(JSON.stringify(challenge)).map((b) => b - 97)).toString('base64')]
        : [challenge];
    const context = vm.createContext({
        videoId: 'abcdefghijk', visitorData: "visitor'\ndata",
        preparedChallenge: options.prepared ? create : null,
        AbortController, TextEncoder, TextDecoder, URL, atob, btoa, setTimeout, clearTimeout,
        fetch: async (url, init) => {
            calls.push({ url, init, payload: JSON.parse(init.body) });
            const data = url.endsWith('/Create') ? create : [Buffer.from([123]).toString('base64'), 3600];
            return { ok: !options.fail, text: async () => JSON.stringify(data) };
        }
    });
    context.window = context;
    return { calls, context, run: () => vm.runInContext(`(async function(){${source}\n})()`, context) };
}

test('runs Create, snapshot, GenerateIT and mints both bindings in correct order', async () => {
    const f = fixture();
    const result = JSON.parse(await f.run());
    assert.equal(result.playerRequestToken, Buffer.from("visitor'\ndata").toString('base64').replace(/\+/g, '-').replace(/\//g, '_'));
    assert.equal(result.streamingDataToken, Buffer.from('abcdefghijk').toString('base64'));
    assert.equal(result.expiresInSeconds, 3600);
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls[0].url.endsWith('/Create'));
    assert.ok(f.calls[1].url.endsWith('/GenerateIT'));
    assert.deepEqual(f.calls[1].payload, ['O43z0dpjhgX20SCx4KAo', 'mock-snapshot']);
    assert.equal(f.calls[0].init.credentials, 'omit');
    assert.equal(f.context.stopped, true);
    assert.equal(f.calls[0].init.signal.aborted, true);
});

test('descrambles encoded challenges without changing bindings', async () => {
    const f = fixture({ scrambled: true });
    const result = JSON.parse(await f.run());
    assert.equal(result.visitorData, "visitor'\ndata");
    assert.equal(f.calls.length, 2);
});

test('a prepared challenge skips Create and still mints both correct bindings', async () => {
    const f = fixture({ prepared: true, scrambled: true });
    const result = JSON.parse(await f.run());
    assert.equal(f.calls.length, 1);
    assert.ok(f.calls[0].url.endsWith('/GenerateIT'));
    assert.equal(result.streamingDataToken, Buffer.from('abcdefghijk').toString('base64'));
    assert.equal(result.playerRequestToken, Buffer.from("visitor'\ndata").toString('base64'));
    assert.equal(f.context.stopped, true);
});

test('fails on unsuccessful challenge without requesting an integrity token or retrying', async () => {
    const f = fixture({ fail: true });
    await assert.rejects(f.run(), /Attestation request failed/);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].init.signal.aborted, true);
});
