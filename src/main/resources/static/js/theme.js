/*
 * Applies the colour theme before first paint (loaded synchronously in <head>) to avoid a flash
 * of the wrong theme. Order: the user's saved choice, then the operating-system preference.
 */
(function () {
    var theme = 'light';
    try {
        var saved = localStorage.getItem('railtime.theme');
        if (saved === 'dark' || saved === 'light') {
            theme = saved;
        } else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            theme = 'dark';
        }
    } catch (e) {
        /* storage unavailable: keep light */
    }
    document.documentElement.setAttribute('data-theme', theme);
}());
