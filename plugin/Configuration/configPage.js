define(['baseView', 'loading', 'emby-input', 'emby-button', 'emby-scroller'], function (BaseView, loading) {
    'use strict';

    var pluginId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

    function normalizeUrl(u) {
        return (u || 'http://localhost:8765').replace(/\/+$/, '');
    }

    function token() {
        try { return ApiClient.accessToken(); } catch (e) { return ''; }
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    }

    function fmt(n) {
        return (n == null ? 0 : n).toLocaleString();
    }

    function refreshStatus(view, serviceUrl) {
        var url = normalizeUrl(serviceUrl);
        var el = view.querySelector('.sonicStatus');
        var skip = view.querySelector('.sonicSkipped');
        skip.style.display = 'none';
        skip.innerHTML = '';
        el.innerHTML = 'Contacting ' + esc(url) + '/sonic/status ...';
        fetch(url + '/sonic/status', { headers: { 'X-Emby-Token': token() } })
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.json();
            })
            .then(function (d) {
                var pct = d.scan_progress != null ? (d.scan_progress * 100).toFixed(1) + '%' : 'N/A';
                var failed = d.failed_tracks || 0;
                var pending = d.pending_tracks || 0;
                var html =
                    '<b style="color:#2ecc71">Service: online</b><br>' +
                    'Analysed: ' + fmt(d.analysed_tracks) + ' / ' + fmt(d.total_tracks) +
                    ' (' + pct + ' of analysable)<br>';
                if (pending > 0) {
                    html += 'Pending: ' + fmt(pending) +
                        (d.scan_running ? ' &nbsp; <em>Scan running...</em>' : '');
                } else {
                    html += '<b style="color:#2ecc71">✓ Analysis complete</b>';
                }
                if (failed > 0) {
                    html += '<br><span style="color:#e0a030">' + fmt(failed) + ' skipped</span> ' +
                        '<small>(couldn’t be read — broken or missing files)</small> ' +
                        '<a href="#" class="lnkShowSkipped" style="margin-left:0.4em;">Show details</a>';
                }
                el.innerHTML = html;
                var lnk = el.querySelector('.lnkShowSkipped');
                if (lnk) {
                    lnk.addEventListener('click', function (e) {
                        e.preventDefault();
                        toggleSkipped(view, url, lnk);
                    });
                }
            })
            .catch(function (err) {
                skip.style.display = 'none';
                el.innerHTML =
                    '<b style="color:#e74c3c">Service: offline</b><br>' +
                    'Could not reach ' + esc(url) + '/sonic/status<br>' +
                    '<small>' + esc(err && err.message ? err.message : err) + '</small>';
            });
    }

    function toggleSkipped(view, url, lnk) {
        var skip = view.querySelector('.sonicSkipped');
        if (skip.style.display !== 'none') {
            skip.style.display = 'none';
            if (lnk) { lnk.textContent = 'Show details'; }
            return;
        }
        skip.style.display = 'block';
        if (lnk) { lnk.textContent = 'Hide details'; }
        skip.innerHTML = 'Loading skipped tracks...';
        fetch(url + '/sonic/status/errors', { headers: { 'X-Emby-Token': token() } })
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.json();
            })
            .then(function (list) {
                if (!list || !list.length) { skip.innerHTML = 'No skipped tracks.'; return; }
                var rows = list.map(function (t) {
                    var who = [t.artist, t.title].filter(Boolean).join(' — ') || t.id;
                    return '<div style="margin-bottom:0.5em;">' +
                        '<div>' + esc(who) + '</div>' +
                        '<small style="color:#888;">' + esc(t.error || 'unknown error') + '</small>' +
                        '</div>';
                }).join('');
                skip.innerHTML =
                    '<b>' + list.length + ' skipped track' + (list.length === 1 ? '' : 's') + '</b>' +
                    '<div style="margin-top:0.6em;">' + rows + '</div>';
            })
            .catch(function (err) {
                skip.innerHTML = '<small style="color:#e74c3c">Could not load skipped tracks: ' +
                    esc(err && err.message ? err.message : err) + '</small>';
            });
    }

    function postAction(view, path, okMsg) {
        var url = normalizeUrl(view.querySelector('.txtServiceUrl').value);
        fetch(url + path, {
            method: 'POST',
            headers: { 'X-Emby-Token': token(), 'Content-Type': 'application/json' },
            body: '{}'
        }).then(function () { Dashboard.alert(okMsg); })
          .catch(function () { Dashboard.alert('Request failed — is the service reachable?'); });
    }

    function save(view) {
        loading.show();
        var serviceUrl = view.querySelector('.txtServiceUrl').value.trim();
        var apiKey = view.querySelector('.txtApiKey').value.trim();
        ApiClient.getPluginConfiguration(pluginId).then(function (config) {
            config.ServiceUrl = serviceUrl;
            config.ApiKey = apiKey;
            ApiClient.updatePluginConfiguration(pluginId, config).then(function (result) {
                Dashboard.processPluginConfigurationUpdateResult(result);
                refreshStatus(view, serviceUrl);
            });
        });
    }

    function View(view, params) {
        BaseView.apply(this, arguments);

        view.querySelector('form').addEventListener('submit', function (e) {
            e.preventDefault();
            e.stopPropagation();
            save(view);
            return false;
        });

        view.querySelector('.btnScan').addEventListener('click', function () {
            postAction(view, '/sonic/library/scan', 'Library scan started.');
        });

        view.querySelector('.btnBuildMixes').addEventListener('click', function () {
            postAction(view, '/sonic/library/build-mixes', 'Mix generation started.');
        });
    }

    Object.assign(View.prototype, BaseView.prototype);

    View.prototype.onResume = function (options) {
        BaseView.prototype.onResume.apply(this, arguments);
        var view = this.view;
        ApiClient.getPluginConfiguration(pluginId).then(function (config) {
            view.querySelector('.txtServiceUrl').value = config.ServiceUrl || '';
            view.querySelector('.txtApiKey').value = config.ApiKey || '';
            refreshStatus(view, config.ServiceUrl);
            loading.hide();
        });
    };

    return View;
});
