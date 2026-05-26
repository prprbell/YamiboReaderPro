package org.shirakawatyu.yamibo.novel.util

import org.shirakawatyu.yamibo.novel.util.theme.DARK_MODE_CSS_RULES_CLASSIC
import org.shirakawatyu.yamibo.novel.util.theme.DARK_MODE_CSS_RULES_OKLCH
import org.shirakawatyu.yamibo.novel.util.theme.DARK_MODE_CSS_RULES_OLED
import org.shirakawatyu.yamibo.novel.util.theme.DARK_MODE_CSS_RULES_TWILIGHT

object PageJsScripts {

    // 基础脚本

    val PJAX_FALLBACK_JS = """
        (function() {
            if (window.__yamiboNavGuardV2) return;
            window.__yamiboNavGuardV2 = true;
    
            var pendingTimer = null;
            var downPoint = null;
    
            function clearPendingTimer() {
                if (pendingTimer) {
                    clearTimeout(pendingTimer);
                    pendingTimer = null;
                }
            }
    
            function closest(el, selector) {
                while (el && el !== document && el.nodeType === 1) {
                    if (el.matches && el.matches(selector)) return el;
                    el = el.parentElement;
                }
                return null;
            }
    
            function getPoint(e) {
                if (e.changedTouches && e.changedTouches.length > 0) return e.changedTouches[0];
                if (e.touches && e.touches.length > 0) return e.touches[0];
                return e;
            }
    
            function normalizeUrl(rawUrl) {
                try {
                    return new URL(rawUrl, document.baseURI);
                } catch (err) {
                    return null;
                }
            }
    
            // 究极兜底策略：只认“帖子”和“版块”
            function isSafeBbsNavigation(a, url) {
                if (!a || !url) return false;
                
                var rawHref = String(a.getAttribute('href') || '').trim();
                if (!rawHref || rawHref === '#' || /^javascript:/i.test(rawHref)) return false;
    
                if (url.hostname !== 'bbs.yamibo.com') return false;
    
                // 排除带有弹窗类名的元素
                if (a.classList && a.classList.contains('dialog')) return false;
                if (a.hasAttribute('data-pswp-width') || closest(a, '.pswp')) return false;
    
                var query = String(url.search || '').toLowerCase();
                var path = String(url.pathname || '').replace(/^\/+/, '').toLowerCase();
    
                // 排除明显的异步请求或操作动作
                if (/(\?|&)(inajax|action|ac|formhash)=/.test(query)) return false;
    
                // ==========================================
                // 核心白名单：只针对下面这两种核心链接执行 800ms 兜底跳转
                // ==========================================
                var isThread = /^thread-\d+/.test(path) || (path === 'forum.php' && query.indexOf('mod=viewthread') !== -1);
                var isForum = /^forum-\d+/.test(path) || (path === 'forum.php' && query.indexOf('mod=forumdisplay') !== -1);
    
                if (!isThread && !isForum) {
                    return false; 
                }
    
                // 纯锚点跳转（楼层跳转）不处理
                var currentNoHash = location.href.split('#')[0];
                var targetNoHash = url.href.split('#')[0];
                if (currentNoHash === targetNoHash) return false;
    
                return true;
            }
    
            function scheduleFallback(a, reason) {
                var url = normalizeUrl(a && a.href);
                if (!isSafeBbsNavigation(a, url)) return;
    
                var before = location.href;
                var targetUrl = url.href;
    
                if (!targetUrl || targetUrl === before) return;
    
                clearPendingTimer();
    
                pendingTimer = setTimeout(function() {
                    pendingTimer = null;
    
                    if (location.href !== before) return;
    
                    try {
                        console.log('[YamiboNavGuard] fallback navigate by ' + reason + ': ' + targetUrl);
                    } catch (_) {}
    
                    try {
                        location.assign(targetUrl);
                    } catch (err) {
                        location.href = targetUrl;
                    }
                }, 800);
            }
    
            document.addEventListener('click', function(e) {
                if (e.button && e.button !== 0) return;
                var a = closest(e.target, 'a[href]');
                if (a) scheduleFallback(a, 'click');
            }, true);
    
            document.addEventListener('pointerdown', function(e) {
                var p = getPoint(e);
                if (p) downPoint = { x: p.clientX, y: p.clientY, t: Date.now() };
            }, true);
    
            document.addEventListener('pointerup', function(e) {
                if (!downPoint) return;
                var p = getPoint(e);
                if (!p) return;
    
                var dx = Math.abs(p.clientX - downPoint.x);
                var dy = Math.abs(p.clientY - downPoint.y);
                var dt = Date.now() - downPoint.t;
                downPoint = null;
    
                if (dx > 16 || dy > 16 || dt > 1000) return;
    
                var el = document.elementFromPoint(p.clientX, p.clientY) || e.target;
                var a = closest(el, 'a[href]');
                if (a) scheduleFallback(a, 'pointerup');
            }, true);
    
            // 兼容老设备
            document.addEventListener('touchstart', function(e) {
                var p = getPoint(e);
                if (p) downPoint = { x: p.clientX, y: p.clientY, t: Date.now() };
            }, true);
    
            document.addEventListener('touchend', function(e) {
                if (!downPoint) return;
                var p = getPoint(e);
                if (!p) return;
    
                var dx = Math.abs(p.clientX - downPoint.x);
                var dy = Math.abs(p.clientY - downPoint.y);
                var dt = Date.now() - downPoint.t;
                downPoint = null;
    
                if (dx > 16 || dy > 16 || dt > 1000) return;
    
                var el = document.elementFromPoint(p.clientX, p.clientY) || e.target;
                var a = closest(el, 'a[href]');
                if (a) scheduleFallback(a, 'touchend');
            }, true);
    
            window.addEventListener('pagehide', clearPendingTimer, true);
            window.addEventListener('beforeunload', clearPendingTimer, true);
        })();
    """.trimIndent()

