define(['baseView', 'loading', 'emby-input', 'emby-button', 'emby-scroller'], function (BaseView, loading) {
    'use strict';

    var pluginId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

    function normalizeUrl(u) {
        return (u || 'http://localhost:8765').replace(/\/+$/, '');
    }

    function token() {
        try { return ApiClient.accessToken(); } catch (e) { return ''; }
    }

    function refreshStatus(view, serviceUrl) {
        var url = normalizeUrl(serviceUrl);
        var el = view.querySelector('.sonicStatus');
        el.innerHTML = 'Contacting ' + url + '/sonic/status ...';
        fetch(url + '/sonic/status', { headers: { 'X-Emby-Token': token() } })
            .then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.json();
            })
            .then(function (d) {
                var pct = d.scan_progress != null ? (d.scan_progress * 100).toFixed(1) + '%' : 'N/A';
                el.innerHTML =
                    '<b style="color:#2ecc71">Service: online</b><br>' +
                    'Tracks: ' + d.analysed_tracks + ' / ' + d.total_tracks + ' analysed (' + pct + ')<br>' +
                    'Pending: ' + d.pending_tracks +
                    (d.scan_running ? ' &nbsp; <em>Scan running...</em>' : '');
            })
            .catch(function (err) {
                el.innerHTML =
                    '<b style="color:#e74c3c">Service: offline</b><br>' +
                    'Could not reach ' + url + '/sonic/status<br>' +
                    '<small>' + (err && err.message ? err.message : err) + '</small>';
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
        ApiClient.getPluginConfiguration(pluginId).then(function (config) {
            config.ServiceUrl = serviceUrl;
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
            refreshStatus(view, config.ServiceUrl);
            loading.hide();
        });
    };

    return View;
});
