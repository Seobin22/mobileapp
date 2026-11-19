// 내 저장소 서비스 워커 (범위 /private).
// 안드로이드 공유 메뉴에서 보낸 파일(POST /private-share)을 기기 안(IndexedDB)에 잠깐 두고
// private.html?share=1 로 넘긴다. 페이지가 로그인된 상태에서 평소 업로드로 올린 뒤 지운다.
// 로그인 쿠키(SameSite=Strict, 30분)가 없어도 파일을 잃지 않게 하려는 것. 그 밖의 요청은 건드리지 않는다.

const DB = "private-share";
const STORE = "pending";

function openDb() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(DB, 1);
        req.onupgradeneeded = () => req.result.createObjectStore(STORE);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
}

async function savePending(files) {
    const db = await openDb();
    await new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, "readwrite");
        const store = tx.objectStore(STORE);
        // 아직 올리지 않은 이전 공유가 있으면 뒤에 붙인다.
        const get = store.get("files");
        get.onsuccess = () => store.put([...(get.result || []), ...files], "files");
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
    });
    db.close();
}

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", event => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", event => {
    const url = new URL(event.request.url);
    if (event.request.method !== "POST" || url.pathname !== "/private-share") return;
    event.respondWith((async () => {
        try {
            const form = await event.request.formData();
            const files = form.getAll("files").filter(f => f instanceof File && f.size > 0);
            await savePending(files);
            return Response.redirect("/private.html?share=" + files.length, 303);
        } catch (e) {
            return Response.redirect("/private.html?share=failed", 303);
        }
    })());
});