    val FIX_CAROUSEL_LAYOUT_JS = """
        (function() {
            if (document.getElementById('carousel-fix-style')) return;
            var style = document.createElement('style');
            style.id = 'carousel-fix-style';
            style.innerHTML = `
                .swiper-wrapper {
                    display: flex !important;
                    flex-direction: row !important;
                    flex-wrap: nowrap !important;
                }
                .swiper-slide, .slidebox, .scrool_img, .slide, #slide, .img_slide {
                    width: 100% !important;
                    flex-shrink: 0 !important;
                    aspect-ratio: 363 / 126 !important;
                    background-color: rgba(212, 200, 176, 0.2) !important;
                    display: block !important;
                    box-sizing: border-box !important;
                }
                #dhnavs .swiper-slide, #dhnavs_li .swiper-slide {
                    width: auto !important;
                    aspect-ratio: auto !important;
                    background-color: transparent !important;
                }
                .swiper-slide img, .slidebox img, .scrool_img img, .slide img, #slide img, .img_slide img {
                    width: 100% !important;
                    height: 100% !important;
                    object-fit: cover !important;
                }
            `;
            if(document.head) document.head.appendChild(style);
            else document.documentElement.appendChild(style);
        })();
    """.trimIndent()

    val INJECT_PSWP_AND_MANGA_JS = """
        (function(){
            window.__pswpInit = function() {
                if (window.__globalPswpAttached) return;
                var pswp = document.querySelector('.pswp');
                if (!pswp) {
                    var bodyObserver = new MutationObserver(function(mutations, obs) {
                        if (document.querySelector('.pswp')) {
                            obs.disconnect();
                            window.__pswpInit();
                        }
                    });
                    bodyObserver.observe(document.body, { childList: true, subtree: true });
                    return;
                }
                window.__globalPswpAttached = true;
                var checkState = function() {
                    var isOpen = pswp.classList.contains('pswp--open') ||
                                 pswp.classList.contains('pswp--visible') || 
                                 (getComputedStyle(pswp).display !== 'none' && getComputedStyle(pswp).opacity > 0);
                    if (window.__pswpLastState !== isOpen) {
                        window.__pswpLastState = isOpen;
                        if (window.AndroidFullscreen) window.AndroidFullscreen.notify(isOpen);
                        if (isOpen) {
                            setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 100);
                        }
                    }
                };
                var pswpObserver = new MutationObserver(checkState);
                pswpObserver.observe(pswp, { attributes: true, attributeFilter: ['class', 'style'] });
                checkState();
            };
            window.__pswpInit();
            if (!window.__pswpLongPressInjected) {
                window.__pswpLongPressInjected = true;
                var _lpTimer = null;
                var _lpStartPos = null;
                document.addEventListener('pointerdown', function(e) {
                    var pswp = document.querySelector('.pswp');
                    if (!pswp || !pswp.classList.contains('pswp--open')) return;
                    _lpStartPos = { x: e.clientX, y: e.clientY };
                    _lpTimer = setTimeout(function() {
                        _lpTimer = null;
                        var img = pswp.querySelector('.pswp__item--active img') || pswp.querySelector('.pswp__img');
                        if (img && img.src && window.AndroidFullscreen) {
                            window.AndroidFullscreen.saveImage(img.src);
                        }
                    }, 500);
                }, { passive: true });
                document.addEventListener('pointermove', function(e) {
                    if (!_lpTimer || !_lpStartPos) return;
                    if (Math.abs(e.clientX - _lpStartPos.x) > 10 || Math.abs(e.clientY - _lpStartPos.y) > 10) {
                        clearTimeout(_lpTimer); _lpTimer = null;
                    }
                }, { passive: true });
                document.addEventListener('pointerup', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
                document.addEventListener('pointercancel', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
            }
            if (!window._backBtnFixed) {
                window._backBtnFixed = true;
                document.addEventListener('click', function(e) {
                    var target = e.target.closest ? e.target.closest('a[href*="history.back"]') : null;
                    if (target) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (window.NativeMangaApi && window.NativeMangaApi.goBack) {
                            window.NativeMangaApi.goBack();
                        } else {
                            window.history.back();
                        }
                    }
                }, true);
            }
            var a = document.querySelector('.header h2 a');
            var isManga = false;
            if (a) {
                var t = a.innerText;
                isManga = t.indexOf('中文百合漫画区') !== -1 || 
                          t.indexOf('貼圖區') !== -1 || 
                          t.indexOf('原创图作区') !== -1 || 
                          t.indexOf('百合漫画图源区') !== -1;
            }
            if (isManga) {
                if (window._mangaClickInjected) return 'true';
                window._mangaClickInjected = true;
                
                var disablePhotoSwipe = function() {
                    var links = document.querySelectorAll('a[data-pswp-width], .img_one a, .message a');
                    for (var i = 0; i < links.length; i++) {
                        var aNode = links[i];
                        if (aNode.querySelector('img')) {
                            aNode.removeAttribute('data-pswp-width');
                            if (aNode.href && aNode.href.indexOf('javascript') === -1) {
                                aNode.setAttribute('data-disabled-href', aNode.href);
                                aNode.removeAttribute('href');
                            }
                        }
                    }
                };
                disablePhotoSwipe();
                var observer = new MutationObserver(disablePhotoSwipe);
                observer.observe(document.body, { childList: true, subtree: true });
                
                document.addEventListener('click', function(e) {
                    var targetContainer = e.target.closest('.img_one li, .img_one a, .message a, .img_one img, .message img');
                    if (!targetContainer) return;
                    
                    var targetImg = targetContainer.tagName.toLowerCase() === 'img' ? targetContainer : targetContainer.querySelector('img');
                    
                    if (targetImg) {
                        var imgSrc = targetImg.getAttribute('src') || '';
                        var imgZsrc = targetImg.getAttribute('zsrc') || '';
                        
                        if (imgSrc.indexOf('smiley') === -1 && imgZsrc.indexOf('smiley') === -1) { 
                            e.preventDefault(); 
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            
                            var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                            var urls = [];
                            var clickedIndex = 0;
                            for (var i = 0; i < allImgs.length; i++) {
                                var rawSrc = allImgs[i].getAttribute('zsrc') ||
                                allImgs[i].getAttribute('file') || allImgs[i].getAttribute('src');
                                if (rawSrc) {
                                    var absoluteUrl = new URL(rawSrc, document.baseURI).href;
                                    urls.push(absoluteUrl);
                                    if (allImgs[i] === targetImg) clickedIndex = urls.length - 1;
                                }
                            }
                            if (window.NativeMangaApi) {
                                window.NativeMangaApi.openNativeManga(urls.join('|||'), clickedIndex, document.title);
                            }
                        }
                    }
                }, true);
            }
            return isManga ? 'true' : 'false';
        })()
    """.trimIndent()

