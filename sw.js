// TrefferPoint Service Worker
// CACHE_VER mit APP_VERSION in index.html zusammen bumpen
const CACHE_VER = 'tp-2.3.36';
const PRECACHE = ['./index.html', './manifest.json', './version.json'];

self.addEventListener('install', e => {
    e.waitUntil(
        caches.open(CACHE_VER).then(cache =>
            Promise.allSettled(
                PRECACHE.map(url => cache.add(new Request(url, { cache: 'reload' })))
            )
        )
    );
    self.skipWaiting();
});

self.addEventListener('activate', e => {
    e.waitUntil(
        caches.keys()
            .then(keys => Promise.all(
                keys.filter(k => k !== CACHE_VER).map(k => caches.delete(k))
            ))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', e => {
    if (e.request.method !== 'GET') return;
    const url = new URL(e.request.url);

    // version.json: always network-first
    if (url.pathname.endsWith('version.json')) {
        e.respondWith(
            fetch(e.request, { cache: 'no-store' })
                .then(r => {
                    if (r.ok) caches.open(CACHE_VER).then(c => c.put(e.request, r.clone()));
                    return r;
                })
                .catch(() => caches.match(e.request))
        );
        return;
    }

    // Everything else: cache-first + background update (stale-while-revalidate)
    e.respondWith(
        caches.match(e.request).then(cached => {
            const networkFetch = fetch(e.request)
                .then(r => {
                    if (r.ok) caches.open(CACHE_VER).then(c => c.put(e.request, r.clone()));
                    return r;
                })
                .catch(() => null);
            return cached ?? networkFetch;
        })
    );
});
