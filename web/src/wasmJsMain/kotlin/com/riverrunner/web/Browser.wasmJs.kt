package com.riverrunner.web

// Each call is wrapped in try/catch: Safari in private mode, or a full storage, throws.

private fun jsStorageGet(k: String): String? = js("(function(){ try { return localStorage.getItem(k); } catch (e) { return null; } })()")
private fun jsStorageSet(k: String, v: String): Boolean = js("(function(){ try { localStorage.setItem(k, v); return true; } catch (e) { return false; } })()")
private fun jsStorageRemove(k: String): Unit = js("(function(){ try { localStorage.removeItem(k); } catch (e) {} })()")
private fun jsLanguage(): String = js("navigator.language || ''")
private fun jsPlay(u: String, v: Double, r: Double): Unit =
    js("(function(){ try { var a = new Audio(u); a.volume = v; a.playbackRate = r; var p = a.play(); if (p && p.catch) p.catch(function(){}); } catch (e) {} })()")

internal actual fun storageGet(key: String): String? = jsStorageGet(key)
internal actual fun storageSet(key: String, value: String): Boolean = jsStorageSet(key, value)
internal actual fun storageRemove(key: String) = jsStorageRemove(key)
internal actual fun browserLanguage(): String = jsLanguage()
internal actual fun playAudio(url: String, volume: Double, rate: Double) = jsPlay(url, volume, rate)

private fun jsPrompt(t: String, i: String): String? = js("(function(){ try { return window.prompt(t, i); } catch (e) { return null; } })()")
internal actual fun browserPrompt(title: String, initial: String): String? = jsPrompt(title, initial)
private fun jsPageUrl(): String = js("(function(){ try { return location.origin + location.pathname; } catch (e) { return ''; } })()")
internal actual fun browserPageUrl(): String = jsPageUrl()

private fun jsHasHook(n: String): Boolean = js("(function(){ try { return typeof window[n] === 'function'; } catch (e) { return false; } })()")
private fun jsCallHook(n: String): Unit = js("(function(){ try { window[n](); } catch (e) {} })()")
internal actual fun browserHasHook(name: String): Boolean = jsHasHook(name)
internal actual fun browserCallHook(name: String) = jsCallHook(name)