    val THREAD_LIST_CLICK_FIX_JS = """
        (function() {
            if (window.__threadListClickFixV2) return;
            window.__threadListClickFixV2 = true;
    
            function closest(el, selector) {
                while (el && el !== document && el.nodeType === 1) {
                    if (el.matches && el.matches(selector)) return el;
                    el = el.parentElement;
                }
                return null;
            }
    
            if (!document.getElementById('yamibo-thread-list-click-style')) {
                var style = document.createElement('style');
                style.id = 'yamibo-thread-list-click-style';
                style.textContent = 'li.list { cursor: pointer; -webkit-tap-highlight-color: rgba(0,0,0,0.08); }';
                document.head.appendChild(style);
            }
    
            document.addEventListener('click', function(e) {
                var li = closest(e.target, 'li.list');
                if (!li) return;
    
                // 点到真实链接时不干预
                if (closest(e.target, 'a[href]')) return;
    
                var threadLink =
                    li.querySelector('a[href*="mod=viewthread"]') ||
                    li.querySelector('a[href^="thread-"]');
    
                if (!threadLink || !threadLink.href) return;
    
                e.preventDefault();
                e.stopPropagation();
    
                var before = location.href;
    
                try {
                    threadLink.dispatchEvent(new MouseEvent('click', {
                        view: window,
                        bubbles: true,
                        cancelable: true
                    }));
                } catch (err) {
                    threadLink.click();
                }
    
                setTimeout(function() {
                    if (location.href !== before) return;
                    try {
                        location.assign(threadLink.href);
                    } catch (err) {
                        location.href = threadLink.href;
                    }
                }, 220);
            }, false);
        })();
    """.trimIndent()

