// Export and import of every save slot and setting as one backup file.
//
// Settings in the app shows "Export saves" when window.__rrExport exists and "Import saves" when
// window.__rrImport exists (see WebApp.kt). The panels are plain HTML over the app, so a real tap
// opens the share sheet or the file picker, which iPhone Safari only allows from a tap.
//
// Where the saves live depends on the page: on the GitHub Pages site in the browser's
// localStorage, on the Claude page in window.__rrStore (store.js). The Claude page also sets
// window.__rrImportTarget(items) -> Promise, which replaces its saves, and window.__rrDownload
// (name, text) -> Promise, because a plain download link does nothing there.
//
// File format: {"app":"river-runner","format":1,"exported":"<ISO time>","items":{"rr.<key>":"<text>"}}
(function () {
    "use strict";
    var zh = /^zh/i.test(navigator.language || "");
    var T = zh ? {
        exportTitle: "导出存档", importTitle: "导入存档",
        exportBody: function (n, kb) { return "所有存档、牌谱和设置（" + n + " 项，约 " + kb + " KB）会存成一个备份文件。在 Claude 页面版里点“设置 → 导入存档”选这个文件，就能接着玩。"; },
        empty: "这里还没有存档可以导出。",
        share: "存储或发送文件", download: "下载文件", copy: "复制为文本", close: "关闭",
        copied: "已复制。到 Claude 页面版的“导入存档”里选“粘贴文本”。",
        copyFail: "复制失败，可以长按下面的文本手动复制。",
        importBody: "选择从旧版导出的备份文件。导入会替换这个页面里你现在的存档和设置。",
        hasSaves: "你在这里已经有存档，导入后会被替换。",
        choose: "选择备份文件", paste: "粘贴文本", pasteHint: "把导出的文本粘贴到这里", useText: "导入这段文本",
        notBackup: "这不是 River Runner 的备份文件。",
        found: function (n) { return "备份里有 " + n + " 项存档和设置。"; },
        replace: "替换我的存档", cancel: "取消",
        working: "正在保存到你的账户…",
        done: "导入完成，重新打开后就是备份里的存档。", reload: "现在重新打开",
        failed: "导入没有完成：", downloadFail: "没能保存文件。"
    } : {
        exportTitle: "Export saves", importTitle: "Import saves",
        exportBody: function (n, kb) { return "Every save slot, hand history and setting (" + n + " items, about " + kb + " KB) goes into one backup file. On the Claude page, open Settings, Import saves and pick this file to carry on."; },
        empty: "There are no saves here to export yet.",
        share: "Save or send the file", download: "Download the file", copy: "Copy as text", close: "Close",
        copied: "Copied. On the Claude page, open Import saves and choose Paste text.",
        copyFail: "Copying didn't work. Press and hold the text below to copy it.",
        importBody: "Pick the backup file you exported from the old trainer. It replaces the saves and settings on this page.",
        hasSaves: "You already have saves here, and they will be replaced.",
        choose: "Choose backup file", paste: "Paste text", pasteHint: "Paste the exported text here", useText: "Import this text",
        notBackup: "This isn't a River Runner backup file.",
        found: function (n) { return "The backup holds " + n + " saves and settings."; },
        replace: "Replace my saves", cancel: "Cancel",
        working: "Saving to your account…",
        done: "Import finished. Reopen the trainer to play from the backup.", reload: "Reopen now",
        failed: "The import didn't finish: ", downloadFail: "The file couldn't be saved."
    };

    var css = "#rr-panel{position:fixed;inset:0;z-index:1200;background:rgba(5,9,7,.72);display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box;touch-action:auto}" +
        "#rr-panel .box{background:#14201A;color:#E9EFEB;border:1px solid #2F4A3C;border-radius:16px;padding:18px;max-width:440px;width:100%;max-height:100%;overflow:auto;box-sizing:border-box;" +
        "font:15px/1.5 -apple-system,'PingFang SC',sans-serif;-webkit-user-select:text;user-select:text}" +
        "#rr-panel h2{margin:0 0 8px;font-size:19px;color:#E6B54F}#rr-panel p{margin:0 0 10px}#rr-panel .msg{color:#9DAAA2;font-size:14px}#rr-panel .bad{color:#F06A63}" +
        "#rr-panel .btns{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}" +
        "#rr-panel button,#rr-panel label.btn{font:600 15px -apple-system,'PingFang SC',sans-serif;min-height:44px;padding:0 16px;border-radius:12px;border:1px solid #2F4A3C;background:#1D2D25;color:#E9EFEB;cursor:pointer;display:inline-flex;align-items:center}" +
        "#rr-panel .main{background:#E6B54F;border-color:#E6B54F;color:#0C1310}" +
        "#rr-panel button:focus-visible,#rr-panel label.btn:focus-within{outline:3px solid #E6B54F;outline-offset:2px}" +
        "#rr-panel textarea{width:100%;box-sizing:border-box;min-height:90px;margin-top:8px;border-radius:10px;border:1px solid #2F4A3C;background:#0C1310;color:#E9EFEB;font:12px/1.4 ui-monospace,Menlo,monospace}" +
        "#rr-panel input[type=file]{position:absolute;width:1px;height:1px;opacity:0}";

    function el(tag, attrs, text) {
        var n = document.createElement(tag);
        if (attrs) for (var k in attrs) n.setAttribute(k, attrs[k]);
        if (text != null) n.textContent = text;
        return n;
    }
    function button(text, onClick, main) {
        var b = el("button", { type: "button" }, text);
        if (main) b.className = "main";
        b.addEventListener("click", onClick);
        return b;
    }
    function closePanel() { var p = document.getElementById("rr-panel"); if (p) p.remove(); }
    // Opens a panel and returns its box; each call replaces the previous panel.
    function panel(title) {
        closePanel();
        if (!document.getElementById("rr-panel-css")) { var s = el("style", { id: "rr-panel-css" }); s.textContent = css; document.head.appendChild(s); }
        var p = el("div", { id: "rr-panel", role: "dialog", "aria-modal": "true", "aria-label": title });
        var box = el("div", { "class": "box" });
        box.appendChild(el("h2", null, title));
        p.appendChild(box);
        // keep taps on the panel away from the app underneath
        ["touchstart", "touchmove", "touchend", "pointerdown", "pointerup", "mousedown", "mouseup", "wheel"].forEach(function (t) {
            p.addEventListener(t, function (e) { e.stopPropagation(); }, { passive: true });
        });
        p.addEventListener("click", function (e) { e.stopPropagation(); if (e.target === p) closePanel(); });
        document.body.appendChild(p);
        return box;
    }
    function msg(box, text, cls) { var m = el("p", { "class": cls || "msg", role: "status" }, text); box.appendChild(m); return m; }

    // ---- reading the saves ----
    function collect() {
        var items = {};
        var st = window.__rrStore;
        if (st && typeof st.keys === "function") {
            st.keys().forEach(function (k) { var v = st.getItem(k); if (v != null) items[k] = v; });
            return items;
        }
        try {
            var ls = window.localStorage;
            for (var i = 0; i < ls.length; i++) { var k = ls.key(i); if (k && k.indexOf("rr.") === 0) items[k] = ls.getItem(k); }
        } catch (e) {}
        return items;
    }
    function fileText(items) { return JSON.stringify({ app: "river-runner", format: 1, exported: new Date().toISOString(), items: items }); }
    function fileName() {
        var d = new Date(), p = function (n) { return (n < 10 ? "0" : "") + n; };
        return "river-runner-saves-" + d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + ".json";
    }

    window.__rrExport = function () {
        var items = collect(), n = Object.keys(items).length;
        var box = panel(T.exportTitle);
        if (!n) { msg(box, T.empty, ""); var b0 = el("div", { "class": "btns" }); b0.appendChild(button(T.close, closePanel, true)); box.appendChild(b0); return; }
        var text = fileText(items), name = fileName();
        msg(box, T.exportBody(n, Math.max(1, Math.round(text.length / 1024))), "");
        var status = msg(box, "");
        var btns = el("div", { "class": "btns" });
        var file = null;
        try { file = new File([text], name, { type: "application/json" }); } catch (e) {}
        if (window.__rrDownload) {
            btns.appendChild(button(T.download, function () {
                window.__rrDownload(name, text).then(null, function () { status.textContent = T.downloadFail; status.className = "bad"; });
            }, true));
        } else {
            if (file && navigator.canShare && navigator.canShare({ files: [file] })) {
                btns.appendChild(button(T.share, function () { navigator.share({ files: [file] }).catch(function () {}); }, true));
            }
            btns.appendChild(button(T.download, function () {
                var a = el("a", { href: URL.createObjectURL(new Blob([text], { type: "application/json" })), download: name });
                document.body.appendChild(a); a.click(); a.remove();
            }, !(file && navigator.canShare && navigator.canShare({ files: [file] }))));
        }
        var shown = null;
        btns.appendChild(button(T.copy, function () {
            var ok = function () { status.textContent = T.copied; status.className = "msg"; };
            var fail = function () {
                status.textContent = T.copyFail; status.className = "bad";
                if (!shown) { shown = el("textarea", { readonly: "readonly", "aria-label": T.copy }); shown.value = text; box.appendChild(shown); }
                shown.focus(); shown.select();
            };
            try { navigator.clipboard.writeText(text).then(ok, fail); } catch (e) { fail(); }
        }));
        btns.appendChild(button(T.close, closePanel));
        box.appendChild(btns);
    };

    // ---- import (only where the page can take one) ----
    function parseBackup(text) {
        var o;
        try { o = JSON.parse(text); } catch (e) { return null; }
        if (!o || o.app !== "river-runner" || typeof o.items !== "object" || !o.items) return null;
        var items = {}, n = 0;
        for (var k in o.items) {
            if (k.indexOf("rr.") !== 0 || typeof o.items[k] !== "string") continue;
            items[k] = o.items[k]; n++;
        }
        return n ? items : null;
    }

    function confirmImport(items) {
        var n = Object.keys(items).length;
        var box = panel(T.importTitle);
        msg(box, T.found(n), "");
        if (Object.keys(collect()).some(function (k) { return k.indexOf("rr.file.") === 0; })) msg(box, T.hasSaves, "");
        var btns = el("div", { "class": "btns" });
        var go = button(T.replace, function () {
            btns.remove();
            var st = msg(box, T.working);
            window.__rrImportTarget(items).then(function () {
                st.textContent = T.done;
                var b2 = el("div", { "class": "btns" });
                b2.appendChild(button(T.reload, function () { location.reload(); }, true));
                box.appendChild(b2);
            }, function (e) {
                st.textContent = T.failed + ((e && (e.message || e.code)) || e); st.className = "bad";
                var b3 = el("div", { "class": "btns" }); b3.appendChild(button(T.close, closePanel)); box.appendChild(b3);
            });
        }, true);
        btns.appendChild(go);
        btns.appendChild(button(T.cancel, closePanel));
        box.appendChild(btns);
    }

    function openImport() {
        var box = panel(T.importTitle);
        msg(box, T.importBody, "");
        var status = msg(box, "");
        var btns = el("div", { "class": "btns" });
        var label = el("label", { "class": "btn main" }, T.choose);
        var input = el("input", { type: "file", accept: ".json,application/json,text/plain" });
        label.appendChild(input);
        input.addEventListener("change", function () {
            var f = input.files && input.files[0];
            if (!f) return;
            var r = new FileReader();
            r.onload = function () {
                var items = parseBackup(String(r.result || ""));
                if (!items) { status.textContent = T.notBackup; status.className = "bad"; return; }
                confirmImport(items);
            };
            r.readAsText(f);
        });
        btns.appendChild(label);
        btns.appendChild(button(T.paste, function () {
            if (box.querySelector("textarea")) return;
            var ta = el("textarea", { placeholder: T.pasteHint, "aria-label": T.pasteHint });
            box.insertBefore(ta, btns);
            var use = button(T.useText, function () {
                var items = parseBackup(ta.value.trim());
                if (!items) { status.textContent = T.notBackup; status.className = "bad"; return; }
                confirmImport(items);
            });
            btns.insertBefore(use, btns.lastChild);
            ta.focus();
        }));
        btns.appendChild(button(T.cancel, closePanel));
        box.appendChild(btns);
    }
    if (typeof window.__rrImportTarget === "function") window.__rrImport = openImport;
})();
