(function () {
  'use strict';
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* 1. Scroll reveal with staggered children */
  var targets = document.querySelectorAll('.reveal');
  targets.forEach(function (sec) {
    sec.querySelectorAll('.card, .shot, .steps li, .checks li').forEach(function (el, i) {
      el.style.setProperty('--i', i);
    });
  });
  if ('IntersectionObserver' in window && !reduce) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    targets.forEach(function (t) { io.observe(t); });
  } else {
    targets.forEach(function (t) { t.classList.add('in'); });
  }

  /* 2. Touch ripple on buttons */
  document.addEventListener('pointerdown', function (e) {
    var btn = e.target.closest('.btn');
    if (!btn || reduce) return;
    var r = btn.getBoundingClientRect();
    var size = Math.max(r.width, r.height) * 2.2;
    var s = document.createElement('span');
    s.className = 'ripple';
    s.style.width = s.style.height = size + 'px';
    s.style.left = (e.clientX - r.left - size / 2) + 'px';
    s.style.top = (e.clientY - r.top - size / 2) + 'px';
    btn.appendChild(s);
    setTimeout(function () { s.remove(); }, 700);
  });

  /* 3. Glow that follows finger or mouse on cards and screenshots, plus a brief touch state */
  function track(el, e) {
    var r = el.getBoundingClientRect();
    el.style.setProperty('--mx', (e.clientX - r.left) + 'px');
    el.style.setProperty('--my', (e.clientY - r.top) + 'px');
  }
  document.querySelectorAll('.card, .shot').forEach(function (el) {
    el.addEventListener('pointermove', function (e) { track(el, e); });
    el.addEventListener('pointerdown', function (e) {
      track(el, e);
      el.classList.add('touched');
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) {
      el.addEventListener(ev, function () { setTimeout(function () { el.classList.remove('touched'); }, 250); });
    });
  });

  /* 4. Haptic tick on supported phones */
  document.addEventListener('pointerdown', function (e) {
    if (e.target.closest('.btn, .card, .shot') && navigator.vibrate) navigator.vibrate(8);
  });

  /* 5. Header: glass deepens on scroll, hides on scroll down, returns on scroll up */
  var bar = document.getElementById('topbar');
  var lastY = window.scrollY, ticking = false;
  window.addEventListener('scroll', function () {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function () {
      var y = window.scrollY;
      bar.classList.toggle('scrolled', y > 10);
      bar.classList.toggle('hide', y > lastY && y > 220);
      lastY = y;
      ticking = false;
    });
  }, { passive: true });

  /* 6. Screenshot lightbox */
  var box = document.getElementById('lightbox');
  if (box && box.showModal) {
    var big = box.querySelector('img');
    document.querySelectorAll('.shot').forEach(function (fig) {
      fig.addEventListener('click', function () {
        var img = fig.querySelector('img');
        big.src = img.currentSrc || img.src;
        big.alt = img.alt;
        box.showModal();
      });
    });
    box.addEventListener('click', function () { box.close(); });
  }
})();