    val REMOVE_TRANSITION_STYLE_JS = """
        var style = document.getElementById('manga-transition-style');
        if (style) style.remove();
    """.trimIndent()

    val CLEANUP_FULLSCREEN_JS = """
        (function() {
            var style = document.getElementById('manga-transition-style');
            if (style) style.remove();
            window.pswpObserverAttached = false;
        })();
    """.trimIndent()

    val CHECK_SECTION_JS = """
        (function() {
            var sectionHeader = document.querySelector('.header h2 a');
            if (sectionHeader) return sectionHeader.innerText.trim();
            var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
            if (nav) return nav.innerText.trim();
            return '';
        })();
    """.trimIndent()

    // 获取帖子历史详情专用JS
    val EXTRACT_THREAD_INFO_JS = """
        (function() {
            var title = document.title || '';
            var section = '';
            var sectionHeader = document.querySelector('.header h2 a');
            if (sectionHeader) {
                section = sectionHeader.innerText.trim();
            } else {
                var nav = document.querySelector('.z, .nav, .mz, .thread_nav, .sq_nav');
                if (nav) section = nav.innerText.trim();
            }
            var author = '';
            var authorEl = document.querySelector('.authi a.xw1, .authi a, .mtit .z a, .pi .authi a');
            if (authorEl) {
                author = authorEl.innerText.trim();
            } else {
                var byUser = document.querySelector('.by a');
                if (byUser) author = byUser.innerText.trim();
            }
            title = title.replace(/\s*-\s*百合会.*$/, '');
            return JSON.stringify({title: title, section: section, author: author});
        })();
    """.trimIndent()

    val AUTO_OPEN_MANGA_JS = """
        (function() {
            // 版块白名单
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            if (sectionName !== '') {
                var allowedSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
                var isAllowedSection = false;
                for (var k = 0; k < allowedSections.length; k++) {
                    if (sectionName.indexOf(allowedSections[k]) !== -1) {
                        isAllowedSection = true;
                        break;
                    }
                }
                if (!isAllowedSection) {
                    if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                        window.AndroidFullscreen.notifyMangaActionDone();
                    }
                    return;
                }
            }
            
            // 公告帖拦截
            var typeLabel = document.querySelector('.view_tit em');
            if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                    window.AndroidFullscreen.notifyMangaActionDone();
                }
                return; 
            }

            // 过渡黑屏样式
            if (!document.getElementById('manga-transition-style')) {
                var style = document.createElement('style');
                style.id = 'manga-transition-style';
                style.innerHTML = 'body > *:not(.pswp) { opacity: 0 !important; pointer-events: none !important; } body { background: #000 !important; }';
                document.head.appendChild(style);
            }

            function abortAndNotify() {
                var style = document.getElementById('manga-transition-style');
                if (style) style.remove();
                if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                    window.AndroidFullscreen.notifyMangaActionDone();
                }
            }

            if (window.NativeMangaApi) {
                var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                if (allImgs.length > 0) {
                    var urls = [];
                    for (var i = 0; i < allImgs.length; i++) {
                        var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                        if (rawSrc) {
                            urls.push(new URL(rawSrc, document.baseURI).href);
                        }
                    }
                    if (urls.length > 0) {
                        window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.title);
                        return;
                    }
                }
            }

            var clickTimer = null;
            var timeoutTimer = null;
            
            var observer = new MutationObserver(function(mutations, obs) {
                if (document.querySelector('.pswp')) {
                    obs.disconnect();
                    clearTimeout(timeoutTimer);
                    if (clickTimer) clearInterval(clickTimer);
                    
                    if (window.AndroidFullscreen) {
                        window.AndroidFullscreen.notify(true);
                        window.AndroidFullscreen.notifyMangaActionDone();
                    }
                    return;
                }
            });

            observer.observe(document.body, { childList: true, subtree: true });

            var clickAttempts = 0;
            var maxClicks = 10;

            function tryClickTarget() {
                if (clickAttempts >= maxClicks) {
                    if (clickTimer) clearInterval(clickTimer);
                    return;
                }
                if (document.querySelector('.pswp')) return; 
                
                clickAttempts++;
                var links = document.querySelectorAll('a[data-pswp-width], .img_one a.orange, .message a.orange, .postmessage a.orange');
                var clicked = false;
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    var innerHtml = links[i].innerHTML || '';
                    if (href.toLowerCase().indexOf('.gif') === -1 && href.indexOf('static/image/') === -1 && innerHtml.indexOf('static/image/') === -1) {
                        links[i].dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                        clicked = true;
                        break; 
                    }
                }

                if (!clicked) {
                    var fallbackImgs = document.querySelectorAll('.img_one img');
                    if(fallbackImgs.length > 0 && fallbackImgs[0].parentElement && fallbackImgs[0].parentElement.tagName === 'A'){
                        fallbackImgs[0].parentElement.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                    }
                }
            }

            tryClickTarget();

            clickTimer = setInterval(tryClickTarget, 250);

            timeoutTimer = setTimeout(function() {
                observer.disconnect();
                if (clickTimer) clearInterval(clickTimer);
                abortAndNotify();
            }, 5000);
        })();
    """.trimIndent()

