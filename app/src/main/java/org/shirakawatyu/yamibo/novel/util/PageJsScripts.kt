package org.shirakawatyu.yamibo.novel.util

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
    private val DARK_MODE_CSS_RULES_CLASSIC = listOf(
        "/* === CSS 变量覆盖 === */",
        "body {",
        "--dz-BG-body: #121212 !important;",
        "--dz-BG-0: #1e1e1e !important;",
        "--dz-BG-5: #252525 !important;",
        "--dz-BG-6: #444444 !important;",
        "--dz-FC-fff: #dddddd !important;",
        "--dz-FC-333: #bbbbbb !important;",
        "--dz-FC-666: #aaaaaa !important;",
        "--dz-FC-999: #888888 !important;",
        "--dz-FC-ccc: #999999 !important;",
        "--dz-FC-aaa: #777777 !important;",
        "--dz-BOR-ed: #333333 !important;",
        "--dz-FC-color: #bbbbbb !important;",
        "}",
        "/* === 页面根背景 === */",
        "html, body { background: #121212 !important; }",
        "/* === 主容器 === */",
        ".wp, #wp, .content, .main, .wrapper { background: #121212 !important; }",
        "/* === 顶部导航栏 === */",
        ".header { background: #1a1a1a !important; border-color: #333 !important; }",
        ".header, .header h2, .header h2 a, .header a { color: #dddddd !important; }",
        ".header_toplogo { background: #1a1a1a !important; }",
        ".header_toplogo p { color: #dddddd !important; }",
        "/* === 顶部更多菜单 (只看楼主/倒序/返回首页/电脑版) === */",
        "#nav-more-menu { background: #1e1e1e !important; }",
        ".nav-more-item { color: #bbbbbb !important; }",
        ".nav-more-item-text { color: #bbbbbb !important; }",
        ".nav-more-item svg { fill: #bbbbbb !important; color: #bbbbbb !important; }",
        "/* === 导航标签 (只看他/倒序/阅读模式等) === */",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: #1a1a1a !important; }",
        ".sq_nav a, .thread_nav a { color: #aaaaaa !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: #fff !important; background: #333 !important; }",
        "/* === 版块导航 .z (分区/标签) === */",
        ".z, .vertical_tab { background: #1a1a1a !important; }",
        ".z a, .vertical_tab a { color: #aaaaaa !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: #ffffff !important; background: #333 !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: #1e1e1e !important; }",
        "/* === 版块顶部信息栏 === */",
        ".forumdisplay-top { background: #1e1e1e !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: #dddddd !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: #aaaaaa !important; }",
        "/* === 排序标签导航 (全部/最新/热门/新帖/精华) === */",
        ".dhnav_box, #dhnav, #dhnav_li { background: #1a1a1a !important; }",
        ".dhnav_box a, #dhnav a { color: #aaaaaa !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: #ffffff !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: #666666 !important; }",
        ".dhnavs_box, #dhnavs { background: #1a1a1a !important; }",
        "#dhnavs .swiper-slide a { color: #aaaaaa !important; }",
        "#dhnavs .swiper-slide.mon a { color: #ffffff !important; }",
        "/* === 论坛列表容器 === */",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: #121212 !important; }",
        ".forumlist .subforumshow { background: #1a1a1a !important; }",
        "/* === 分区标题栏 === */",
        ".subforumshow { background: #1a1a1a !important; border-color: #333 !important; }",
        ".subforumshow h2 a { color: #bbbbbb !important; }",
        ".subforumshow i { color: #888888 !important; }",
        ".subforumshow { color: #aaaaaa !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: #bbbbbb !important; }",
        "/* === 版块列表项 (含内部 a 标签背景覆盖) === */",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: #1e1e1e !important; }",
        ".mlist1 li { border-color: #2a2a2a !important; }",
        ".mlist1 .mtit { color: #dddddd !important; }",
        ".mlist1 .mtxt { color: #999999 !important; }",
        ".mlist1 .mnum { color: #777777 !important; }",
        "/* === 通用板块容器 (Discuz 模板) === */",
        ".bm, .bm_c, .bm_h { background: #1e1e1e !important; border-color: #333 !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: #bbbbbb !important; }",
        "/* === 帖子列表 === */",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: #1e1e1e !important; }",
        ".threadlist { background: #1a1a1a !important; }",
        ".threadlist li, .threadlist li a { background: #1e1e1e !important; }",
        ".tl_bm li, .threadlist li { border-color: #2a2a2a !important; }",
        ".tl_bm a, .threadlist a { color: #bbbbbb !important; }",
        "/* === 帖子详情 - 标题 === */",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: #1e1e1e !important; color: #dddddd !important; }",
        "/* === 帖子详情 - 楼主信息栏 === */",
        ".pls, .pls div, .pls a { background: #1a1a1a !important; }",
        ".pls, .pls a, .pls em, .pls span { color: #bbbbbb !important; }",
        "/* === 帖子详情 - 帖子内容容器 === */",
        ".plc, .plm, .plc div, .plm div { background: #1e1e1e !important; }",
        "/* === 帖子详情 - 作者行 === */",
        ".authi, .authi em, .authi a, .authi span { color: #aaaaaa !important; }",
        "/* === 帖子详情 - 文本颜色 === */",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: #bbbbbb !important; }",
        "/* === 帖子中的链接 === */",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: #7eb8da !important; }",
        "/* === 分页导航 === */",
        ".pgs, .pgs a, .pg, .pg a { background: #252525 !important; color: #bbbbbb !important; border-color: #444 !important; }",
        ".page { background: #121212 !important; }",
        ".page a { background: #252525 !important; color: #bbbbbb !important; border-color: #444 !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: #444 !important; color: #fff !important; }",
        "/* === Discuz 颜色工具类 === */",
        ".xi1 { color: #dddddd !important; }",
        ".xi2 { color: #bbbbbb !important; }",
        ".xg1, .xg1 a { color: #aaaaaa !important; }",
        ".xg2, .xg2 a { color: #999999 !important; }",
        "/* === 帖子元信息 === */",
        ".num, .views, .replies { color: #888888 !important; }",
        ".ts, .time { color: #777777 !important; }",
        ".pipe { color: #444 !important; }",
        "/* === 锁/图章/图标 === */",
        ".lock, .closed, .icn, .attach, .tattl { color: #888888 !important; }",
        "/* === 弹窗/对话框 === */",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: #1e1e1e !important; color: #bbbbbb !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: #bbbbbb !important; }",
        "/* === 底部回复栏 === */",
        ".foot_reply, .f_c, .foot, #f_c { background: #1e1e1e !important; border-color: #333 !important; }",
        ".foot_reply a, .f_c a, .foot a { color: #bbbbbb !important; }",
        ".viewt-reply, .viewt-reply a { background: #333 !important; color: #fff !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: #bbbbbb !important; }",
        "/* === 个人中心导航 === */",
        ".my, .my a, .my i, .my span { color: #bbbbbb !important; }",
        ".mz, .mz a, .mz i, .mz span { color: #aaaaaa !important; }",
        "/* === 个人中心功能列表 (含内部 a 标签背景覆盖) === */",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: #1e1e1e !important; }",
        ".myinfo_list_ico li  { background: #1e1e1e !important; }",
        ".myinfo_list_ico li a { background: #1a1a1a !important; }",
        ".myinfo_list_ico li { border-color: #2a2a2a !important; }",
        ".myinfo_list_ico li a { color: #bbbbbb !important; }",
        ".myinfo_list_ico li i { color: #EEEEEE !important; }",
        "/* === 个人中心其他区块 === */",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: #1e1e1e !important; }",
        ".myinfo a, .myinfo_menu a { color: #bbbbbb !important; }",
        ".myinfo_list, .myinfo_list li { border-color: #2a2a2a !important; }",
        ".myinfo_list li span { color: #aaaaaa !important; }",
        ".myinfo_list b { color: #bbbbbb !important; }",
        ".mtag, .profile_tag { color: #888888 !important; }",
        "/* === 表格 === */",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: #1e1e1e !important; color: #bbbbbb !important; border-color: #2a2a2a !important; }",
        "/* === 页脚 === */",
        ".footer, #footer { background: #1a1a1a !important; color: #888888 !important; border-color: #333 !important; }",
        ".footer a, #footer a { color: #888888 !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: #1a1a1a !important; color: #888888 !important; }",
        ".footer .mon { color: #888888 !important; }",
        "/* === input/select 控件 === */",
        "input, select, textarea { background: #2a2a2a !important; color: #bbbbbb !important; border-color: #444 !important; }",
        "/* === 引用/代码块 === */",
        "blockquote, .quote, .blockcode { background: #252525 !important; border-color: #444 !important; color: #aaaaaa !important; }",
        "/* === 分割线 === */",
        "hr, .line, .partition { border-color: #333 !important; }",
        "/* === 通知/提示条 === */",
        ".notice, .tip, .alert, .warning, .tips { background: #252525 !important; color: #bbbbbb !important; border-color: #444 !important; }",
        "/* === 头像边框适配 === */",
        ".avatar img, .avatar, .my_avatar img { border-color: #333 !important; }",
        "/* === 按钮通用 === */",
        ".btn, .button, button, .pn, .pnc { background: #333 !important; color: #bbbbbb !important; border-color: #555 !important; }",
        "/* === 快速回复框 === */",
        "#postform, #fastpostform, .area textarea { background: #1e1e1e !important; color: #bbbbbb !important; border-color: #444 !important; }",
        "/* === 点评/评分标题 === */",
        ".psth { background: #1a1a1a !important; color: #bbbbbb !important; border-color: #333 !important; }",
        ".psth .icon_ring { color: #888888 !important; }",
        "/* === 目录/章节折叠列表 === */",
        ".showcollapse_box { background: #1e1e1e !important; border-color: #2a2a2a !important; }",
        ".showcollapse_title { background: #252525 !important; color: #bbbbbb !important; border-color: #333 !important; }",
        ".showcollapse_content { background: #1e1e1e !important; color: #bbbbbb !important; }",
        ".showcollapse_content a { color: #7eb8da !important; }",
        ".showcollapse_gather { color: #888888 !important; }",
        "/* === 帖子内联高亮 (覆盖 inline style) 强制清除发帖人自带底色 === */",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: #bbbbbb !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: #ff6666 !important; }",
        "/* === 帖子底部操作栏 (评分/点评)  & 列表项底部信息 === */",
        ".threadlist_foot { background: #1e1e1e !important; border-color: #333 !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: #888888 !important; }",
        ".threadlist_foot li { border: 1px solid #555 !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: #888888 !important; }",
        "/* === 帖子列表项 - 顶部 (头像+用户名) === */",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: #1e1e1e !important; }",
        ".threadlist_top .mmc { color: #bbbbbb !important; }",
        ".threadlist_top .mtime { color: #777777 !important; }",
        "/* === 帖子列表项 - 标题&摘要 === */",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: #1e1e1e !important; color: #dddddd !important; }",
        ".threadlist_mes, .threadlist_mes a { background: #1e1e1e !important; color: #999999 !important; }",
        "/* === 帖子间分隔 === */",
        ".discuz_x { background: #1e1e1e !important; border-color: #333 !important; }",
        "/* === 倒序/只看楼主 栏 === */",
        ".txtlist, .txtlist .mtit { background: #1a1a1a !important; color: #bbbbbb !important; border-color: #333 !important; }",
        ".txtlist .ytxt, .txtlist a { color: #aaaaaa !important; }",
        "/* === 帖子元信息行 === */",
        ".mtime, .mtime span, .mtime em, .mtime i { color: #888888 !important; }",
        ".y, .y span, .y em, .y i { color: #888888 !important; }",
        ".pstatus, .pstatus font { color: #888888 !important; }",
        "/* === 浮动菜单 (回到顶部) === */",
        ".float-menu-item { background: rgba(51, 51, 51, 0.85) !important; color: #bbbbbb !important; }",
        ".float-menu-item svg { fill: #bbbbbb !important; color: #bbbbbb !important; }",
        ".scrolltop { background: #333 !important; }",
        "#mask { background: rgba(0,0,0,0.7) !important; }",
        "/* === 通用文本覆盖 === */",
        "strong, b, .strong { color: #dddddd !important; }",
        "sup, sub { color: #aaaaaa !important; }",
        "em { color: #bbbbbb !important; }",
        ".display, .pi { background: #1e1e1e !important; }",
        "/* === 修复夜间模式头像被 DOM 层叠覆盖 === */",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        "/* === 签到页 (hui 框架) === */",
        ".hui-header { background: #1a1a1a !important; }",
        ".hui-header h1 { color: #dddddd !important; }",
        ".hui-slide-menu { background: #1e1e1e !important; }",
        ".hui-slide-menu li { color: #bbbbbb !important; }",
        ".hui-wrap { background: #121212 !important; }",
        ".hui-common-title-line { border-color: #333 !important; }",
        ".hui-common-title-txt { color: #bbbbbb !important; }",
        ".hui-content { background: #1e1e1e !important; color: #bbbbbb !important; }",
        ".hui-media-list li { background: #1e1e1e !important; border-color: #333 !important; }",
        ".hui-media-content { background: #1e1e1e !important; }",
        ".hui-media-content p { color: #bbbbbb !important; }",
        ".hui-media-content a { color: #7eb8da !important; }",
        ".hui-list { background: #1e1e1e !important; }",
        ".hui-list-text { color: #bbbbbb !important; }",
        ".hui-center-title h2 { color: #dddddd !important; }",
        ".hui-button { background: #333 !important; color: #bbbbbb !important; }",
        ".hui-primary { background: #cc7755 !important; color: #fff !important; }",
        ".fl-table { background: #1e1e1e !important; }",
        ".fl-table th { background: #252525 !important; color: #bbbbbb !important; }",
        ".fl-table td { background: #1e1e1e !important; color: #bbbbbb !important; }",
        ".day { color: #bbbbbb !important; }",
        ".day.today { background: #cc7755 !important; color: #fff !important; }",
        ".lunar { color: #888888 !important; }",
        ".signbtn .btna { background: #cc7755 !important; color: #fff !important; }",
        "/* === 发帖时间/楼层号 === */",
        ".authi .mtit, .authi .mtime { color: #888888 !important; }",
        "/* === PhotoSwipe按钮 === */",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 2. 新增：深空灰蓝 Oklch 主题
    private val DARK_MODE_CSS_RULES_OKLCH = listOf(
        "/* === CSS 变量覆盖 (深空灰蓝 Oklch 环境色版) === */",
        "body {",
        "--dz-BG-body: oklch(18% 0.015 250) !important;",
        "--dz-BG-0: oklch(23% 0.015 250) !important;",
        "--dz-BG-5: oklch(26% 0.015 250) !important;",
        "--dz-BG-6: oklch(38% 0.015 250) !important;",
        "--dz-FC-fff: oklch(89% 0.005 250) !important;",
        "--dz-FC-333: oklch(77% 0.005 250) !important;",
        "--dz-FC-666: oklch(71% 0.005 250) !important;",
        "--dz-FC-999: oklch(59% 0.01 250) !important;",
        "--dz-FC-ccc: oklch(65% 0.01 250) !important;",
        "--dz-FC-aaa: oklch(54% 0.01 250) !important;",
        "--dz-BOR-ed: oklch(32% 0.015 250) !important;",
        "--dz-FC-color: oklch(77% 0.005 250) !important;",
        "}",
        "html, body { background: oklch(18% 0.015 250) !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: oklch(18% 0.015 250) !important; }",
        ".header { background: oklch(21% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".header, .header h2, .header h2 a, .header a { color: oklch(89% 0.005 250) !important; }",
        ".header_toplogo { background: oklch(21% 0.015 250) !important; }",
        ".header_toplogo p { color: oklch(89% 0.005 250) !important; }",
        "#nav-more-menu { background: oklch(23% 0.015 250) !important; }",
        ".nav-more-item { color: oklch(77% 0.005 250) !important; }",
        ".nav-more-item-text { color: oklch(77% 0.005 250) !important; }",
        ".nav-more-item svg { fill: oklch(77% 0.005 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: oklch(21% 0.015 250) !important; }",
        ".sq_nav a, .thread_nav a { color: oklch(71% 0.005 250) !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: oklch(98% 0.005 250) !important; background: oklch(32% 0.015 250) !important; }",
        ".z, .vertical_tab { background: oklch(21% 0.015 250) !important; }",
        ".z a, .vertical_tab a { color: oklch(71% 0.005 250) !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: oklch(98% 0.005 250) !important; background: oklch(32% 0.015 250) !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: oklch(23% 0.015 250) !important; }",
        ".forumdisplay-top { background: oklch(23% 0.015 250) !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: oklch(89% 0.005 250) !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: oklch(71% 0.005 250) !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: oklch(21% 0.015 250) !important; }",
        ".dhnav_box a, #dhnav a { color: oklch(71% 0.005 250) !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: oklch(98% 0.005 250) !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: oklch(48% 0.015 250) !important; }",
        ".dhnavs_box, #dhnavs { background: oklch(21% 0.015 250) !important; }",
        "#dhnavs .swiper-slide a { color: oklch(71% 0.005 250) !important; }",
        "#dhnavs .swiper-slide.mon a { color: oklch(98% 0.005 250) !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: oklch(18% 0.015 250) !important; }",
        ".forumlist .subforumshow { background: oklch(21% 0.015 250) !important; }",
        ".subforumshow { background: oklch(21% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".subforumshow h2 a { color: oklch(77% 0.005 250) !important; }",
        ".subforumshow i { color: oklch(59% 0.01 250) !important; }",
        ".subforumshow { color: oklch(71% 0.005 250) !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: oklch(77% 0.005 250) !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: oklch(23% 0.015 250) !important; }",
        ".mlist1 li { border-color: oklch(28% 0.015 250) !important; }",
        ".mlist1 .mtit { color: oklch(89% 0.005 250) !important; }",
        ".mlist1 .mtxt { color: oklch(65% 0.01 250) !important; }",
        ".mlist1 .mnum { color: oklch(54% 0.01 250) !important; }",
        ".bm, .bm_c, .bm_h { background: oklch(23% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: oklch(77% 0.005 250) !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: oklch(23% 0.015 250) !important; }",
        ".threadlist { background: oklch(21% 0.015 250) !important; }",
        ".threadlist li, .threadlist li a { background: oklch(23% 0.015 250) !important; }",
        ".tl_bm li, .threadlist li { border-color: oklch(28% 0.015 250) !important; }",
        ".tl_bm a, .threadlist a { color: oklch(77% 0.005 250) !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: oklch(23% 0.015 250) !important; color: oklch(89% 0.005 250) !important; }",
        ".pls, .pls div, .pls a { background: oklch(21% 0.015 250) !important; }",
        ".pls, .pls a, .pls em, .pls span { color: oklch(77% 0.005 250) !important; }",
        ".plc, .plm, .plc div, .plm div { background: oklch(23% 0.015 250) !important; }",
        ".authi, .authi em, .authi a, .authi span { color: oklch(71% 0.005 250) !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: oklch(77% 0.005 250) !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: oklch(78% 0.12 245) !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: oklch(26% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(38% 0.015 250) !important; }",
        ".page { background: oklch(18% 0.015 250) !important; }",
        ".page a { background: oklch(26% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(38% 0.015 250) !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: oklch(38% 0.015 250) !important; color: oklch(98% 0.005 250) !important; }",
        ".xi1 { color: oklch(89% 0.005 250) !important; }",
        ".xi2 { color: oklch(77% 0.005 250) !important; }",
        ".xg1, .xg1 a { color: oklch(71% 0.005 250) !important; }",
        ".xg2, .xg2 a { color: oklch(65% 0.01 250) !important; }",
        ".num, .views, .replies { color: oklch(59% 0.01 250) !important; }",
        ".ts, .time { color: oklch(54% 0.01 250) !important; }",
        ".pipe { color: oklch(38% 0.015 250) !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: oklch(59% 0.01 250) !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: oklch(77% 0.005 250) !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: oklch(23% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".foot_reply a, .f_c a, .foot a { color: oklch(77% 0.005 250) !important; }",
        ".viewt-reply, .viewt-reply a { background: oklch(32% 0.015 250) !important; color: oklch(98% 0.005 250) !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: oklch(77% 0.005 250) !important; }",
        ".my, .my a, .my i, .my span { color: oklch(77% 0.005 250) !important; }",
        ".mz, .mz a, .mz i, .mz span { color: oklch(71% 0.005 250) !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: oklch(23% 0.015 250) !important; }",
        ".myinfo_list_ico li { background: oklch(23% 0.015 250) !important; }",
        ".myinfo_list_ico li a { background: oklch(21% 0.015 250) !important; }",
        ".myinfo_list_ico li { border-color: oklch(28% 0.015 250) !important; }",
        ".myinfo_list_ico li a { color: oklch(77% 0.005 250) !important; }",
        ".myinfo_list_ico li i { color: oklch(95% 0.005 250) !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: oklch(23% 0.015 250) !important; }",
        ".myinfo a, .myinfo_menu a { color: oklch(77% 0.005 250) !important; }",
        ".myinfo_list, .myinfo_list li { border-color: oklch(28% 0.015 250) !important; }",
        ".myinfo_list li span { color: oklch(71% 0.005 250) !important; }",
        ".myinfo_list b { color: oklch(77% 0.005 250) !important; }",
        ".mtag, .profile_tag { color: oklch(59% 0.01 250) !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(28% 0.015 250) !important; }",
        ".footer, #footer { background: oklch(21% 0.015 250) !important; color: oklch(59% 0.01 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".footer a, #footer a { color: oklch(59% 0.01 250) !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: oklch(21% 0.015 250) !important; color: oklch(59% 0.01 250) !important; }",
        ".footer .mon { color: oklch(59% 0.01 250) !important; }",
        "input, select, textarea { background: oklch(28% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(38% 0.015 250) !important; }",
        "blockquote, .quote, .blockcode { background: oklch(26% 0.015 250) !important; border-color: oklch(38% 0.015 250) !important; color: oklch(71% 0.005 250) !important; }",
        "hr, .line, .partition { border-color: oklch(32% 0.015 250) !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: oklch(26% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(38% 0.015 250) !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: oklch(32% 0.015 250) !important; }",
        ".btn, .button, button, .pn, .pnc { background: oklch(32% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(43% 0.015 250) !important; }",
        "#postform, #fastpostform, .area textarea { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(38% 0.015 250) !important; }",
        ".psth { background: oklch(21% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".psth .icon_ring { color: oklch(59% 0.01 250) !important; }",
        ".showcollapse_box { background: oklch(23% 0.015 250) !important; border-color: oklch(28% 0.015 250) !important; }",
        ".showcollapse_title { background: oklch(26% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".showcollapse_content { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".showcollapse_content a { color: oklch(78% 0.12 245) !important; }",
        ".showcollapse_gather { color: oklch(59% 0.01 250) !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: oklch(77% 0.005 250) !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: oklch(67% 0.17 25) !important; }",
        ".threadlist_foot { background: oklch(23% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: oklch(59% 0.01 250) !important; }",
        ".threadlist_foot li { border: 1px solid oklch(43% 0.015 250) !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: oklch(59% 0.01 250) !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: oklch(23% 0.015 250) !important; }",
        ".threadlist_top .mmc { color: oklch(77% 0.005 250) !important; }",
        ".threadlist_top .mtime { color: oklch(54% 0.01 250) !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: oklch(23% 0.015 250) !important; color: oklch(89% 0.005 250) !important; }",
        ".threadlist_mes, .threadlist_mes a { background: oklch(23% 0.015 250) !important; color: oklch(65% 0.01 250) !important; }",
        ".discuz_x { background: oklch(23% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".txtlist, .txtlist .mtit { background: oklch(21% 0.015 250) !important; color: oklch(77% 0.005 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".txtlist .ytxt, .txtlist a { color: oklch(71% 0.005 250) !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: oklch(59% 0.01 250) !important; }",
        ".y, .y span, .y em, .y i { color: oklch(59% 0.01 250) !important; }",
        ".pstatus, .pstatus font { color: oklch(59% 0.01 250) !important; }",
        ".float-menu-item { background: oklch(32% 0.015 250 / 0.85) !important; color: oklch(77% 0.005 250) !important; }",
        ".float-menu-item svg { fill: oklch(77% 0.005 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".scrolltop { background: oklch(32% 0.015 250) !important; }",
        "#mask { background: oklch(10% 0.01 250 / 0.7) !important; }",
        "strong, b, .strong { color: oklch(89% 0.005 250) !important; }",
        "sup, sub { color: oklch(71% 0.005 250) !important; }",
        "em { color: oklch(77% 0.005 250) !important; }",
        ".display, .pi { background: oklch(23% 0.015 250) !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: oklch(21% 0.015 250) !important; }",
        ".hui-header h1 { color: oklch(89% 0.005 250) !important; }",
        ".hui-slide-menu { background: oklch(23% 0.015 250) !important; }",
        ".hui-slide-menu li { color: oklch(77% 0.005 250) !important; }",
        ".hui-wrap { background: oklch(18% 0.015 250) !important; }",
        ".hui-common-title-line { border-color: oklch(32% 0.015 250) !important; }",
        ".hui-common-title-txt { color: oklch(77% 0.005 250) !important; }",
        ".hui-content { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".hui-media-list li { background: oklch(23% 0.015 250) !important; border-color: oklch(32% 0.015 250) !important; }",
        ".hui-media-content { background: oklch(23% 0.015 250) !important; }",
        ".hui-media-content p { color: oklch(77% 0.005 250) !important; }",
        ".hui-media-content a { color: oklch(78% 0.12 245) !important; }",
        ".hui-list { background: oklch(23% 0.015 250) !important; }",
        ".hui-list-text { color: oklch(77% 0.005 250) !important; }",
        ".hui-center-title h2 { color: oklch(89% 0.005 250) !important; }",
        ".hui-button { background: oklch(32% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".hui-primary { background: oklch(65% 0.16 45) !important; color: oklch(98% 0.005 250) !important; }",
        ".fl-table { background: oklch(23% 0.015 250) !important; }",
        ".fl-table th { background: oklch(26% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".fl-table td { background: oklch(23% 0.015 250) !important; color: oklch(77% 0.005 250) !important; }",
        ".day { color: oklch(77% 0.005 250) !important; }",
        ".day.today { background: oklch(65% 0.16 45) !important; color: oklch(98% 0.005 250) !important; }",
        ".lunar { color: oklch(59% 0.01 250) !important; }",
        ".signbtn .btna { background: oklch(65% 0.16 45) !important; color: oklch(98% 0.005 250) !important; }",
        ".authi .mtit, .authi .mtime { color: oklch(59% 0.01 250) !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 3. OLED 极致黑主题
    private val DARK_MODE_CSS_RULES_OLED = listOf(
        "/* === CSS 变量覆盖 (OLED 极致黑版) === */",
        "body {",
        "--dz-BG-body: oklch(0% 0 0) !important;",
        "--dz-BG-0: oklch(12% 0 0) !important;",
        "--dz-BG-5: oklch(16% 0 0) !important;",
        "--dz-BG-6: oklch(25% 0 0) !important;",
        "--dz-FC-fff: oklch(85% 0 0) !important;",
        "--dz-FC-333: oklch(75% 0 0) !important;",
        "--dz-FC-666: oklch(65% 0 0) !important;",
        "--dz-FC-999: oklch(55% 0 0) !important;",
        "--dz-FC-ccc: oklch(60% 0 0) !important;",
        "--dz-FC-aaa: oklch(50% 0 0) !important;",
        "--dz-BOR-ed: oklch(20% 0 0) !important;",
        "--dz-FC-color: oklch(75% 0 0) !important;",
        "}",
        "html, body { background: oklch(0% 0 0) !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: oklch(0% 0 0) !important; }",
        ".header { background: oklch(8% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".header, .header h2, .header h2 a, .header a { color: oklch(85% 0 0) !important; }",
        ".header_toplogo { background: oklch(8% 0 0) !important; }",
        ".header_toplogo p { color: oklch(85% 0 0) !important; }",
        "#nav-more-menu { background: oklch(12% 0 0) !important; }",
        ".nav-more-item { color: oklch(75% 0 0) !important; }",
        ".nav-more-item-text { color: oklch(75% 0 0) !important; }",
        ".nav-more-item svg { fill: oklch(75% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: oklch(8% 0 0) !important; }",
        ".sq_nav a, .thread_nav a { color: oklch(65% 0 0) !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: oklch(95% 0 0) !important; background: oklch(20% 0 0) !important; }",
        ".z, .vertical_tab { background: oklch(8% 0 0) !important; }",
        ".z a, .vertical_tab a { color: oklch(65% 0 0) !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: oklch(95% 0 0) !important; background: oklch(20% 0 0) !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: oklch(12% 0 0) !important; }",
        ".forumdisplay-top { background: oklch(12% 0 0) !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: oklch(85% 0 0) !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: oklch(65% 0 0) !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: oklch(8% 0 0) !important; }",
        ".dhnav_box a, #dhnav a { color: oklch(65% 0 0) !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: oklch(95% 0 0) !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: oklch(35% 0 0) !important; }",
        ".dhnavs_box, #dhnavs { background: oklch(8% 0 0) !important; }",
        "#dhnavs .swiper-slide a { color: oklch(65% 0 0) !important; }",
        "#dhnavs .swiper-slide.mon a { color: oklch(95% 0 0) !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: oklch(0% 0 0) !important; }",
        ".forumlist .subforumshow { background: oklch(8% 0 0) !important; }",
        ".subforumshow { background: oklch(8% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".subforumshow h2 a { color: oklch(75% 0 0) !important; }",
        ".subforumshow i { color: oklch(55% 0 0) !important; }",
        ".subforumshow { color: oklch(65% 0 0) !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: oklch(75% 0 0) !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: oklch(12% 0 0) !important; }",
        ".mlist1 li { border-color: oklch(18% 0 0) !important; }",
        ".mlist1 .mtit { color: oklch(85% 0 0) !important; }",
        ".mlist1 .mtxt { color: oklch(60% 0 0) !important; }",
        ".mlist1 .mnum { color: oklch(50% 0 0) !important; }",
        ".bm, .bm_c, .bm_h { background: oklch(12% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: oklch(75% 0 0) !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: oklch(12% 0 0) !important; }",
        ".threadlist { background: oklch(8% 0 0) !important; }",
        ".threadlist li, .threadlist li a { background: oklch(12% 0 0) !important; }",
        ".tl_bm li, .threadlist li { border-color: oklch(18% 0 0) !important; }",
        ".tl_bm a, .threadlist a { color: oklch(75% 0 0) !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: oklch(12% 0 0) !important; color: oklch(85% 0 0) !important; }",
        ".pls, .pls div, .pls a { background: oklch(8% 0 0) !important; }",
        ".pls, .pls a, .pls em, .pls span { color: oklch(75% 0 0) !important; }",
        ".plc, .plm, .plc div, .plm div { background: oklch(12% 0 0) !important; }",
        ".authi, .authi em, .authi a, .authi span { color: oklch(65% 0 0) !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: oklch(75% 0 0) !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: oklch(75% 0.12 245) !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: oklch(16% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(25% 0 0) !important; }",
        ".page { background: oklch(0% 0 0) !important; }",
        ".page a { background: oklch(16% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(25% 0 0) !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: oklch(25% 0 0) !important; color: oklch(95% 0 0) !important; }",
        ".xi1 { color: oklch(85% 0 0) !important; }",
        ".xi2 { color: oklch(75% 0 0) !important; }",
        ".xg1, .xg1 a { color: oklch(65% 0 0) !important; }",
        ".xg2, .xg2 a { color: oklch(60% 0 0) !important; }",
        ".num, .views, .replies { color: oklch(55% 0 0) !important; }",
        ".ts, .time { color: oklch(50% 0 0) !important; }",
        ".pipe { color: oklch(20% 0 0) !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: oklch(55% 0 0) !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: oklch(75% 0 0) !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: oklch(12% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".foot_reply a, .f_c a, .foot a { color: oklch(75% 0 0) !important; }",
        ".viewt-reply, .viewt-reply a { background: oklch(20% 0 0) !important; color: oklch(95% 0 0) !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: oklch(75% 0 0) !important; }",
        ".my, .my a, .my i, .my span { color: oklch(75% 0 0) !important; }",
        ".mz, .mz a, .mz i, .mz span { color: oklch(65% 0 0) !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: oklch(12% 0 0) !important; }",
        ".myinfo_list_ico li  { background: oklch(12% 0 0) !important; }",
        ".myinfo_list_ico li a { background: oklch(8% 0 0) !important; }",
        ".myinfo_list_ico li { border-color: oklch(18% 0 0) !important; }",
        ".myinfo_list_ico li a { color: oklch(75% 0 0) !important; }",
        ".myinfo_list_ico li i { color: oklch(90% 0 0) !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: oklch(12% 0 0) !important; }",
        ".myinfo a, .myinfo_menu a { color: oklch(75% 0 0) !important; }",
        ".myinfo_list, .myinfo_list li { border-color: oklch(18% 0 0) !important; }",
        ".myinfo_list li span { color: oklch(65% 0 0) !important; }",
        ".myinfo_list b { color: oklch(75% 0 0) !important; }",
        ".mtag, .profile_tag { color: oklch(55% 0 0) !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(18% 0 0) !important; }",
        ".footer, #footer { background: oklch(8% 0 0) !important; color: oklch(55% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".footer a, #footer a { color: oklch(55% 0 0) !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: oklch(8% 0 0) !important; color: oklch(55% 0 0) !important; }",
        ".footer .mon { color: oklch(55% 0 0) !important; }",
        "input, select, textarea { background: oklch(18% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(25% 0 0) !important; }",
        "blockquote, .quote, .blockcode { background: oklch(16% 0 0) !important; border-color: oklch(25% 0 0) !important; color: oklch(65% 0 0) !important; }",
        "hr, .line, .partition { border-color: oklch(20% 0 0) !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: oklch(16% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(25% 0 0) !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: oklch(20% 0 0) !important; }",
        ".btn, .button, button, .pn, .pnc { background: oklch(20% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(30% 0 0) !important; }",
        "#postform, #fastpostform, .area textarea { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(25% 0 0) !important; }",
        ".psth { background: oklch(8% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".psth .icon_ring { color: oklch(55% 0 0) !important; }",
        ".showcollapse_box { background: oklch(12% 0 0) !important; border-color: oklch(18% 0 0) !important; }",
        ".showcollapse_title { background: oklch(16% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".showcollapse_content { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".showcollapse_content a { color: oklch(75% 0.12 245) !important; }",
        ".showcollapse_gather { color: oklch(55% 0 0) !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: oklch(75% 0 0) !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: oklch(60% 0.17 25) !important; }",
        ".threadlist_foot { background: oklch(12% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: oklch(55% 0 0) !important; }",
        ".threadlist_foot li { border: 1px solid oklch(30% 0 0) !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: oklch(55% 0 0) !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: oklch(12% 0 0) !important; }",
        ".threadlist_top .mmc { color: oklch(75% 0 0) !important; }",
        ".threadlist_top .mtime { color: oklch(50% 0 0) !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: oklch(12% 0 0) !important; color: oklch(85% 0 0) !important; }",
        ".threadlist_mes, .threadlist_mes a { background: oklch(12% 0 0) !important; color: oklch(60% 0 0) !important; }",
        ".discuz_x { background: oklch(12% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".txtlist, .txtlist .mtit { background: oklch(8% 0 0) !important; color: oklch(75% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".txtlist .ytxt, .txtlist a { color: oklch(65% 0 0) !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: oklch(55% 0 0) !important; }",
        ".y, .y span, .y em, .y i { color: oklch(55% 0 0) !important; }",
        ".pstatus, .pstatus font { color: oklch(55% 0 0) !important; }",
        ".float-menu-item { background: oklch(20% 0 0 / 0.85) !important; color: oklch(75% 0 0) !important; }",
        ".float-menu-item svg { fill: oklch(75% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".scrolltop { background: oklch(20% 0 0) !important; }",
        "#mask { background: oklch(0% 0 0 / 0.8) !important; }",
        "strong, b, .strong { color: oklch(85% 0 0) !important; }",
        "sup, sub { color: oklch(65% 0 0) !important; }",
        "em { color: oklch(75% 0 0) !important; }",
        ".display, .pi { background: oklch(12% 0 0) !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: oklch(8% 0 0) !important; }",
        ".hui-header h1 { color: oklch(85% 0 0) !important; }",
        ".hui-slide-menu { background: oklch(12% 0 0) !important; }",
        ".hui-slide-menu li { color: oklch(75% 0 0) !important; }",
        ".hui-wrap { background: oklch(0% 0 0) !important; }",
        ".hui-common-title-line { border-color: oklch(20% 0 0) !important; }",
        ".hui-common-title-txt { color: oklch(75% 0 0) !important; }",
        ".hui-content { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".hui-media-list li { background: oklch(12% 0 0) !important; border-color: oklch(20% 0 0) !important; }",
        ".hui-media-content { background: oklch(12% 0 0) !important; }",
        ".hui-media-content p { color: oklch(75% 0 0) !important; }",
        ".hui-media-content a { color: oklch(75% 0.12 245) !important; }",
        ".hui-list { background: oklch(12% 0 0) !important; }",
        ".hui-list-text { color: oklch(75% 0 0) !important; }",
        ".hui-center-title h2 { color: oklch(85% 0 0) !important; }",
        ".hui-button { background: oklch(20% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".hui-primary { background: oklch(55% 0.16 45) !important; color: oklch(95% 0 0) !important; }",
        ".fl-table { background: oklch(12% 0 0) !important; }",
        ".fl-table th { background: oklch(16% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".fl-table td { background: oklch(12% 0 0) !important; color: oklch(75% 0 0) !important; }",
        ".day { color: oklch(75% 0 0) !important; }",
        ".day.today { background: oklch(55% 0.16 45) !important; color: oklch(95% 0 0) !important; }",
        ".lunar { color: oklch(55% 0 0) !important; }",
        ".signbtn .btna { background: oklch(55% 0.16 45) !important; color: oklch(95% 0 0) !important; }",
        ".authi .mtit, .authi .mtime { color: oklch(55% 0 0) !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 4. 静谧紫夜 Twilight 主题
    private val DARK_MODE_CSS_RULES_TWILIGHT = listOf(
        "/* === CSS 变量覆盖 (静谧紫夜 Twilight 环境色版) === */",
        "body {",
        "--dz-BG-body: oklch(17% 0.02 285) !important;",
        "--dz-BG-0: oklch(22% 0.02 285) !important;",
        "--dz-BG-5: oklch(25% 0.02 285) !important;",
        "--dz-BG-6: oklch(35% 0.02 285) !important;",
        "--dz-FC-fff: oklch(90% 0.005 285) !important;",
        "--dz-FC-333: oklch(78% 0.005 285) !important;",
        "--dz-FC-666: oklch(70% 0.005 285) !important;",
        "--dz-FC-999: oklch(58% 0.01 285) !important;",
        "--dz-FC-ccc: oklch(65% 0.01 285) !important;",
        "--dz-FC-aaa: oklch(54% 0.01 285) !important;",
        "--dz-BOR-ed: oklch(30% 0.02 285) !important;",
        "--dz-FC-color: oklch(78% 0.005 285) !important;",
        "}",
        "html, body { background: oklch(17% 0.02 285) !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: oklch(17% 0.02 285) !important; }",
        ".header { background: #191925 !important; border-color: oklch(30% 0.02 285) !important; }",
        ".header, .header h2, .header h2 a, .header a { color: oklch(90% 0.005 285) !important; }",
        ".header_toplogo { background: #191925 !important; }",
        ".header_toplogo p { color: oklch(90% 0.005 285) !important; }",
        "#nav-more-menu { background: oklch(22% 0.02 285) !important; }",
        ".nav-more-item { color: oklch(78% 0.005 285) !important; }",
        ".nav-more-item-text { color: oklch(78% 0.005 285) !important; }",
        ".nav-more-item svg { fill: oklch(78% 0.005 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: oklch(20% 0.02 285) !important; }",
        ".sq_nav a, .thread_nav a { color: oklch(70% 0.005 285) !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: oklch(98% 0.005 285) !important; background: oklch(30% 0.02 285) !important; }",
        ".z, .vertical_tab { background: oklch(20% 0.02 285) !important; }",
        ".z a, .vertical_tab a { color: oklch(70% 0.005 285) !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: oklch(98% 0.005 285) !important; background: oklch(30% 0.02 285) !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: oklch(22% 0.02 285) !important; }",
        ".forumdisplay-top { background: oklch(22% 0.02 285) !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: oklch(90% 0.005 285) !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: oklch(70% 0.005 285) !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: oklch(20% 0.02 285) !important; }",
        ".dhnav_box a, #dhnav a { color: oklch(70% 0.005 285) !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: oklch(98% 0.005 285) !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: oklch(45% 0.02 285) !important; }",
        ".dhnavs_box, #dhnavs { background: oklch(20% 0.02 285) !important; }",
        "#dhnavs .swiper-slide a { color: oklch(70% 0.005 285) !important; }",
        "#dhnavs .swiper-slide.mon a { color: oklch(98% 0.005 285) !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: oklch(17% 0.02 285) !important; }",
        ".forumlist .subforumshow { background: oklch(20% 0.02 285) !important; }",
        ".subforumshow { background: oklch(20% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".subforumshow h2 a { color: oklch(78% 0.005 285) !important; }",
        ".subforumshow i { color: oklch(58% 0.01 285) !important; }",
        ".subforumshow { color: oklch(70% 0.005 285) !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: oklch(78% 0.005 285) !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: oklch(22% 0.02 285) !important; }",
        ".mlist1 li { border-color: oklch(27% 0.02 285) !important; }",
        ".mlist1 .mtit { color: oklch(90% 0.005 285) !important; }",
        ".mlist1 .mtxt { color: oklch(65% 0.01 285) !important; }",
        ".mlist1 .mnum { color: oklch(54% 0.01 285) !important; }",
        ".bm, .bm_c, .bm_h { background: oklch(22% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: oklch(78% 0.005 285) !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: oklch(22% 0.02 285) !important; }",
        ".threadlist { background: oklch(20% 0.02 285) !important; }",
        ".threadlist li, .threadlist li a { background: oklch(22% 0.02 285) !important; }",
        ".tl_bm li, .threadlist li { border-color: oklch(27% 0.02 285) !important; }",
        ".tl_bm a, .threadlist a { color: oklch(78% 0.005 285) !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: oklch(22% 0.02 285) !important; color: oklch(90% 0.005 285) !important; }",
        ".pls, .pls div, .pls a { background: oklch(20% 0.02 285) !important; }",
        ".pls, .pls a, .pls em, .pls span { color: oklch(78% 0.005 285) !important; }",
        ".plc, .plm, .plc div, .plm div { background: oklch(22% 0.02 285) !important; }",
        ".authi, .authi em, .authi a, .authi span { color: oklch(70% 0.005 285) !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: oklch(78% 0.005 285) !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: oklch(80% 0.12 285) !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: oklch(25% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(35% 0.02 285) !important; }",
        ".page { background: oklch(17% 0.02 285) !important; }",
        ".page a { background: oklch(25% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(35% 0.02 285) !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: oklch(35% 0.02 285) !important; color: oklch(98% 0.005 285) !important; }",
        ".xi1 { color: oklch(90% 0.005 285) !important; }",
        ".xi2 { color: oklch(78% 0.005 285) !important; }",
        ".xg1, .xg1 a { color: oklch(70% 0.005 285) !important; }",
        ".xg2, .xg2 a { color: oklch(65% 0.01 285) !important; }",
        ".num, .views, .replies { color: oklch(58% 0.01 285) !important; }",
        ".ts, .time { color: oklch(54% 0.01 285) !important; }",
        ".pipe { color: oklch(30% 0.02 285) !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: oklch(58% 0.01 285) !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: oklch(78% 0.005 285) !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: oklch(22% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".foot_reply a, .f_c a, .foot a { color: oklch(78% 0.005 285) !important; }",
        ".viewt-reply, .viewt-reply a { background: oklch(30% 0.02 285) !important; color: oklch(98% 0.005 285) !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: oklch(78% 0.005 285) !important; }",
        ".my, .my a, .my i, .my span { color: oklch(78% 0.005 285) !important; }",
        ".mz, .mz a, .mz i, .mz span { color: oklch(70% 0.005 285) !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: oklch(22% 0.02 285) !important; }",
        ".myinfo_list_ico li  { background: oklch(22% 0.02 285) !important; }",
        ".myinfo_list_ico li a { background: oklch(20% 0.02 285) !important; }",
        ".myinfo_list_ico li { border-color: oklch(27% 0.02 285) !important; }",
        ".myinfo_list_ico li a { color: oklch(78% 0.005 285) !important; }",
        ".myinfo_list_ico li i { color: oklch(95% 0.005 285) !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: oklch(22% 0.02 285) !important; }",
        ".myinfo a, .myinfo_menu a { color: oklch(78% 0.005 285) !important; }",
        ".myinfo_list, .myinfo_list li { border-color: oklch(27% 0.02 285) !important; }",
        ".myinfo_list li span { color: oklch(70% 0.005 285) !important; }",
        ".myinfo_list b { color: oklch(78% 0.005 285) !important; }",
        ".mtag, .profile_tag { color: oklch(58% 0.01 285) !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(27% 0.02 285) !important; }",
        ".footer, #footer { background: #191925 !important; color: oklch(58% 0.01 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".footer a, #footer a { color: oklch(58% 0.01 285) !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: #191925 !important; color: oklch(58% 0.01 285) !important; }",
        ".footer .mon { color: oklch(58% 0.01 285) !important; }",
        "input, select, textarea { background: oklch(27% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(35% 0.02 285) !important; }",
        "blockquote, .quote, .blockcode { background: oklch(25% 0.02 285) !important; border-color: oklch(35% 0.02 285) !important; color: oklch(70% 0.005 285) !important; }",
        "hr, .line, .partition { border-color: oklch(30% 0.02 285) !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: oklch(25% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(35% 0.02 285) !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: oklch(30% 0.02 285) !important; }",
        ".btn, .button, button, .pn, .pnc { background: oklch(30% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(40% 0.02 285) !important; }",
        "#postform, #fastpostform, .area textarea { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(35% 0.02 285) !important; }",
        ".psth { background: oklch(20% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".psth .icon_ring { color: oklch(58% 0.01 285) !important; }",
        ".showcollapse_box { background: oklch(22% 0.02 285) !important; border-color: oklch(27% 0.02 285) !important; }",
        ".showcollapse_title { background: oklch(25% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".showcollapse_content { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".showcollapse_content a { color: oklch(80% 0.12 285) !important; }",
        ".showcollapse_gather { color: oklch(58% 0.01 285) !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: oklch(78% 0.005 285) !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: oklch(67% 0.17 25) !important; }",
        ".threadlist_foot { background: oklch(22% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: oklch(58% 0.01 285) !important; }",
        ".threadlist_foot li { border: 1px solid oklch(40% 0.02 285) !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: oklch(58% 0.01 285) !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: oklch(22% 0.02 285) !important; }",
        ".threadlist_top .mmc { color: oklch(78% 0.005 285) !important; }",
        ".threadlist_top .mtime { color: oklch(54% 0.01 285) !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: oklch(22% 0.02 285) !important; color: oklch(90% 0.005 285) !important; }",
        ".threadlist_mes, .threadlist_mes a { background: oklch(22% 0.02 285) !important; color: oklch(65% 0.01 285) !important; }",
        ".discuz_x { background: oklch(22% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".txtlist, .txtlist .mtit { background: oklch(20% 0.02 285) !important; color: oklch(78% 0.005 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".txtlist .ytxt, .txtlist a { color: oklch(70% 0.005 285) !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: oklch(58% 0.01 285) !important; }",
        ".y, .y span, .y em, .y i { color: oklch(58% 0.01 285) !important; }",
        ".pstatus, .pstatus font { color: oklch(58% 0.01 285) !important; }",
        ".float-menu-item { background: oklch(30% 0.02 285 / 0.85) !important; color: oklch(78% 0.005 285) !important; }",
        ".float-menu-item svg { fill: oklch(78% 0.005 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".scrolltop { background: oklch(30% 0.02 285) !important; }",
        "#mask { background: oklch(10% 0.02 285 / 0.7) !important; }",
        "strong, b, .strong { color: oklch(90% 0.005 285) !important; }",
        "sup, sub { color: oklch(70% 0.005 285) !important; }",
        "em { color: oklch(78% 0.005 285) !important; }",
        ".display, .pi { background: oklch(22% 0.02 285) !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: oklch(20% 0.02 285) !important; }",
        ".hui-header h1 { color: oklch(90% 0.005 285) !important; }",
        ".hui-slide-menu { background: oklch(22% 0.02 285) !important; }",
        ".hui-slide-menu li { color: oklch(78% 0.005 285) !important; }",
        ".hui-wrap { background: oklch(17% 0.02 285) !important; }",
        ".hui-common-title-line { border-color: oklch(30% 0.02 285) !important; }",
        ".hui-common-title-txt { color: oklch(78% 0.005 285) !important; }",
        ".hui-content { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".hui-media-list li { background: oklch(22% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".hui-media-content { background: oklch(22% 0.02 285) !important; }",
        ".hui-media-content p { color: oklch(78% 0.005 285) !important; }",
        ".hui-media-content a { color: oklch(80% 0.12 285) !important; }",
        ".hui-list { background: oklch(22% 0.02 285) !important; }",
        ".hui-list-text { color: oklch(78% 0.005 285) !important; }",
        ".hui-center-title h2 { color: oklch(90% 0.005 285) !important; }",
        ".hui-button { background: oklch(30% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".hui-primary { background: oklch(62% 0.15 285) !important; color: oklch(98% 0.005 285) !important; }",
        ".fl-table { background: oklch(22% 0.02 285) !important; }",
        ".fl-table th { background: oklch(25% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".fl-table td { background: oklch(22% 0.02 285) !important; color: oklch(78% 0.005 285) !important; }",
        ".day { color: oklch(78% 0.005 285) !important; }",
        ".day.today { background: oklch(62% 0.15 285) !important; color: oklch(98% 0.005 285) !important; }",
        ".lunar { color: oklch(58% 0.01 285) !important; }",
        ".signbtn .btna { background: oklch(62% 0.15 285) !important; color: oklch(98% 0.005 285) !important; }",
        ".authi .mtit, .authi .mtime { color: oklch(58% 0.01 285) !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 5. 暖纸 (SEPIA_PAPER) — 复古书页风格：深陶土棕 header + 暖米黄底 + 象牙白卡 + 铁锈橙主色
    private val LIGHT_MODE_CSS_RULES_SEPIA_PAPER = listOf(
        "/* === CSS 变量覆盖 (暖纸日间版 · 复古书页) === */",
        "body {",
        "--dz-BG-body: #F2E8D5 !important;",
        "--dz-BG-0: #FFFBF3 !important;",
        "--dz-BG-5: #E6D7B5 !important;",
        "--dz-BG-6: #C9B388 !important;",
        "--dz-FC-fff: #3D2817 !important;",
        "--dz-FC-333: #3D2817 !important;",
        "--dz-FC-666: #7A6651 !important;",
        "--dz-FC-999: #8F7E68 !important;",
        "--dz-FC-ccc: #BFB298 !important;",
        "--dz-FC-aaa: #A99B82 !important;",
        "--dz-BOR-ed: #D6C5A8 !important;",
        "--dz-FC-color: #B85530 !important;",
        "}",
        "html, body { background: #F2E8D5 !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: #F2E8D5 !important; }",
        ".header { background: #7A3E1E !important; border-color: #7A3E1E !important; }",
        ".header, .header h2, .header h2 a, .header a { color: #FFFFFF !important; }",
        ".header i { color: #FFFFFF !important; }",
        ".header_toplogo { background: #7A3E1E !important; }",
        ".header_toplogo p { color: #FFFFFF !important; }",
        ".header .myss a { background: #FFFBF3 !important; color: #A99B82 !important; }",
        ".header .myss a i { color: #A99B82 !important; }",
        "#nav-more-menu { background: #FFFBF3 !important; }",
        ".nav-more-item { color: #3D2817 !important; }",
        ".nav-more-item-text { color: #3D2817 !important; }",
        ".nav-more-item svg { fill: #3D2817 !important; color: #3D2817 !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: #E6D7B5 !important; }",
        ".sq_nav a, .thread_nav a { color: #7A6651 !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: #241606 !important; background: #D9C8A3 !important; }",
        ".z, .vertical_tab { background: #E6D7B5 !important; }",
        ".z a, .vertical_tab a { color: #7A6651 !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: #241606 !important; background: #D9C8A3 !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: #FFFBF3 !important; }",
        ".forumdisplay-top { background: #FFFBF3 !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: #3D2817 !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: #7A6651 !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: #E6D7B5 !important; }",
        ".dhnav_box a, #dhnav a { color: #7A6651 !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: #241606 !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: #B85530 !important; color: #9C3D1E !important; }",
        ".dhnavs_box, #dhnavs { background: #E6D7B5 !important; }",
        "#dhnavs .swiper-slide a { color: #7A6651 !important; }",
        "#dhnavs .swiper-slide.mon a { color: #241606 !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: #F2E8D5 !important; }",
        ".forumlist .subforumshow { background: #E6D7B5 !important; }",
        ".subforumshow { background: #E6D7B5 !important; border-color: #D6C5A8 !important; }",
        ".subforumshow h2 a { color: #3D2817 !important; }",
        ".subforumshow i { color: #7A6651 !important; }",
        ".subforumshow { color: #7A6651 !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: #3D2817 !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: #FFFBF3 !important; }",
        ".mlist1 li { border-color: #D6C5A8 !important; }",
        ".mlist1 .mtit { color: #3D2817 !important; }",
        ".mlist1 .mtxt { color: #8F7E68 !important; }",
        ".mlist1 .mnum { color: #A99B82 !important; }",
        ".bm, .bm_c, .bm_h { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: #3D2817 !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: #FFFBF3 !important; }",
        ".threadlist { background: #E6D7B5 !important; }",
        ".threadlist li, .threadlist li a { background: #FFFBF3 !important; }",
        ".tl_bm li, .threadlist li { border-color: #D6C5A8 !important; }",
        ".tl_bm a, .threadlist a { color: #3D2817 !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".pls, .pls div, .pls a { background: #E6D7B5 !important; }",
        ".pls, .pls a, .pls em, .pls span { color: #3D2817 !important; }",
        ".plc, .plm, .plc div, .plm div { background: #FFFBF3 !important; }",
        ".authi, .authi em, .authi a, .authi span { color: #7A6651 !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: #3D2817 !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: #9C3D1E !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        ".page { background: #F2E8D5 !important; }",
        ".page a { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: #B85530 !important; color: #FFFFFF !important; }",
        ".xi1 { color: #3D2817 !important; }",
        ".xi2 { color: #3D2817 !important; }",
        ".xg1, .xg1 a { color: #7A6651 !important; }",
        ".xg2, .xg2 a { color: #8F7E68 !important; }",
        ".num, .views, .replies { color: #8F7E68 !important; }",
        ".ts, .time { color: #A99B82 !important; }",
        ".pipe { color: #BFB298 !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: #8F7E68 !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: #3D2817 !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".foot_reply a, .f_c a, .foot a { color: #3D2817 !important; }",
        ".viewt-reply, .viewt-reply a { background: #D9C8A3 !important; color: #241606 !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: #3D2817 !important; }",
        ".my, .my a { color: #FFFFFF !important; }",
        ".my i, .my span { color: #FFFFFF !important; }",
        ".mz, .mz a, .mz i, .mz span { color: #FFFFFF !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: #FFFBF3 !important; }",
        ".myinfo_list_ico li { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".myinfo_list_ico li a { background: #E6D7B5 !important; color: #3D2817 !important; }",
        ".myinfo_list_ico li i { color: #B85530 !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: #FFFBF3 !important; }",
        ".myinfo a, .myinfo_menu a { color: #3D2817 !important; }",
        ".myinfo_list, .myinfo_list li { border-color: #D6C5A8 !important; }",
        ".myinfo_list li span { color: #7A6651 !important; }",
        ".myinfo_list b { color: #3D2817 !important; }",
        ".mtag, .profile_tag { color: #8F7E68 !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: #FFFBF3 !important; color: #3D2817 !important; border-color: #D6C5A8 !important; }",
        ".footer, #footer { background: #E6D7B5 !important; color: #8F7E68 !important; border-color: #D6C5A8 !important; }",
        ".footer a, #footer a { color: #8F7E68 !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: #E6D7B5 !important; color: #8F7E68 !important; }",
        ".footer .mon { color: #8F7E68 !important; }",
        "input, select, textarea { background: #FFFBF3 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        "blockquote, .quote, .blockcode { background: #E6D7B5 !important; border-color: #C9B388 !important; color: #7A6651 !important; }",
        "hr, .line, .partition { border-color: #D6C5A8 !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: #D6C5A8 !important; }",
        ".btn, .button, button, .pn, .pnc { background: #D9C8A3 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        "#postform, #fastpostform, .area textarea { background: #FFFBF3 !important; color: #3D2817 !important; border-color: #C9B388 !important; }",
        ".psth { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #D6C5A8 !important; }",
        ".psth .icon_ring { color: #7A6651 !important; }",
        ".showcollapse_box { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".showcollapse_title { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #D6C5A8 !important; }",
        ".showcollapse_content { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".showcollapse_content a { color: #9C3D1E !important; }",
        ".showcollapse_gather { color: #8F7E68 !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: #3D2817 !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: #C0492F !important; }",
        ".threadlist_foot { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: #8F7E68 !important; }",
        ".threadlist_foot li { border: 1px solid #C9B388 !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: #8F7E68 !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: #FFFBF3 !important; }",
        ".threadlist_top .mmc { color: #3D2817 !important; }",
        ".threadlist_top .mtime { color: #A99B82 !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".threadlist_mes, .threadlist_mes a { background: #FFFBF3 !important; color: #8F7E68 !important; }",
        ".discuz_x { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".txtlist, .txtlist .mtit { background: #E6D7B5 !important; color: #3D2817 !important; border-color: #D6C5A8 !important; }",
        ".txtlist .ytxt, .txtlist a { color: #7A6651 !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: #8F7E68 !important; }",
        ".y, .y span, .y em, .y i { color: #8F7E68 !important; }",
        ".pstatus, .pstatus font { color: #8F7E68 !important; }",
        ".float-menu-item { background: rgba(255, 255, 255, 0.92) !important; color: #3D2817 !important; border: 1px solid #D6C5A8 !important; }",
        ".float-menu-item svg { fill: #3D2817 !important; color: #3D2817 !important; }",
        ".scrolltop { background: #B85530 !important; color: #FFFFFF !important; }",
        ".scrolltop i, .scrolltop svg { color: #FFFFFF !important; fill: #FFFFFF !important; }",
        "#mask { background: rgba(0,0,0,0.32) !important; }",
        "strong, b, .strong { color: #3D2817 !important; }",
        "sup, sub { color: #7A6651 !important; }",
        "em { color: #3D2817 !important; }",
        ".display, .pi { background: #FFFBF3 !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: #7A3E1E !important; }",
        ".hui-header h1 { color: #FFFFFF !important; }",
        ".hui-header a, .hui-header i { color: #FFFFFF !important; }",
        ".hui-slide-menu { background: #FFFBF3 !important; }",
        ".hui-slide-menu li { color: #3D2817 !important; }",
        ".hui-wrap { background: #F2E8D5 !important; }",
        ".hui-common-title-line { border-color: #D6C5A8 !important; }",
        ".hui-common-title-txt { color: #3D2817 !important; }",
        ".hui-content { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".hui-media-list li { background: #FFFBF3 !important; border-color: #D6C5A8 !important; }",
        ".hui-media-content { background: #FFFBF3 !important; }",
        ".hui-media-content p { color: #3D2817 !important; }",
        ".hui-media-content a { color: #9C3D1E !important; }",
        ".hui-list { background: #FFFBF3 !important; }",
        ".hui-list-text { color: #3D2817 !important; }",
        ".hui-center-title h2 { color: #3D2817 !important; }",
        ".hui-button { background: #D9C8A3 !important; color: #3D2817 !important; }",
        ".hui-primary { background: #B85530 !important; color: #FFFFFF !important; }",
        ".fl-table { background: #FFFBF3 !important; }",
        ".fl-table th { background: #E6D7B5 !important; color: #3D2817 !important; }",
        ".fl-table td { background: #FFFBF3 !important; color: #3D2817 !important; }",
        ".day { color: #3D2817 !important; }",
        ".day.today { background: #B85530 !important; color: #FFFFFF !important; }",
        ".lunar { color: #8F7E68 !important; }",
        ".signbtn .btna { background: #B85530 !important; color: #FFFFFF !important; }",
        ".authi .mtit, .authi .mtime { color: #8F7E68 !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 6. 论坛蓝 (COBALT_FORUM) — 致敬源生 #2B7ACD：深钴蓝 header + 浅灰蓝底 + 纯白卡 + 论坛蓝主色
    private val LIGHT_MODE_CSS_RULES_COBALT_FORUM = listOf(
        "/* === CSS 变量覆盖 (论坛蓝日间版 · 经典 #2B7ACD 蓝) === */",
        "body {",
        "--dz-BG-body: #EDF1F6 !important;",
        "--dz-BG-0: #FFFFFF !important;",
        "--dz-BG-5: #DDE7F1 !important;",
        "--dz-BG-6: #BAC9D9 !important;",
        "--dz-FC-fff: #1A2733 !important;",
        "--dz-FC-333: #1A2733 !important;",
        "--dz-FC-666: #5B6A7A !important;",
        "--dz-FC-999: #7C8B9A !important;",
        "--dz-FC-ccc: #B3C0CC !important;",
        "--dz-FC-aaa: #9DACBA !important;",
        "--dz-BOR-ed: #CFDAE5 !important;",
        "--dz-FC-color: #2B7ACD !important;",
        "}",
        "html, body { background: #EDF1F6 !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: #EDF1F6 !important; }",
        ".header { background: #1F5A8F !important; border-color: #1F5A8F !important; }",
        ".header, .header h2, .header h2 a, .header a { color: #FFFFFF !important; }",
        ".header i { color: #FFFFFF !important; }",
        ".header_toplogo { background: #1F5A8F !important; }",
        ".header_toplogo p { color: #FFFFFF !important; }",
        ".header .myss a { background: #FFFFFF !important; color: #9DACBA !important; }",
        ".header .myss a i { color: #9DACBA !important; }",
        "#nav-more-menu { background: #FFFFFF !important; }",
        ".nav-more-item { color: #1A2733 !important; }",
        ".nav-more-item-text { color: #1A2733 !important; }",
        ".nav-more-item svg { fill: #1A2733 !important; color: #1A2733 !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: #DDE7F1 !important; }",
        ".sq_nav a, .thread_nav a { color: #5B6A7A !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: #0E1822 !important; background: #CFDDEC !important; }",
        ".z, .vertical_tab { background: #DDE7F1 !important; }",
        ".z a, .vertical_tab a { color: #5B6A7A !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: #0E1822 !important; background: #CFDDEC !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: #FFFFFF !important; }",
        ".forumdisplay-top { background: #FFFFFF !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: #1A2733 !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: #5B6A7A !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: #DDE7F1 !important; }",
        ".dhnav_box a, #dhnav a { color: #5B6A7A !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: #0E1822 !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: #2B7ACD !important; color: #1F5A8F !important; }",
        ".dhnavs_box, #dhnavs { background: #DDE7F1 !important; }",
        "#dhnavs .swiper-slide a { color: #5B6A7A !important; }",
        "#dhnavs .swiper-slide.mon a { color: #0E1822 !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: #EDF1F6 !important; }",
        ".forumlist .subforumshow { background: #DDE7F1 !important; }",
        ".subforumshow { background: #DDE7F1 !important; border-color: #CFDAE5 !important; }",
        ".subforumshow h2 a { color: #1A2733 !important; }",
        ".subforumshow i { color: #5B6A7A !important; }",
        ".subforumshow { color: #5B6A7A !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: #1A2733 !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: #FFFFFF !important; }",
        ".mlist1 li { border-color: #CFDAE5 !important; }",
        ".mlist1 .mtit { color: #1A2733 !important; }",
        ".mlist1 .mtxt { color: #7C8B9A !important; }",
        ".mlist1 .mnum { color: #9DACBA !important; }",
        ".bm, .bm_c, .bm_h { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: #1A2733 !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: #FFFFFF !important; }",
        ".threadlist { background: #DDE7F1 !important; }",
        ".threadlist li, .threadlist li a { background: #FFFFFF !important; }",
        ".tl_bm li, .threadlist li { border-color: #CFDAE5 !important; }",
        ".tl_bm a, .threadlist a { color: #1A2733 !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".pls, .pls div, .pls a { background: #DDE7F1 !important; }",
        ".pls, .pls a, .pls em, .pls span { color: #1A2733 !important; }",
        ".plc, .plm, .plc div, .plm div { background: #FFFFFF !important; }",
        ".authi, .authi em, .authi a, .authi span { color: #5B6A7A !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: #1A2733 !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: #1F5A8F !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        ".page { background: #EDF1F6 !important; }",
        ".page a { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: #2B7ACD !important; color: #FFFFFF !important; }",
        ".xi1 { color: #1A2733 !important; }",
        ".xi2 { color: #1A2733 !important; }",
        ".xg1, .xg1 a { color: #5B6A7A !important; }",
        ".xg2, .xg2 a { color: #7C8B9A !important; }",
        ".num, .views, .replies { color: #7C8B9A !important; }",
        ".ts, .time { color: #9DACBA !important; }",
        ".pipe { color: #B3C0CC !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: #7C8B9A !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: #1A2733 !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".foot_reply a, .f_c a, .foot a { color: #1A2733 !important; }",
        ".viewt-reply, .viewt-reply a { background: #CFDDEC !important; color: #0E1822 !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: #1A2733 !important; }",
        ".my, .my a { color: #FFFFFF !important; }",
        ".my i, .my span { color: #FFFFFF !important; }",
        ".mz, .mz a, .mz i, .mz span { color: #FFFFFF !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: #FFFFFF !important; }",
        ".myinfo_list_ico li { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".myinfo_list_ico li a { background: #DDE7F1 !important; color: #1A2733 !important; }",
        ".myinfo_list_ico li i { color: #2B7ACD !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: #FFFFFF !important; }",
        ".myinfo a, .myinfo_menu a { color: #1A2733 !important; }",
        ".myinfo_list, .myinfo_list li { border-color: #CFDAE5 !important; }",
        ".myinfo_list li span { color: #5B6A7A !important; }",
        ".myinfo_list b { color: #1A2733 !important; }",
        ".mtag, .profile_tag { color: #7C8B9A !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: #FFFFFF !important; color: #1A2733 !important; border-color: #CFDAE5 !important; }",
        ".footer, #footer { background: #DDE7F1 !important; color: #7C8B9A !important; border-color: #CFDAE5 !important; }",
        ".footer a, #footer a { color: #7C8B9A !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: #DDE7F1 !important; color: #7C8B9A !important; }",
        ".footer .mon { color: #7C8B9A !important; }",
        "input, select, textarea { background: #FFFFFF !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        "blockquote, .quote, .blockcode { background: #DDE7F1 !important; border-color: #BAC9D9 !important; color: #5B6A7A !important; }",
        "hr, .line, .partition { border-color: #CFDAE5 !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: #CFDAE5 !important; }",
        ".btn, .button, button, .pn, .pnc { background: #CFDDEC !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        "#postform, #fastpostform, .area textarea { background: #FFFFFF !important; color: #1A2733 !important; border-color: #BAC9D9 !important; }",
        ".psth { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #CFDAE5 !important; }",
        ".psth .icon_ring { color: #5B6A7A !important; }",
        ".showcollapse_box { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".showcollapse_title { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #CFDAE5 !important; }",
        ".showcollapse_content { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".showcollapse_content a { color: #1F5A8F !important; }",
        ".showcollapse_gather { color: #7C8B9A !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: #1A2733 !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: #C04A3A !important; }",
        ".threadlist_foot { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: #7C8B9A !important; }",
        ".threadlist_foot li { border: 1px solid #BAC9D9 !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: #7C8B9A !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: #FFFFFF !important; }",
        ".threadlist_top .mmc { color: #1A2733 !important; }",
        ".threadlist_top .mtime { color: #9DACBA !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".threadlist_mes, .threadlist_mes a { background: #FFFFFF !important; color: #7C8B9A !important; }",
        ".discuz_x { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".txtlist, .txtlist .mtit { background: #DDE7F1 !important; color: #1A2733 !important; border-color: #CFDAE5 !important; }",
        ".txtlist .ytxt, .txtlist a { color: #5B6A7A !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: #7C8B9A !important; }",
        ".y, .y span, .y em, .y i { color: #7C8B9A !important; }",
        ".pstatus, .pstatus font { color: #7C8B9A !important; }",
        ".float-menu-item { background: rgba(255, 255, 255, 0.92) !important; color: #1A2733 !important; border: 1px solid #CFDAE5 !important; }",
        ".float-menu-item svg { fill: #1A2733 !important; color: #1A2733 !important; }",
        ".scrolltop { background: #2B7ACD !important; color: #FFFFFF !important; }",
        ".scrolltop i, .scrolltop svg { color: #FFFFFF !important; fill: #FFFFFF !important; }",
        "#mask { background: rgba(0,0,0,0.32) !important; }",
        "strong, b, .strong { color: #1A2733 !important; }",
        "sup, sub { color: #5B6A7A !important; }",
        "em { color: #1A2733 !important; }",
        ".display, .pi { background: #FFFFFF !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: #1F5A8F !important; }",
        ".hui-header h1 { color: #FFFFFF !important; }",
        ".hui-header a, .hui-header i { color: #FFFFFF !important; }",
        ".hui-slide-menu { background: #FFFFFF !important; }",
        ".hui-slide-menu li { color: #1A2733 !important; }",
        ".hui-wrap { background: #EDF1F6 !important; }",
        ".hui-common-title-line { border-color: #CFDAE5 !important; }",
        ".hui-common-title-txt { color: #1A2733 !important; }",
        ".hui-content { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".hui-media-list li { background: #FFFFFF !important; border-color: #CFDAE5 !important; }",
        ".hui-media-content { background: #FFFFFF !important; }",
        ".hui-media-content p { color: #1A2733 !important; }",
        ".hui-media-content a { color: #1F5A8F !important; }",
        ".hui-list { background: #FFFFFF !important; }",
        ".hui-list-text { color: #1A2733 !important; }",
        ".hui-center-title h2 { color: #1A2733 !important; }",
        ".hui-button { background: #CFDDEC !important; color: #1A2733 !important; }",
        ".hui-primary { background: #2B7ACD !important; color: #FFFFFF !important; }",
        ".fl-table { background: #FFFFFF !important; }",
        ".fl-table th { background: #DDE7F1 !important; color: #1A2733 !important; }",
        ".fl-table td { background: #FFFFFF !important; color: #1A2733 !important; }",
        ".day { color: #1A2733 !important; }",
        ".day.today { background: #2B7ACD !important; color: #FFFFFF !important; }",
        ".lunar { color: #7C8B9A !important; }",
        ".signbtn .btna { background: #2B7ACD !important; color: #FFFFFF !important; }",
        ".authi .mtit, .authi .mtime { color: #7C8B9A !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

    // 7. 苔色 (SAGE_GARDEN) — 自然森林：深苔绿 header + 浅绿底 + 近白卡 + 森林绿主色
    private val LIGHT_MODE_CSS_RULES_SAGE_GARDEN = listOf(
        "/* === CSS 变量覆盖 (苔色日间版 · 自然森林) === */",
        "body {",
        "--dz-BG-body: #E9F0EA !important;",
        "--dz-BG-0: #FAFCF9 !important;",
        "--dz-BG-5: #D3E0D5 !important;",
        "--dz-BG-6: #B5C4B7 !important;",
        "--dz-FC-fff: #1F2B22 !important;",
        "--dz-FC-333: #1F2B22 !important;",
        "--dz-FC-666: #5F6E5E !important;",
        "--dz-FC-999: #78897C !important;",
        "--dz-FC-ccc: #B1BFB4 !important;",
        "--dz-FC-aaa: #9AA99E !important;",
        "--dz-BOR-ed: #C5D2C2 !important;",
        "--dz-FC-color: #4F7857 !important;",
        "}",
        "html, body { background: #E9F0EA !important; }",
        ".wp, #wp, .content, .main, .wrapper { background: #E9F0EA !important; }",
        ".header { background: #37553F !important; border-color: #37553F !important; }",
        ".header, .header h2, .header h2 a, .header a { color: #FFFFFF !important; }",
        ".header i { color: #FFFFFF !important; }",
        ".header_toplogo { background: #37553F !important; }",
        ".header_toplogo p { color: #FFFFFF !important; }",
        ".header .myss a { background: #FAFCF9 !important; color: #9AA99E !important; }",
        ".header .myss a i { color: #9AA99E !important; }",
        "#nav-more-menu { background: #FAFCF9 !important; }",
        ".nav-more-item { color: #1F2B22 !important; }",
        ".nav-more-item-text { color: #1F2B22 !important; }",
        ".nav-more-item svg { fill: #1F2B22 !important; color: #1F2B22 !important; }",
        ".sq_nav, .thread_nav, .sq_nav ul, .thread_nav ul { background: #D3E0D5 !important; }",
        ".sq_nav a, .thread_nav a { color: #5F6E5E !important; }",
        ".sq_nav .a a, .thread_nav .a a, .sq_nav a.active, .thread_nav a.active { color: #101A14 !important; background: #C5D6C8 !important; }",
        ".z, .vertical_tab { background: #D3E0D5 !important; }",
        ".z a, .vertical_tab a { color: #5F6E5E !important; }",
        ".z .a, .vertical_tab .a, .z a.active, .vertical_tab a.active { color: #101A14 !important; background: #C5D6C8 !important; }",
        ".plc .z, .plc .flex-2, .plc .xg1, .plc .xw1 { background: #FAFCF9 !important; }",
        ".forumdisplay-top { background: #FAFCF9 !important; }",
        ".forumdisplay-top h2, .forumdisplay-top h2 a { color: #1F2B22 !important; }",
        ".forumdisplay-top p, .forumdisplay-top p span { color: #5F6E5E !important; }",
        ".dhnav_box, #dhnav, #dhnav_li { background: #D3E0D5 !important; }",
        ".dhnav_box a, #dhnav a { color: #5F6E5E !important; }",
        ".dhnav_box .mon a, #dhnav .mon a { color: #101A14 !important; }",
        ".tabs a.mon, .dhnv a.mon, #dhnav_li li.mon { border-bottom-color: #4F7857 !important; color: #37553F !important; }",
        ".dhnavs_box, #dhnavs { background: #D3E0D5 !important; }",
        "#dhnavs .swiper-slide a { color: #5F6E5E !important; }",
        "#dhnavs .swiper-slide.mon a { color: #101A14 !important; }",
        ".forumlist, .forumlist > div, .forumlist .mlist1 { background: #E9F0EA !important; }",
        ".forumlist .subforumshow { background: #D3E0D5 !important; }",
        ".subforumshow { background: #D3E0D5 !important; border-color: #C5D2C2 !important; }",
        ".subforumshow h2 a { color: #1F2B22 !important; }",
        ".subforumshow i { color: #5F6E5E !important; }",
        ".subforumshow { color: #5F6E5E !important; }",
        ".murl .mtit, .sub-forum .murl .mtit { color: #1F2B22 !important; }",
        ".mlist1, .mlist1 ul, .mlist1 li, .mlist1 li a, .mlist1 li span { background: #FAFCF9 !important; }",
        ".mlist1 li { border-color: #C5D2C2 !important; }",
        ".mlist1 .mtit { color: #1F2B22 !important; }",
        ".mlist1 .mtxt { color: #78897C !important; }",
        ".mlist1 .mnum { color: #9AA99E !important; }",
        ".bm, .bm_c, .bm_h { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".bm_h, .bm_h h2, .bm_h a { color: #1F2B22 !important; }",
        ".tl_bm, .tl_bm ul, .tl_bm li, .tl_bm li a { background: #FAFCF9 !important; }",
        ".threadlist { background: #D3E0D5 !important; }",
        ".threadlist li, .threadlist li a { background: #FAFCF9 !important; }",
        ".tl_bm li, .threadlist li { border-color: #C5D2C2 !important; }",
        ".tl_bm a, .threadlist a { color: #1F2B22 !important; }",
        ".view_tit, .view_tit h1, .view_tit a, .view_tit em { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".pls, .pls div, .pls a { background: #D3E0D5 !important; }",
        ".pls, .pls a, .pls em, .pls span { color: #1F2B22 !important; }",
        ".plc, .plm, .plc div, .plm div { background: #FAFCF9 !important; }",
        ".authi, .authi em, .authi a, .authi span { color: #5F6E5E !important; }",
        ".message, .postmessage, .t_f, .t_msgfont, .message div, .t_f div { color: #1F2B22 !important; }",
        ".message a, .postmessage a, .t_f a, .t_msgfont a { color: #37553F !important; }",
        ".pgs, .pgs a, .pg, .pg a { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        ".page { background: #E9F0EA !important; }",
        ".page a { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        ".pgs .pg strong, .pg strong, .page strong { background: #4F7857 !important; color: #FFFFFF !important; }",
        ".xi1 { color: #1F2B22 !important; }",
        ".xi2 { color: #1F2B22 !important; }",
        ".xg1, .xg1 a { color: #5F6E5E !important; }",
        ".xg2, .xg2 a { color: #78897C !important; }",
        ".num, .views, .replies { color: #78897C !important; }",
        ".ts, .time { color: #9AA99E !important; }",
        ".pipe { color: #B1BFB4 !important; }",
        ".lock, .closed, .icn, .attach, .tattl { color: #78897C !important; }",
        ".dialog, .ui-dialog, .bootstrap-dialog, .pop, .p_pop, .p_pop div { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".dialog a, .ui-dialog a, .p_pop a { color: #1F2B22 !important; }",
        ".foot_reply, .f_c, .foot, #f_c { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".foot_reply a, .f_c a, .foot a { color: #1F2B22 !important; }",
        ".viewt-reply, .viewt-reply a { background: #C5D6C8 !important; color: #101A14 !important; }",
        ".fico-launch, .dm-star, .fico-reply, .fico-favorite, .fico-share, .fico { color: #1F2B22 !important; }",
        ".my, .my a { color: #FFFFFF !important; }",
        ".my i, .my span { color: #FFFFFF !important; }",
        ".mz, .mz a, .mz i, .mz span { color: #FFFFFF !important; }",
        ".myinfo_list_ico, .myinfo_list_ico ul { background: #FAFCF9 !important; }",
        ".myinfo_list_ico li { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".myinfo_list_ico li a { background: #D3E0D5 !important; color: #1F2B22 !important; }",
        ".myinfo_list_ico li i { color: #4F7857 !important; }",
        ".myinfo, .myinfo_menu, .profile_section, .profile_section a { background: #FAFCF9 !important; }",
        ".myinfo a, .myinfo_menu a { color: #1F2B22 !important; }",
        ".myinfo_list, .myinfo_list li { border-color: #C5D2C2 !important; }",
        ".myinfo_list li span { color: #5F6E5E !important; }",
        ".myinfo_list b { color: #1F2B22 !important; }",
        ".mtag, .profile_tag { color: #78897C !important; }",
        "table, tbody, td, th, .t_table, .t_table td, .t_table th { background: #FAFCF9 !important; color: #1F2B22 !important; border-color: #C5D2C2 !important; }",
        ".footer, #footer { background: #D3E0D5 !important; color: #78897C !important; border-color: #C5D2C2 !important; }",
        ".footer a, #footer a { color: #78897C !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: #D3E0D5 !important; color: #78897C !important; }",
        ".footer .mon { color: #78897C !important; }",
        "input, select, textarea { background: #FAFCF9 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        "blockquote, .quote, .blockcode { background: #D3E0D5 !important; border-color: #B5C4B7 !important; color: #5F6E5E !important; }",
        "hr, .line, .partition { border-color: #C5D2C2 !important; }",
        ".notice, .tip, .alert, .warning, .tips { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        ".avatar img, .avatar, .my_avatar img { border-color: #C5D2C2 !important; }",
        ".btn, .button, button, .pn, .pnc { background: #C5D6C8 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        "#postform, #fastpostform, .area textarea { background: #FAFCF9 !important; color: #1F2B22 !important; border-color: #B5C4B7 !important; }",
        ".psth { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #C5D2C2 !important; }",
        ".psth .icon_ring { color: #5F6E5E !important; }",
        ".showcollapse_box { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".showcollapse_title { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #C5D2C2 !important; }",
        ".showcollapse_content { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".showcollapse_content a { color: #37553F !important; }",
        ".showcollapse_gather { color: #78897C !important; }",
        ".message *[style*=\"background\" i] { background-color: transparent !important; }",
        "font[color] { color: #1F2B22 !important; }",
        "font[color=\"#ff0000\" i], font[color=\"red\" i] { color: #BA4A3D !important; }",
        ".threadlist_foot { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".threadlist_foot a, .threadlist_foot i, .threadlist_foot em { color: #78897C !important; }",
        ".threadlist_foot li { border: 1px solid #B5C4B7 !important; outline: none !important; box-shadow: none !important; }",
        ".threadlist_foot li.mr { border: none !important; }",
        ".threadlist_foot .dm-heart, .threadlist_foot .dm-chat-s, .dm-heart, .dm-chat-s, .dm-eye-fill, .dm-chat-s-fill { color: #78897C !important; }",
        ".threadlist_top, .threadlist_top a, .threadlist_top .mimg, .threadlist_top .muser { background: #FAFCF9 !important; }",
        ".threadlist_top .mmc { color: #1F2B22 !important; }",
        ".threadlist_top .mtime { color: #9AA99E !important; }",
        ".threadlist_tit, .threadlist_tit em, .threadlist_tit a { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".threadlist_mes, .threadlist_mes a { background: #FAFCF9 !important; color: #78897C !important; }",
        ".discuz_x { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".txtlist, .txtlist .mtit { background: #D3E0D5 !important; color: #1F2B22 !important; border-color: #C5D2C2 !important; }",
        ".txtlist .ytxt, .txtlist a { color: #5F6E5E !important; }",
        ".mtime, .mtime span, .mtime em, .mtime i { color: #78897C !important; }",
        ".y, .y span, .y em, .y i { color: #78897C !important; }",
        ".pstatus, .pstatus font { color: #78897C !important; }",
        ".float-menu-item { background: rgba(255, 255, 255, 0.92) !important; color: #1F2B22 !important; border: 1px solid #C5D2C2 !important; }",
        ".float-menu-item svg { fill: #1F2B22 !important; color: #1F2B22 !important; }",
        ".scrolltop { background: #4F7857 !important; color: #FFFFFF !important; }",
        ".scrolltop i, .scrolltop svg { color: #FFFFFF !important; fill: #FFFFFF !important; }",
        "#mask { background: rgba(0,0,0,0.32) !important; }",
        "strong, b, .strong { color: #1F2B22 !important; }",
        "sup, sub { color: #5F6E5E !important; }",
        "em { color: #1F2B22 !important; }",
        ".display, .pi { background: #FAFCF9 !important; }",
        ".plc .avatar { z-index: 10 !important; background: transparent !important; }",
        ".plc .display, .plc .pi { background: transparent !important; }",
        ".hui-header { background: #37553F !important; }",
        ".hui-header h1 { color: #FFFFFF !important; }",
        ".hui-header a, .hui-header i { color: #FFFFFF !important; }",
        ".hui-slide-menu { background: #FAFCF9 !important; }",
        ".hui-slide-menu li { color: #1F2B22 !important; }",
        ".hui-wrap { background: #E9F0EA !important; }",
        ".hui-common-title-line { border-color: #C5D2C2 !important; }",
        ".hui-common-title-txt { color: #1F2B22 !important; }",
        ".hui-content { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".hui-media-list li { background: #FAFCF9 !important; border-color: #C5D2C2 !important; }",
        ".hui-media-content { background: #FAFCF9 !important; }",
        ".hui-media-content p { color: #1F2B22 !important; }",
        ".hui-media-content a { color: #37553F !important; }",
        ".hui-list { background: #FAFCF9 !important; }",
        ".hui-list-text { color: #1F2B22 !important; }",
        ".hui-center-title h2 { color: #1F2B22 !important; }",
        ".hui-button { background: #C5D6C8 !important; color: #1F2B22 !important; }",
        ".hui-primary { background: #4F7857 !important; color: #FFFFFF !important; }",
        ".fl-table { background: #FAFCF9 !important; }",
        ".fl-table th { background: #D3E0D5 !important; color: #1F2B22 !important; }",
        ".fl-table td { background: #FAFCF9 !important; color: #1F2B22 !important; }",
        ".day { color: #1F2B22 !important; }",
        ".day.today { background: #4F7857 !important; color: #FFFFFF !important; }",
        ".lunar { color: #78897C !important; }",
        ".signbtn .btna { background: #4F7857 !important; color: #FFFFFF !important; }",
        ".authi .mtit, .authi .mtime { color: #78897C !important; }",
        ".pswp__button, .pswp__button:hover, .pswp__button:active { background: transparent !important; border-color: transparent !important; box-shadow: none !important; }"
    )

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

    fun getLightModeSetJs(enable: Boolean, themeId: Int = 0): String {
        val rulesList = when (themeId) {
            2 -> LIGHT_MODE_CSS_RULES_COBALT_FORUM
            3 -> LIGHT_MODE_CSS_RULES_SAGE_GARDEN
            else -> LIGHT_MODE_CSS_RULES_SEPIA_PAPER
        }
        val styleString = rulesList.joinToString(",\n") { "                '$it'" }

        return """
            (function() {
                var styleId = 'yamibo-light-mode';
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

    fun injectLightModeCssIntoHtml(html: String, themeId: Int = 0): String {
        val rulesList = when (themeId) {
            2 -> LIGHT_MODE_CSS_RULES_COBALT_FORUM
            3 -> LIGHT_MODE_CSS_RULES_SAGE_GARDEN
            else -> LIGHT_MODE_CSS_RULES_SEPIA_PAPER
        }
        val css = rulesList.joinToString("\n")
        val styleTag = "<style id=\"yamibo-light-mode\">\n$css\n</style>"
        return when {
            html.contains("</head>") -> html.replace("</head>", "$styleTag</head>")
            html.contains("<head>") -> html.replace("<head>", "<head>$styleTag")
            html.contains("<html>") -> html.replace("<html>", "<html><head>$styleTag</head>")
            html.contains("<body") -> html.replace("<body", "$styleTag<body")
            else -> "$styleTag$html"
        }
    }

    /** 根据当前暗色/亮色模式状态移除旧样式并注入对应 CSS */
    fun getThemeSetJs(isDark: Boolean, darkThemeId: Int, lightThemeId: Int): String {
        val darkJs = getDarkModeSetJs(isDark, darkThemeId)
        val lightJs = getLightModeSetJs(!isDark && lightThemeId > 0, lightThemeId)
        return "$darkJs\n$lightJs"
    }

    /** 将当前主题 CSS 注入 HTML（先注入暗色后注入亮色，只会命中其一） */
    fun injectThemeCssIntoHtml(html: String, isDark: Boolean, darkThemeId: Int, lightThemeId: Int): String {
        var result = html
        if (isDark) {
            result = injectDarkModeCssIntoHtml(result, darkThemeId)
        } else if (lightThemeId > 0) {
            result = injectLightModeCssIntoHtml(result, lightThemeId)
        }
        return result
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