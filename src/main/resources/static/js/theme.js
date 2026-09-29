/*
 * Applies saved display preferences before first paint (loaded synchronously in <head>), so the
 * page never flashes the wrong theme or sidebar width.
 *  - Theme: the user's saved choice, then the operating-system preference.
 *  - Sidebar: collapsed to icons if the user collapsed it last time (desktop only; see app.css).
 */
(function () {
    var root = document.documentElement;
    var theme = 'light';
    var sidebar = null;
    try {
        var saved = localStorage.getItem('railtime.theme');
        if (saved === 'dark' || saved === 'light') {
            theme = saved;
        } else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            theme = 'dark';
        }
        sidebar = localStorage.getItem('railtime.sidebar');
    } catch (e) {
        /* storage unavailable: keep defaults */
    }
    root.setAttribute('data-theme', theme);
    if (sidebar === 'collapsed') {
        root.setAttribute('data-sidebar', 'collapsed');
    }
}());