    // MinePage脚本
    val MINE_INJECT_PSWP_AND_MANGA_JS = """
        (function(){
            window.__pswpInit = function() {
                if (window.__globalPswpAttached) return;
                var pswp = document.querySelector('.pswp');
                if (!pswp) {
                    var bodyObserver = new MutationObserver(function(mutations, obs) {
                        if (document.querySelector('.pswp')) {
                            obs.disconnect();
                            window.__pswpInit();
                        }
                    });
                    bodyObserver.observe(document.body, { childList: true, subtree: true });
                    return;
                }
                window.__globalPswpAttached = true;
                
                var checkState = function() {
                    var isOpen = pswp.classList.contains('pswp--open') || 
                                 pswp.classList.contains('pswp--visible') || 
                                 (getComputedStyle(pswp).display !== 'none' && getComputedStyle(pswp).opacity > 0);
                    if (window.__pswpLastState !== isOpen) {
                        window.__pswpLastState = isOpen;
                        if (window.AndroidFullscreen) window.AndroidFullscreen.notify(isOpen);
                        if (isOpen) {
                            setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 100);
                        }
                    }
                };
                
                var pswpObserver = new MutationObserver(checkState);
                pswpObserver.observe(pswp, { attributes: true, attributeFilter: ['class', 'style'] });
                checkState();
            };
            window.__pswpInit();
            if (!window.__pswpLongPressInjected) {
                window.__pswpLongPressInjected = true;
                var _lpTimer = null;
                var _lpStartPos = null;
                document.addEventListener('pointerdown', function(e) {
                    var pswp = document.querySelector('.pswp');
                    if (!pswp || !pswp.classList.contains('pswp--open')) return;
                    _lpStartPos = { x: e.clientX, y: e.clientY };
                    _lpTimer = setTimeout(function() {
                        _lpTimer = null;
                        var img = pswp.querySelector('.pswp__item--active img') || pswp.querySelector('.pswp__img');
                        if (img && img.src && window.AndroidFullscreen) {
                            window.AndroidFullscreen.saveImage(img.src);
                        }
                    }, 500);
                }, { passive: true });
                document.addEventListener('pointermove', function(e) {
                    if (!_lpTimer || !_lpStartPos) return;
                    if (Math.abs(e.clientX - _lpStartPos.x) > 10 || Math.abs(e.clientY - _lpStartPos.y) > 10) {
                        clearTimeout(_lpTimer); _lpTimer = null;
                    }
                }, { passive: true });
                document.addEventListener('pointerup', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
                document.addEventListener('pointercancel', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
            }

            var rewriteHomeLink = function() {
                var homeLink = document.querySelector('.my a[href*="index.php"]');
                if (homeLink) {
                    homeLink.href = 'home.php?mod=space&do=profile&mycenter=1&mobile=2';
                }
            };
            rewriteHomeLink();
            if (!window._mineHomeLinkObserver) {
                window._mineHomeLinkObserver = new MutationObserver(rewriteHomeLink);
                window._mineHomeLinkObserver.observe(document.body, { childList: true, subtree: true });
            }
            
            if (!window._backBtnFixed) {
                window._backBtnFixed = true;
                document.addEventListener('click', function(e) {
                    var target = e.target.closest ? e.target.closest('a[href*="history.back"], #hui-back') : null;
                    if (target) {
                        e.preventDefault();
                        e.stopPropagation();
                        if (window.NativeMangaApi && window.NativeMangaApi.goBack) {
                            window.NativeMangaApi.goBack();
                        } else {
                            window.history.back();
                        }
                    }
                }, true);
            }
            var a = document.querySelector('.header h2 a');
            var isManga = false;
            if (a) {
                var t = a.innerText;
                isManga = t.indexOf('中文百合漫画区') !== -1 || 
                          t.indexOf('貼圖區') !== -1 || 
                          t.indexOf('原创图作区') !== -1 || 
                          t.indexOf('百合漫画图源区') !== -1;
            }
            if (isManga) {
                if (window._mangaClickInjected) return 'true';
                window._mangaClickInjected = true;
                
                var disablePhotoSwipe = function() {
                    var links = document.querySelectorAll('a[data-pswp-width], .img_one a, .message a');
                    for (var i = 0; i < links.length; i++) {
                        var aNode = links[i];
                        if (aNode.querySelector('img')) {
                            aNode.removeAttribute('data-pswp-width');
                            if (aNode.href && aNode.href.indexOf('javascript') === -1) {
                                aNode.setAttribute('data-disabled-href', aNode.href);
                                aNode.removeAttribute('href');
                            }
                        }
                    }
                };
                disablePhotoSwipe();
                var observer = new MutationObserver(disablePhotoSwipe);
                observer.observe(document.body, { childList: true, subtree: true });
                
                document.addEventListener('click', function(e) {
                    var targetContainer = e.target.closest('.img_one li, .img_one a, .message a, .img_one img, .message img');
                    if (!targetContainer) return;
                    
                    var targetImg = targetContainer.tagName.toLowerCase() === 'img' ? targetContainer : targetContainer.querySelector('img');
                    
                    if (targetImg) {
                        var imgSrc = targetImg.getAttribute('src') || '';
                        var imgZsrc = targetImg.getAttribute('zsrc') || '';
                        
                        if (imgSrc.indexOf('smiley') === -1 && imgZsrc.indexOf('smiley') === -1) { 
                            e.preventDefault(); 
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            
                            var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                            var urls = [];
                            var clickedIndex = 0;
                            for (var i = 0; i < allImgs.length; i++) {
                                var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('file') || allImgs[i].getAttribute('src');
                                if (rawSrc) {
                                    var absoluteUrl = new URL(rawSrc, document.baseURI).href;
                                    urls.push(absoluteUrl);
                                    if (allImgs[i] === targetImg) clickedIndex = urls.length - 1;
                                }
                            }
                            if (window.NativeMangaApi) {
                                window.NativeMangaApi.openNativeManga(urls.join('|||'), clickedIndex, document.title);
                            }
                        }
                    }
                }, true); 
            }
            return isManga ? 'true' : 'false';
        })()
    """.trimIndent()


