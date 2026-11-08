/* 手势浏览器 · 网页内置播放器
 * 给网页里的 <video> 盖上自己的控制层：点播放直接播放，不经过网站自己的按钮（避免触发跳转广告）。
 * 没播放前不显示，网站自己的播放按钮照常可点；网页视频一开始播放，内置播放器才接管画面并隐藏网站控件。
 * 拖动进度条时上方显示该时间的画面（后台预先截图，m3u8 用最低画质）。
 * 单击：暂停时播放 / 播放时显示隐藏控制栏；双击左侧后退 10 秒、右侧快进 10 秒、中间暂停播放。
 */
(function () {
  if (window.__gbp) return;
  window.__gbp = 1;

  var css = [
    '.gbp{position:fixed;z-index:2147483646;pointer-events:auto;-webkit-tap-highlight-color:transparent;user-select:none;-webkit-user-select:none;font-family:system-ui,sans-serif;color:#fff;touch-action:pan-y;}',
    '.gbp *{box-sizing:border-box;}',
    '.gbp-center{position:absolute;left:50%;top:50%;width:64px;height:64px;margin:-32px 0 0 -32px;border-radius:50%;background:rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;transition:opacity .2s;}',
    '.gbp-center svg{width:30px;height:30px;margin-left:4px;fill:#fff;}',
    '.gbp-hint{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);padding:8px 16px;border-radius:18px;background:rgba(0,0,0,.6);font-size:15px;font-weight:600;opacity:0;transition:opacity .2s;white-space:nowrap;}',
    '.gbp-bar{position:absolute;left:0;right:0;bottom:0;height:52px;padding:14px 8px 0;display:flex;align-items:center;gap:6px;background:linear-gradient(transparent,rgba(0,0,0,.65));transition:opacity .25s;}',
    '.gbp-btn{width:36px;height:36px;flex:none;display:flex;align-items:center;justify-content:center;}',
    '.gbp-btn svg{width:24px;height:24px;fill:#fff;}',
    '.gbp-time{font-size:12.5px;flex:none;font-variant-numeric:tabular-nums;text-shadow:0 1px 2px rgba(0,0,0,.6);}',
    '.gbp-track{position:relative;flex:1;height:30px;display:flex;align-items:center;}',
    '.gbp-rail{position:absolute;left:0;right:0;height:3px;border-radius:2px;background:rgba(255,255,255,.35);}',
    '.gbp-buf{position:absolute;left:0;height:3px;border-radius:2px;background:rgba(255,255,255,.55);}',
    '.gbp-fill{position:absolute;left:0;height:3px;border-radius:2px;background:#fff;}',
    '.gbp-knob{position:absolute;width:13px;height:13px;margin-left:-6.5px;border-radius:50%;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.4);}',
    '.gbp.gbp-hide .gbp-bar{opacity:0;pointer-events:none;}',
    '.gbp-pv{position:absolute;bottom:50px;width:196px;display:none;flex-direction:column;align-items:center;pointer-events:none;}',
    '.gbp-pv-img{width:196px;height:110px;border-radius:8px;border:2px solid rgba(255,255,255,.9);background:#000 center/cover no-repeat;box-shadow:0 2px 8px rgba(0,0,0,.5);}',
    '.gbp-pv-t{margin-top:4px;font-size:13px;font-weight:600;text-shadow:0 1px 3px rgba(0,0,0,.8);}',
    '.gbp.gbp-playing .gbp-center{opacity:0;pointer-events:none;}'
  ].join('');
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  var ICON = {
    play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>',
    vol: '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z"/></svg>',
    mute: '<svg viewBox="0 0 24 24"><path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.2l2.45 2.45c.03-.2.05-.42.05-.65zM19 12c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12a9 9 0 0 0-7-8.77v2.06A7 7 0 0 1 19 12zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a9 9 0 0 0 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4 9.91 6.09 12 8.18V4z"/></svg>',
    fs: '<svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>'
  };


  // ================= 拖动进度条时的画面预览（仿 UC）=================
  // 播放开始后在后台用一个隐藏的视频（m3u8 用最低画质）按时间间隔预先截图，拖动时立即显示最近一张，再补上精确画面。
  var GBT = (function () {
    var cur = null, cbSeq = 0, cbs = {};
    window.__gbtCb = function (id, img) { var f = cbs[id]; delete cbs[id]; if (f) f(img || null); };

    function findSource(v) {
      var s = v.currentSrc || v.src || '';
      if (s && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0) return { type: 'direct', url: s };
      var es = performance.getEntriesByType ? performance.getEntriesByType('resource') : [], first = null;
      for (var i = 0; i < es.length; i++) if (/\.m3u8(\?|#|$)/i.test(es[i].name)) { first = es[i].name; break; }
      return first ? { type: 'hls', url: first } : null;
    }
    function getHls() {
      if (window.__GBHls) return window.__GBHls;
      if (window.Hls && window.Hls.isSupported && window.Hls.isSupported()) return window.Hls;
      try {
        var old = window.Hls;
        (0, eval)(GBPlayer.hlsLib());
        window.__GBHls = window.Hls; window.Hls = old;
        return window.__GBHls;
      } catch (e) { return null; }
    }

    function Engine(v) {
      this.v = v; this.list = []; this.want = null; this.busy = false; this.dead = false;
      this.order = null; this.oi = 0; this.pv = null; this.hls = null; this.native = false;
      this.token = 0; this.cancel = null; this.blackCount = 0; this.details = null;
      this.canvas = document.createElement('canvas');
      this.holdUntil = 0; this.bgCount = 0;
      var self = this;
      // 主视频一卡（缓冲不够）就让后台预取让路 8 秒，网速优先给正在看的视频
      v.addEventListener('waiting', function () { self.holdUntil = Date.now() + 8000; });
      v.addEventListener('stalled', function () { self.holdUntil = Date.now() + 8000; });
    }
    // 后台预取是否该让路：主视频在播且前方缓冲不足 20 秒、刚卡过、或页面不可见
    Engine.prototype.shouldYield = function () {
      if (document.hidden) return true;
      if (Date.now() < this.holdUntil) return true;
      var v = this.v;
      if (v.paused) return false;
      try {
        var b = v.buffered, t = v.currentTime, ahead = 0;
        for (var i = 0; i < b.length; i++) if (b.start(i) <= t + 0.5 && b.end(i) > t) ahead = b.end(i) - t;
        var left = (this.dur || v.duration) - t;
        return ahead < Math.min(20, Math.max(0, left - 1));
      } catch (e) { return false; }
    };
    Engine.prototype.setup = function () {
      var d = this.v.duration;
      if (!isFinite(d) || d <= 0) return false;
      if (this.src) return true;
      var src = findSource(this.v);
      if (!src) return false;
      this.src = src; this.dur = d;
      this.step = Math.max(2, Math.min(20, d / 300));
      var n = Math.floor(d / this.step) + 1, ord = [], seen = {};
      for (var g = 32; g >= 1; g = g / 2) for (var b = 0; b < n; b += g) if (!seen[b]) { seen[b] = 1; ord.push(b * this.step); }
      this.order = ord.slice(0, 80);          // 后台最多预取 80 张，其余拖到时现取
      var pv = document.createElement('video');
      pv.muted = true; pv.preload = 'auto'; pv.playsInline = true; pv.setAttribute('playsinline', '');
      pv.__gbp = 1; pv.__gbpv = 1; pv.setAttribute('data-gbpv', '1');
      // 必须留在屏幕内且可见，WebView 才会真正解码出画面（否则截出来是黑的）
      pv.style.cssText = 'position:fixed;right:0;bottom:0;width:32px;height:18px;opacity:0.01;pointer-events:none;z-index:2147483646;object-fit:contain;';
      document.documentElement.appendChild(pv);
      this.pv = pv;
      var self = this;
      if (src.type === 'direct') {
        pv.crossOrigin = 'anonymous';
        pv.addEventListener('error', function () { self.native = true; }, { once: true });
        pv.src = src.url;
      } else {
        var H = getHls();
        if (!H) { this.dead = true; return false; }
        var h = new H({ startLevel: 0, maxBufferLength: 2, maxMaxBufferLength: 4, backBufferLength: 0,
                        capLevelToPlayerSize: false, enableWorker: true });
        h.on(H.Events.MANIFEST_PARSED, function (ev, data) {
          // 预览画质：选最接近 480p 的清晰度（没有就用最低）
          var lv = data.levels || h.levels || [], best = 0, bd = 1e9;
          for (var i = 0; i < lv.length; i++) { var dd = Math.abs((lv[i].height || 0) - 480); if (dd < bd) { bd = dd; best = i; } }
          try { h.currentLevel = best; h.loadLevel = best; } catch (e) {}
        });
        h.on(H.Events.ERROR, function (ev, data) { if (data && data.fatal) self.dead = true; });
        h.loadSource(src.url); h.attachMedia(pv);
        this.hls = h;
        h.on(H.Events.LEVEL_LOADED, function (ev, data) { self.details = data.details; });
      }
      // 先静音播放一下再暂停，部分机型不播放一次就截不到画面
      pv.addEventListener('loadeddata', function () {
        var pr = pv.play(); if (pr && pr.then) pr.then(function () { pv.pause(); }, function () {}); else pv.pause();
      }, { once: true });
      return true;
    };
    Engine.prototype.destroy = function () {
      this.dead = true;
      try { if (this.hls) this.hls.destroy(); } catch (e) {}
      try { if (this.pv) { this.pv.removeAttribute('src'); this.pv.load(); this.pv.remove(); } } catch (e) {}
    };
    Engine.prototype.add = function (t, img) {
      var l = this.list, i = 0;
      while (i < l.length && l[i].t < t) i++;
      if (l[i] && Math.abs(l[i].t - t) < 0.3) l[i].img = img; else l.splice(i, 0, { t: t, img: img });
    };
    Engine.prototype.nearest = function (t) {
      var l = this.list, lo = 0, hi = l.length - 1;
      if (hi < 0) return null;
      while (lo < hi) { var m = (lo + hi) >> 1; if (l[m].t < t) lo = m + 1; else hi = m; }
      var c = l[lo];
      if (lo > 0 && Math.abs(l[lo - 1].t - t) < Math.abs(c.t - t)) c = l[lo - 1];
      return c;
    };
    Engine.prototype.capture = function (t, done) {
      var self = this;
      if (this.native) {
        var id = ++cbSeq, url = this.src.url, off = t;
        if (this.src.type === 'hls') {            // m3u8：找到这个时间所在的分片，交给原生解码
          var fr = this.details && this.details.fragments, f = null;
          if (fr) for (var i = 0; i < fr.length; i++) if (t >= fr[i].start && t < fr[i].start + fr[i].duration) { f = fr[i]; break; }
          if (!f || !f.url || (f.decryptdata && f.decryptdata.method && f.decryptdata.method !== 'NONE')) { done(null); return; }
          url = f.url; off = Math.max(0, t - f.start);
        }
        cbs[id] = done;
        try { GBPlayer.frame(id, url, off); } catch (e) { delete cbs[id]; done(null); }
        this.cancel = null;
        return;
      }
      var pv = this.pv, fin = false, tm = 0;
      function finish(img) {
        if (fin) return; fin = true; clearTimeout(tm);
        pv.removeEventListener('seeked', onSeeked); self.cancel = null; done(img);
      }
      var tries = 0;
      function onSeeked() {
        if (pv.requestVideoFrameCallback) {
          var ok = false;
          pv.requestVideoFrameCallback(function () { ok = true; grab(); });
          setTimeout(function () { if (!ok) grab(); }, 350);
        } else requestAnimationFrame(function () { requestAnimationFrame(grab); });
      }
      function grab() {
          if (fin) return;
          try {
            var w = pv.videoWidth, h = pv.videoHeight;
            if (!w || !h) return finish(null);
            var cw = 360, ch = Math.round(cw * h / w);
            self.canvas.width = cw; self.canvas.height = ch;
            var ctx = self.canvas.getContext('2d');
            ctx.imageSmoothingQuality = 'high';
            ctx.drawImage(pv, 0, 0, cw, ch);
            if (isBlack(ctx, cw, ch)) {
              if (++tries < 3) { setTimeout(grab, 200); return; }
              // 网页里截出来一直是黑的：改用原生解码
              if (++self.blackCount >= 2) self.native = true;
              return finish(null);
            }
            self.blackCount = 0;
            finish(self.canvas.toDataURL('image/jpeg', 0.82));
          } catch (e) {                       // 画面跨域被保护：改用原生解码
            self.native = true;
            finish(null);
          }
      }
      pv.addEventListener('seeked', onSeeked);
      this.cancel = function () { finish(undefined); };
      tm = setTimeout(function () { finish(null); }, 8000);
      try { pv.currentTime = Math.max(0, Math.min(t, (self.dur || t + 1) - 0.3)); } catch (e) { finish(null); }
    };
    function isBlack(ctx, w, h) {
      try {
        var d = ctx.getImageData(0, 0, w, h).data, sum = 0, n = 0, step = Math.max(4, Math.floor(d.length / 4 / 400)) * 4;
        for (var i = 0; i < d.length; i += step) { sum += d[i] + d[i + 1] + d[i + 2]; n++; }
        return sum / (n * 3) < 6;
      } catch (e) { throw e; }
    }
    Engine.prototype.pump = function () {
      if (this.dead || (!this.pv && !this.setup()) || this.dead || this.busy) return;
      var t = null, demand = false;
      var self = this;
      if (this.want != null) { t = this.want; this.want = null; demand = true; }
      else if (this.oi < this.order.length) {
        if (this.shouldYield()) { clearTimeout(this.yieldTm); this.yieldTm = setTimeout(function () { self.pump(); }, 1500); return; }
        t = this.order[this.oi++];
      }
      if (t == null) return;
      this.busy = true; this.busyDemand = demand;
      this.capture(t, function (img) {
        self.busy = false;
        if (img) self.add(t, img);
        if (img === undefined && !demand) self.oi--;          // 被拖动打断的预取，稍后重做
        // 后台预取每张间隔 0.6 秒，不和主视频抢网速；拖动要的画面立即取
        if (!self.dead) setTimeout(function () { self.pump(); }, self.want != null ? 0 : (img ? 600 : 1200));
      });
    };
    // 拖动时：要的画面优先，打断正在进行的后台预取，直接把预览视频跳到这个时间
    Engine.prototype.get = function (t) {
      if (!this.pv && !this.setup()) return null;
      var c = this.nearest(t);
      if (!c || Math.abs(c.t - t) > 0.6) {
        this.want = t;
        if (this.busy && !this.busyDemand && this.cancel) this.cancel();
        else this.pump();
      }
      // 只显示离目标时间足够近的画面，差太远宁可先空着等精确画面，免得显示错的时间点
      var tol = Math.max(2, (this.step || 2) * 0.75);
      return c && Math.abs(c.t - t) <= tol ? c.img : null;
    };
    function forVideo(v) {
      if (cur && cur.v === v && !cur.dead) return cur;
      if (cur) cur.destroy();
      cur = new Engine(v);
      return cur;
    }
    return {
      start: function (v) { var e = forVideo(v); setTimeout(function () { e.pump(); }, 2500); },
      get: function (v, t) { return forVideo(v).get(t); }
    };
  })();
  window.__gbt = GBT;

  function fmt(s) {
    if (!isFinite(s) || s < 0) s = 0;
    s = Math.floor(s);
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return (h ? h + ':' + p(m) : p(m)) + ':' + p(x);
  }
  function tapNative() { try { GBPlayer.tap(); } catch (e) {} }

  /** 把网站自己盖在视频上的按钮、控制栏藏起来（只藏和视频同尺寸的播放器容器里的东西） */
  function hideSiteUi(v) {
    v.controls = false;
    var vr = v.getBoundingClientRect(), box = v, n = v.parentElement;
    for (var i = 0; i < 6 && n && n !== document.body; i++, n = n.parentElement) {
      var r = n.getBoundingClientRect();
      if (Math.abs(r.left - vr.left) < 40 && Math.abs(r.top - vr.top) < 40 &&
          Math.abs(r.width - vr.width) < 60 && Math.abs(r.height - vr.height) < 80) box = n;
      else break;
    }
    (function walk(node) {
      for (var c = node.firstElementChild; c; c = c.nextElementSibling) {
        if (c === v) continue;
        if (c.contains(v)) walk(c);
        else if (c.tagName !== 'SOURCE' && c.tagName !== 'TRACK') c.style.setProperty('visibility', 'hidden', 'important');
      }
    })(box);
  }

  function attach(v) {
    v.__gbp = 1;
    var o = document.createElement('div');
    o.className = 'gbp gbp-hide';
    o.innerHTML =
      '<div class="gbp-center">' + ICON.play + '</div>' +
      '<div class="gbp-hint"></div>' +
      '<div class="gbp-pv"><div class="gbp-pv-img"></div><div class="gbp-pv-t"></div></div>' +
      '<div class="gbp-bar">' +
        '<div class="gbp-btn gbp-pp">' + ICON.play + '</div>' +
        '<span class="gbp-time gbp-cur">00:00</span>' +
        '<div class="gbp-track"><div class="gbp-rail"></div><div class="gbp-buf"></div><div class="gbp-fill"></div><div class="gbp-knob"></div></div>' +
        '<span class="gbp-time gbp-dur">00:00</span>' +
        '<div class="gbp-btn gbp-mute">' + ICON.vol + '</div>' +
        '<div class="gbp-btn gbp-fs">' + ICON.fs + '</div>' +
      '</div>';
    document.documentElement.appendChild(o);
    var $ = function (s) { return o.querySelector(s); };
    var pp = $('.gbp-pp'), cur = $('.gbp-cur'), dur = $('.gbp-dur'), track = $('.gbp-track'),
        fill = $('.gbp-fill'), knob = $('.gbp-knob'), buf = $('.gbp-buf'), hint = $('.gbp-hint'),
        mute = $('.gbp-mute'), center = $('.gbp-center'),
        pvBox = $('.gbp-pv'), pvImg = $('.gbp-pv-img'), pvT = $('.gbp-pv-t'), pvTimer = 0;
    var hideTimer = 0, hintTimer = 0, seeking = false, seekFrac = 0, siteHidden = false, armed = !v.paused;

    function place() {
      var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
      var r = v.getBoundingClientRect();
      var visible = !fsEl && r.width >= 160 && r.height >= 90 && r.bottom > 0 && r.top < innerHeight &&
                    getComputedStyle(v).visibility !== 'hidden' && document.contains(v);
      if (!document.contains(v)) { o.remove(); return; }
      if (!armed) visible = false;            // 还没点网站的播放按钮：不接管
      o.style.display = visible ? 'block' : 'none';
      if (!visible) return;
      o.style.left = r.left + 'px'; o.style.top = r.top + 'px';
      o.style.width = r.width + 'px'; o.style.height = r.height + 'px';
      if (!siteHidden) { siteHidden = true; hideSiteUi(v); }
    }
    function render() {
      var d = v.duration, t = seeking ? seekFrac * (d || 0) : v.currentTime;
      var f = d ? Math.min(1, t / d) : 0;
      fill.style.width = (f * 100) + '%';
      knob.style.left = (f * 100) + '%';
      cur.textContent = fmt(t);
      dur.textContent = fmt(d);
      try { if (v.buffered.length && d) buf.style.width = (v.buffered.end(v.buffered.length - 1) / d * 100) + '%'; } catch (e) {}
      pp.innerHTML = v.paused ? ICON.play : ICON.pause;
      mute.innerHTML = v.muted ? ICON.mute : ICON.vol;
      o.classList.toggle('gbp-playing', !v.paused);
    }
    function showBar() {
      o.classList.remove('gbp-hide');
      clearTimeout(hideTimer);
      hideTimer = setTimeout(function () { if (!v.paused && !seeking) o.classList.add('gbp-hide'); }, 3500);
    }
    function flash(text) {
      hint.textContent = text; hint.style.opacity = 1;
      clearTimeout(hintTimer);
      hintTimer = setTimeout(function () { hint.style.opacity = 0; }, 700);
    }
    function play() { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    function toggle() { if (v.paused) play(); else v.pause(); }
    function skip(s) {
      v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 0.5, v.currentTime + s));
      flash((s > 0 ? '快进 ' : '后退 ') + Math.abs(s) + ' 秒');
    }

    ['play', 'pause', 'timeupdate', 'durationchange', 'progress', 'volumechange', 'loadedmetadata'].forEach(function (ev) {
      v.addEventListener(ev, render);
    });
    v.addEventListener('play', function () {
      if (!armed) { armed = true; place(); render(); }
      GBT.start(v);
      showBar();
    });
    v.addEventListener('pause', function () { o.classList.remove('gbp-hide'); });

    // 所有触摸都在控制层里消化掉，不传给网站（网站的广告点击监听拿不到）
    ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mouseup', 'click', 'pointerdown', 'pointerup', 'contextmenu'].forEach(function (ev) {
      o.addEventListener(ev, function (e) {
        e.stopPropagation();
        if (ev === 'click' || ev === 'mousedown' || ev === 'mouseup' || ev === 'contextmenu') e.preventDefault();
      }, false);
    });

    var sx = 0, sy = 0, moved = false, lastTap = 0, lastX = 0, singleTimer = 0;
    o.addEventListener('touchstart', function (e) {
      tapNative();
      var t = e.touches[0]; sx = t.clientX; sy = t.clientY; moved = false;
    }, { passive: true });
    o.addEventListener('touchmove', function (e) {
      var t = e.touches[0];
      if (Math.abs(t.clientX - sx) > 10 || Math.abs(t.clientY - sy) > 10) moved = true;
    }, { passive: true });
    o.addEventListener('touchend', function (e) {
      if (moved || seeking) return;
      e.preventDefault();           // 不再合成 click，网站拿不到点击
      var target = e.target, now = Date.now();
      if (pp.contains(target)) { toggle(); showBar(); return; }
      if (mute.contains(target)) { v.muted = !v.muted; showBar(); return; }
      if (target.closest && target.closest('.gbp-fs')) {
        var fn = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen;
        if (fn) { var r = fn.call(v); if (r && r.catch) r.catch(function () {}); }
        return;
      }
      if (track.contains(target)) return;
      if (center.contains(target) && v.paused) { play(); return; }
      var w = o.getBoundingClientRect(), x = sx - w.left;
      if (now - lastTap < 300 && Math.abs(sx - lastX) < 80) {   // 双击
        clearTimeout(singleTimer); lastTap = 0;
        if (x < w.width / 3) skip(-10);
        else if (x > w.width * 2 / 3) skip(10);
        else { toggle(); flash(v.paused ? '已暂停' : '播放'); }
        return;
      }
      lastTap = now; lastX = sx;
      singleTimer = setTimeout(function () {                      // 单击
        if (v.paused) play();
        else if (o.classList.contains('gbp-hide')) showBar();
        else o.classList.add('gbp-hide');
      }, 300);
    }, { passive: false });

    // 拖动时在进度条上方显示该时间的画面
    function showPreview() {
      var d = v.duration; if (!d) return;
      var t = seekFrac * d, w = o.getBoundingClientRect().width, tr = track.getBoundingClientRect(), ow = o.getBoundingClientRect();
      var x = tr.left - ow.left + seekFrac * tr.width - 98;
      pvBox.style.left = Math.max(4, Math.min(w - 200, x)) + 'px';
      pvBox.style.display = 'flex';
      pvT.textContent = fmt(t) + ' / ' + fmt(d);
      var img = GBT.get(v, t);
      if (img) pvImg.style.backgroundImage = 'url(' + img + ')';
      clearTimeout(pvTimer);
      pvTimer = setTimeout(function () { if (seeking) showPreview(); }, 120);   // 精确画面到了就换上
    }
    function hidePreview() { clearTimeout(pvTimer); pvBox.style.display = 'none'; }

    // 进度条拖动
    function fracAt(clientX) {
      var r = track.getBoundingClientRect();
      return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
    }
    track.addEventListener('touchstart', function (e) {
      seeking = true; seekFrac = fracAt(e.touches[0].clientX); pvImg.style.backgroundImage = 'none'; render(); showBar(); showPreview();
    }, { passive: true });
    track.addEventListener('touchmove', function (e) {
      e.preventDefault(); seekFrac = fracAt(e.touches[0].clientX); render(); showPreview();
    }, { passive: false });
    track.addEventListener('touchend', function (e) {
      e.preventDefault();
      if (seeking && v.duration) v.currentTime = seekFrac * v.duration;
      seeking = false; render(); showBar(); hidePreview();
    }, { passive: false });

    place(); render();
    var iv = setInterval(function () { if (!document.contains(o)) { clearInterval(iv); v.__gbp = 0; return; } place(); }, 250);
    addEventListener('scroll', place, { passive: true, capture: true });
    addEventListener('resize', place);
  }

  // 接管前用户点的是网站自己的播放按钮：在视频区域内的触摸也通知原生层，2 秒内的跳转广告照样拦截
  addEventListener('touchstart', function (e) {
    var t = e.touches[0], list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) {
      if (list[i].__gbpv) continue;
      var r = list[i].getBoundingClientRect();
      if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) { tapNative(); return; }
    }
  }, { capture: true, passive: true });

  function scan() {
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (v.__gbp || v.__gbpv || v.hasAttribute('data-gbpv')) continue;
      var r = v.getBoundingClientRect();
      if (r.width >= 160 && r.height >= 90) attach(v);
    }
  }
  scan();
  // 不再每秒全页扫描：页面有新节点或视频开始加载时再查（节流 400ms），另留 4 秒一次的兜底
  var scanT = 0;
  function scanSoon() { if (!scanT) scanT = setTimeout(function () { scanT = 0; scan(); }, 400); }
  try { new MutationObserver(function (ms) {
    for (var i = 0; i < ms.length; i++) for (var j = 0, n = ms[i].addedNodes; j < n.length; j++) {
      var x = n[j]; if (x.nodeType === 1 && (x.tagName === 'VIDEO' || (x.getElementsByTagName && x.getElementsByTagName('video').length))) { scanSoon(); return; }
    }
  }).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  ['loadedmetadata', 'play', 'resize'].forEach(function (ev) { document.addEventListener(ev, scanSoon, true); });
  setInterval(scan, 4000);
})();
