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
    
            function isBadRawHref(rawHref) {
                if (!rawHref) return true;
    
                rawHref = String(rawHref).trim();
                if (!rawHref) return true;
    
                if (/^javascript:/i.test(rawHref)) return true;
                if (/^(mailto|tel|sms):/i.test(rawHref)) return true;
                if (rawHref === '#' || rawHref.indexOf('#') === 0) return true;
    
                return false;
            }
    
            function isSafeBbsNavigation(a, url) {
                if (!a || !url) return false;
    
                var rawHref = a.getAttribute('href') || '';
                if (isBadRawHref(rawHref)) return false;
    
                var protocol = String(url.protocol || '').toLowerCase();
                if (protocol !== 'http:' && protocol !== 'https:') return false;
    
                // 只兜底百合会论坛内部链接，避免误伤外链。
                if (url.hostname !== 'bbs.yamibo.com') return false;
    
                var target = String(a.getAttribute('target') || '').toLowerCase();
                if (target && target !== '_self') return false;
    
                if (a.hasAttribute('download')) return false;
    
                // PhotoSwipe / 图片预览不要兜底。
                if (a.hasAttribute('data-pswp-width')) return false;
                if (closest(a, '.pswp')) return false;
    
                var hasImg = !!a.querySelector('img');
    
                // 带图片的链接很可能是头像、表情、预览图、附件图。
                // 只有明确是帖子/版块导航时才允许兜底。
                if (hasImg && !/mod=viewthread|thread-\d+|forum-\d+/i.test(url.href)) {
                    return false;
                }
    
                var path = String(url.pathname || '').replace(/^\/+/, '');
                var query = String(url.search || '');
    
                // 排除 ajax / 弹层 / 表单动作 / 敏感动作。
                if (/(\?|&)inajax=1\b/i.test(query)) return false;
                if (/(\?|&)handlekey=/i.test(query)) return false;
                if (/(\?|&)formhash=/i.test(query)) return false;
    
                if (/(\?|&)action=(reply|edit|newthread|delete|recommend|rate|favorite|logout)/i.test(query)) {
                    return false;
                }
    
                if (/(\?|&)mod=(post|logging|register|ajax|misc)/i.test(query)) {
                    return false;
                }
    
                // 纯楼层锚点变化不处理。
                var currentNoHash = location.href.split('#')[0];
                var targetNoHash = url.href.split('#')[0];
                if (currentNoHash === targetNoHash) return false;
    
                // Discuz 常见普通导航白名单。
                if (/^forum\.php/i.test(path)) return true;
                if (/^home\.php/i.test(path)) return true;
                if (/^search\.php/i.test(path)) return true;
                if (/^portal\.php/i.test(path)) return true;
    
                if (/^thread-\d+/i.test(path)) return true;
                if (/^forum-\d+/i.test(path)) return true;
                if (/^space-\d+/i.test(path)) return true;
    
                return false;
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
    
                    // 正常跳转、PJAX pushState、hash 变化都不再兜底。
                    if (location.href !== before) return;
    
                    // 页面没动，认为点击被论坛脚本/PJAX 吃掉了，执行硬跳转。
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
                if (!a) return;
    
                scheduleFallback(a, 'click');
            }, true);
    
            document.addEventListener('pointerdown', function(e) {
                var p = getPoint(e);
                if (!p) return;
    
                downPoint = {
                    x: p.clientX,
                    y: p.clientY,
                    t: Date.now()
                };
            }, true);
    
            document.addEventListener('pointerup', function(e) {
                if (!downPoint) return;
    
                var p = getPoint(e);
                if (!p) return;
    
                var dx = Math.abs(p.clientX - downPoint.x);
                var dy = Math.abs(p.clientY - downPoint.y);
                var dt = Date.now() - downPoint.t;
    
                downPoint = null;
    
                // 排除滚动和长按。
                if (dx > 16 || dy > 16 || dt > 1000) return;
    
                var el = document.elementFromPoint(p.clientX, p.clientY) || e.target;
                var a = closest(el, 'a[href]');
                if (!a) return;
    
                scheduleFallback(a, 'pointerup');
            }, true);
    
            // 部分老 WebView / 页面脚本可能 pointer 事件不稳定，再补 touch。
            document.addEventListener('touchstart', function(e) {
                var p = getPoint(e);
                if (!p) return;
    
                downPoint = {
                    x: p.clientX,
                    y: p.clientY,
                    t: Date.now()
                };
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
                if (!a) return;
    
                scheduleFallback(a, 'touchend');
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
            style.innerHTML = '.my, .mz { visibility: hidden !important; pointer-events: none !important; }';
            document.head.appendChild(style);
        })()
    """.trimIndent()

    val MANGA_WEB_IS_MANGA_SECTION_JS = """
        (function(){
            var a = document.querySelector('.header h2 a');
            if (!a) return false;
            var t = a.innerText;
            return t.indexOf('中文百合漫画区') !== -1 || t.indexOf('貼圖區') !== -1 || t.indexOf('原创图作区') !== -1 || t.indexOf('百合漫画图源区') !== -1;
        })()
    """.trimIndent()

    val MANGA_WEB_INJECT_CLICK_LISTENER_JS = """
        javascript:(function() {
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
        })();
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
            style.innerHTML = '.nav-search, #nav-more-menu .btn-to-pc { display: none !important; }';
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
            var novelSections = ['文学区', 'TXT小说区', '轻小说/译文区'];
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


    // ProbingPage脚本

    val PROBING_CHECK_JS = """
        (function() {
            var currentUrl = window.location.href;
            if (!currentUrl || currentUrl === 'about:blank') return 'WAIT';
            
            if (!document.body || document.body.children.length === 0) return 'WAIT';
            
            var hasForumStructure = document.querySelector('.header, #wp, .view_tit, .message');
            if (!hasForumStructure) return 'WAIT';
            
            var sectionHeader = document.querySelector('.header h2 a');
            var sectionName = sectionHeader ? sectionHeader.innerText.trim() : '';
            
            var mangaSections = ['中文百合漫画区', '贴图区', '貼圖區', '原创图作区', '百合漫画图源区'];
            var isManga = mangaSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=30') !== -1;
            
            var novelSections = ['文學區', '文学区', '轻小说/译文区', 'TXT小说区'];
            var isNovel = novelSections.some(function(s) { return sectionName.indexOf(s) !== -1; }) || currentUrl.indexOf('fid=55') !== -1;
            
            // 如果明确是【小说区】
            if (isNovel) {
                var onlyOpBtn = document.querySelector('.nav-more-item');
                var authorId = "";
                var encodedTitle = encodeURIComponent(document.title || '');
                if (onlyOpBtn && onlyOpBtn.href) {
                    var match = onlyOpBtn.href.match(/authorid=(\d+)/);
                    if (match) authorId = match[1];
                    return "1:::" + authorId + ":::" + encodedTitle;
                }
                if (document.querySelector('.message') || document.readyState === 'complete') {
                    return "1::::::" + encodedTitle; 
                }
                return 'WAIT';
            }

            // 如果明确是【漫画区】
            if (isManga) {
                var allImgs = document.querySelectorAll('.img_one img, .message img:not([src*="smiley"])');
                if (allImgs.length > 0) {
                    var urls = [];
                    for (var i = 0; i < allImgs.length; i++) {
                        var rawSrc = allImgs[i].getAttribute('zsrc') || allImgs[i].getAttribute('src');
                        if (rawSrc) urls.push(new URL(rawSrc, document.baseURI).href);
                    }
                    var encodedTitle = encodeURIComponent(document.title || '');
                    var encodedHtml = encodeURIComponent(document.documentElement.outerHTML);
                    return "2:::" + encodedTitle + ":::" + urls.join('|||') + ":::" + encodedHtml;
                }
                if (document.readyState === 'complete') return "3";
                return 'WAIT';
            }

            // 既不是小说也不是漫画
            if (sectionName !== "") {
                return "3";
            }

            if (document.readyState !== 'complete') {
                return 'WAIT';
            }

            return "3";
        })();
    """.trimIndent()
    val FREEZE_BROKEN_IMAGES_JS = """
        (function() {
            var imgs = document.querySelectorAll('img');
            for(var i=0; i<imgs.length; i++) {
                if(!imgs[i].complete || typeof imgs[i].naturalWidth === 'undefined' || imgs[i].naturalWidth === 0) {
                    imgs[i].style.opacity = '0'; 
                }
            }
            window.stop();
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
        ".header { background: oklch(20% 0.02 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".header, .header h2, .header h2 a, .header a { color: oklch(90% 0.005 285) !important; }",
        ".header_toplogo { background: oklch(20% 0.02 285) !important; }",
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
        ".footer, #footer { background: oklch(20% 0.02 285) !important; color: oklch(58% 0.01 285) !important; border-color: oklch(30% 0.02 285) !important; }",
        ".footer a, #footer a { color: oklch(58% 0.01 285) !important; }",
        ".footer-nv, .footer-nv a, .footer-copy, .footer-copy a { background: oklch(20% 0.02 285) !important; color: oklch(58% 0.01 285) !important; }",
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