    // MangaWebPage脚本

    val MANGA_WEB_HIDE_COMMAND = """
        javascript:(function() {
            var style = document.createElement('style');
            style.innerHTML = '.mz { visibility: hidden !important; pointer-events: none !important; } .nav-search, #nav-more-menu .btn-to-pc { display: none !important; }';
            if (document.head) document.head.appendChild(style);
        })()
    """.trimIndent()

    val MANGA_WEB_AUTO_OPEN_JS = """
        (function() {
            // 版块与公告检查
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            if (sectionName !== '') {
                var allowedSections = ['中文百合漫画区', '貼圖區', '贴图区', '原创图作区', '百合漫画图源区'];
                var isAllowedSection = false;
                for (var k = 0; k < allowedSections.length; k++) {
                    if (sectionName.indexOf(allowedSections[k]) !== -1) { isAllowedSection = true; break; }
                }
                if (!isAllowedSection) {
                    if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                    return;
                }
            }
            
            var typeLabel = document.querySelector('.view_tit em');
            if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                return; 
            }

            // 提取图片
            function extractAndOpenNative() {
                if (!window.NativeMangaApi) return false;
                
                var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                if (allImgs.length === 0) return false;
                
                var urls = [];
                for (var i = 0; i < allImgs.length; i++) {
                    var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                    if (rawSrc) urls.push(new URL(rawSrc, document.baseURI).href);
                }
                
                if (urls.length > 0) {
                    window.NativeMangaApi.openNativeManga(urls.join('|||'), 0, document.title);
                    return true;
                }
                return false;
            }

            if (extractAndOpenNative()) {
                return; // 如果第一次就成功了，直接结束
            }

            var extractAttempts = 0;
            var maxExtracts = 10;
            
            var extractTimer = setInterval(function() {
                extractAttempts++;
                
                if (extractAndOpenNative()) {
                    clearInterval(extractTimer);
                    return;
                }
                
                if (extractAttempts >= maxExtracts) {
                    clearInterval(extractTimer);
                    if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) {
                        window.AndroidFullscreen.notifyMangaActionDone();
                    }
                }
            }, 250);

        })();
    """.trimIndent()


    // OtherWebPage脚本

    val OTHER_WEB_HIDE_COMMAND = """
        (function() {
            var style = document.createElement('style');
            style.innerHTML = '.mz { visibility: hidden !important; pointer-events: none !important; } .nav-search, #nav-more-menu .btn-to-pc { display: none !important; }';
            if (document.head) document.head.appendChild(style);
        })()
    """.trimIndent()

    val OTHER_WEB_INIT_PSWP_JS = """
        (function(){
            window.__pswpInit = function() {
                if (window.__globalPswpAttached) return;
                var pswp = document.querySelector('.pswp');
                if (!pswp) {
                    var bodyObserver = new MutationObserver(function(mutations, obs) {
                        if (document.querySelector('.pswp')) {
                            obs.disconnect();
                            window.__pswpInit();
                        }
                    });
                    bodyObserver.observe(document.body, { childList: true, subtree: true });
                    return;
                }
                window.__globalPswpAttached = true;
                
                var checkState = function() {
                    var isOpen = pswp.classList.contains('pswp--open') || 
                                 pswp.classList.contains('pswp--visible') || 
                                 (getComputedStyle(pswp).display !== 'none' && getComputedStyle(pswp).opacity > 0);
                    if (window.__pswpLastState !== isOpen) {
                        window.__pswpLastState = isOpen;
                        if (window.AndroidFullscreen) window.AndroidFullscreen.notify(isOpen);
                        if (isOpen) {
                            setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 100);
                        }
                    }
                };
                
                var pswpObserver = new MutationObserver(checkState);
                pswpObserver.observe(pswp, { attributes: true, attributeFilter: ['class', 'style'] });
                checkState();
            };
            window.__pswpInit();
            if (!window.__pswpLongPressInjected) {
                window.__pswpLongPressInjected = true;
                var _lpTimer = null;
                var _lpStartPos = null;
                document.addEventListener('pointerdown', function(e) {
                    var pswp = document.querySelector('.pswp');
                    if (!pswp || !pswp.classList.contains('pswp--open')) return;
                    _lpStartPos = { x: e.clientX, y: e.clientY };
                    _lpTimer = setTimeout(function() {
                        _lpTimer = null;
                        var img = pswp.querySelector('.pswp__item--active img') || pswp.querySelector('.pswp__img');
                        if (img && img.src && window.AndroidFullscreen) {
                            window.AndroidFullscreen.saveImage(img.src);
                        }
                    }, 500);
                }, { passive: true });
                document.addEventListener('pointermove', function(e) {
                    if (!_lpTimer || !_lpStartPos) return;
                    if (Math.abs(e.clientX - _lpStartPos.x) > 10 || Math.abs(e.clientY - _lpStartPos.y) > 10) {
                        clearTimeout(_lpTimer); _lpTimer = null;
                    }
                }, { passive: true });
                document.addEventListener('pointerup', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
                document.addEventListener('pointercancel', function() {
                    if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                }, { passive: true });
            }
        })()
    """.trimIndent()

    val OTHER_WEB_CHECK_TYPE_JS = """
        (function() {
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            var currentUrl = window.location.href;
            var mangaSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
            var isManga = mangaSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=30') !== -1;
            var novelSections = ['文學區', '文学区', 'TXT小说区', '轻小说/译文区'];
            var isNovel = novelSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=55') !== -1;
            if (isNovel) return 1;
            if (isManga) return 2;
            return 3;
        })();
    """.trimIndent()

    val OTHER_WEB_AUTO_OPEN_JS = """
        (function() {
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            if (sectionName !== '') {
                var allowedSections = ['中文百合漫画区', '貼圖區', '原创图作区', '百合漫画图源区'];
                var isAllowedSection = false;
                for (var k = 0; k < allowedSections.length; k++) {
                    if (sectionName.indexOf(allowedSections[k]) !== -1) { isAllowedSection = true; break; }
                }
                if (!isAllowedSection) {
                    if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                    return;
                }
            }
            
            var typeLabel = document.querySelector('.view_tit em');
            if (typeLabel && typeLabel.innerText.indexOf('公告') !== -1) {
                if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
                return; 
            }
            
            if (!document.getElementById('manga-transition-style')) {
                var style = document.createElement('style');
                style.id = 'manga-transition-style';
                style.innerHTML = 'body > *:not(.pswp) { opacity: 0 !important; pointer-events: none !important; } body { background: #000 !important; }';
                document.head.appendChild(style);
            }

            function abortAndNotify() {
                var style = document.getElementById('manga-transition-style');
                if (style) style.remove();
                if (window.AndroidFullscreen && window.AndroidFullscreen.notifyMangaActionDone) window.AndroidFullscreen.notifyMangaActionDone();
            }

            var isDone = false;
            var attempts = 0;
            var timer = setInterval(function() {
                if (isDone) { clearInterval(timer); return; }
                attempts++;

                var pswp = document.querySelector('.pswp');
                if (pswp) {
                    isDone = true;
                    clearInterval(timer);
                    if (window.AndroidFullscreen) {
                        window.AndroidFullscreen.notify(true);
                        window.AndroidFullscreen.notifyMangaActionDone();
                    }
                    return;
                }

                var links = document.querySelectorAll('a[data-pswp-width], .img_one a.orange, .message a.orange, .postmessage a.orange');
                var targetEl = null;
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].getAttribute('href') || '';
                    var innerHtml = links[i].innerHTML || '';
                    if (href.toLowerCase().indexOf('.gif') === -1 && href.indexOf('static/image/') === -1 && innerHtml.indexOf('static/image/') === -1) {
                        targetEl = links[i]; break;
                    }
                }
                
                if (targetEl) targetEl.dispatchEvent(new MouseEvent('click', { view: window, bubbles: true, cancelable: true }));
                if (attempts >= 25) { isDone = true; clearInterval(timer); abortAndNotify(); }
            }, 200);
        })();
    """.trimIndent()

    fun getDarkModeSetJs(enable: Boolean, themeId: Int = 0): String {
        val rulesList = when (themeId) {
            1 -> DARK_MODE_CSS_RULES_OKLCH
            2 -> DARK_MODE_CSS_RULES_OLED
            3 -> DARK_MODE_CSS_RULES_TWILIGHT
            else -> DARK_MODE_CSS_RULES_CLASSIC
        }
        val styleString = rulesList.joinToString(",\n") { "                '$it'" }

        return """
            (function() {
                var styleId = 'yamibo-dark-mode';
                var existing = document.getElementById(styleId);
                var enable = $enable;
                if (!enable) {
                    if (existing) existing.remove();
                    return;
                }
                if (existing) existing.remove();
                var style = document.createElement('style');
                style.id = styleId;
                style.innerHTML = [
$styleString
                ].join('\n');
                (document.body || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }

    fun injectDarkModeCssIntoHtml(html: String, themeId: Int = 0): String {
        val rulesList = when (themeId) {
            1 -> DARK_MODE_CSS_RULES_OKLCH
            2 -> DARK_MODE_CSS_RULES_OLED
            3 -> DARK_MODE_CSS_RULES_TWILIGHT
            else -> DARK_MODE_CSS_RULES_CLASSIC
        }
        val css = rulesList.joinToString("\n")
        val styleTag = "<style id=\"yamibo-dark-mode\">\n$css\n</style>"
        return when {
            html.contains("</head>") -> html.replace("</head>", "$styleTag</head>")
            html.contains("<head>") -> html.replace("<head>", "<head>$styleTag")
            html.contains("<html>") -> html.replace("<html>", "<html><head>$styleTag</head>")
            html.contains("<body") -> html.replace("<body", "$styleTag<body")
            else -> "$styleTag$html"
        }
    }

    val SEARCH_DIRECT_NAV_JS = """
        (function() {
            if (window.__yamiboSearchNav) return;
            window.__yamiboSearchNav = true;

            // 事件委托在 document 上，避免 PJAX 导航后 DOM 替换导致监听丢失
            document.addEventListener('submit', function(e) {
                var form = e.target;
                if (!form || !form.classList.contains('searchform')) return;
                if (!/search\.php/.test(window.location.href)) return;

                var input = document.getElementById('scform_srchtxt');
                if (!input) return;

                var keyword = input.value.trim();
                if (!keyword) return;

                // 包含中文或空白字符则不匹配（必须是纯网址）
                if (/[一-鿿㐀-䶿豈-﫿\s]/.test(keyword)) return;

                var url = null;

                // 只匹配帖子网址:
                // https://bbs.yamibo.com/forum.php?mod=viewthread&tid=XXX...
                // https://m.yamibo.com/forum.php?mod=viewthread&tid=XXX...
                if (/^https?:\/\/(bbs|m)\.yamibo\.com\/forum\.php\?mod=viewthread&tid=\d+/.test(keyword)) {
                    url = keyword.replace(/&highlight=[^&]*/g, '');
                }

                // https://bbs.yamibo.com/thread-XXX-X-X.html
                if (!url && /^https?:\/\/(bbs|m)\.yamibo\.com\/thread-\d+-\d+-\d+\.html$/.test(keyword)) {
                    url = keyword;
                }

                if (url && window.AndroidSearchNav) {
                    e.preventDefault();
                    e.stopPropagation();
                    window.AndroidSearchNav.navigateToPost(url);
                }
            }, true);
        })();
    """.trimIndent()

    val RELOAD_BROKEN_IMAGES_JS = """
        (function(){
            var imgs = document.querySelectorAll('img');
            for(var i=0; i<imgs.length; i++) {
                var img = imgs[i];
                if(!img.complete || typeof img.naturalWidth === 'undefined' || img.naturalWidth === 0 || img.style.opacity === '0') {
                    img.onload = function() { 
                        this.style.transition = 'opacity 0.2s ease-in'; 
                        this.style.opacity = '1'; 
                    };
                    var src = img.src;
                    img.src = ''; 
                    img.src = src; 
                }
            }
        })();
    """.trimIndent()

}