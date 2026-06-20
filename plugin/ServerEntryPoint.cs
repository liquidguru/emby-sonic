using System;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Plugins;
using MediaBrowser.Model.Events;
using Microsoft.Extensions.Logging;

namespace EmbysonicPlugin;

public class ServerEntryPoint : IServerEntryPoint
{
    // Reused across events; a new HttpClient per ItemAdded would leak sockets
    // during a bulk import.
    private static readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };

    // Coalesce a burst of ItemAdded events (e.g. importing thousands of tracks)
    // into a single scan trigger.
    private static readonly TimeSpan DebounceWindow = TimeSpan.FromSeconds(10);

    private readonly ILibraryManager _libraryManager;
    private readonly ILogger<ServerEntryPoint> _logger;
    private readonly object _lock = new();
    private CancellationTokenSource? _debounceCts;

    public ServerEntryPoint(ILibraryManager libraryManager, ILogger<ServerEntryPoint> logger)
    {
        _libraryManager = libraryManager;
        _logger = logger;
    }

    public void Run()
    {
        _libraryManager.ItemAdded += OnItemAdded;
        _logger.LogInformation("Emby Sonic: server entry point started, watching for library changes");
    }

    private void OnItemAdded(object? sender, ItemChangeEventArgs e)
    {
        if (e.Item is not MediaBrowser.Controller.Entities.Audio.Audio)
            return;
        ScheduleScan();
    }

    /// <summary>Debounced: each new audio item resets the timer; one scan fires once the burst settles.</summary>
    private void ScheduleScan()
    {
        CancellationToken token;
        lock (_lock)
        {
            _debounceCts?.Cancel();
            _debounceCts = new CancellationTokenSource();
            token = _debounceCts.Token;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(DebounceWindow, token);
                await TriggerScanAsync();
            }
            catch (OperationCanceledException)
            {
                // Superseded by a later item in the same burst — expected.
            }
        });
    }

    private async Task TriggerScanAsync()
    {
        var config = Plugin.Instance?.Configuration;
        var url = (config?.ServiceUrl ?? "http://localhost:8765").TrimEnd('/');
        var apiKey = config?.ApiKey;

        if (string.IsNullOrWhiteSpace(apiKey))
        {
            _logger.LogWarning(
                "Emby Sonic: no API key configured — automatic incremental scan is disabled. " +
                "Set the API key in the Emby Sonic plugin settings to enable it.");
            return;
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, $"{url}/sonic/library/scan");
            request.Headers.TryAddWithoutValidation("X-Emby-Token", apiKey);
            request.Content = new StringContent("{\"full\":false}", Encoding.UTF8, "application/json");

            using var response = await _http.SendAsync(request);
            if (response.IsSuccessStatusCode)
                _logger.LogInformation("Emby Sonic: incremental scan triggered after library change");
            else if (response.StatusCode == HttpStatusCode.Conflict)
                _logger.LogInformation("Emby Sonic: a scan is already running");
            else
                _logger.LogWarning(
                    "Emby Sonic: scan trigger rejected (HTTP {Status}) — check the plugin API key and service URL",
                    (int)response.StatusCode);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Emby Sonic: failed to trigger incremental scan");
        }
    }

    public void Dispose()
    {
        _libraryManager.ItemAdded -= OnItemAdded;
        lock (_lock)
        {
            _debounceCts?.Cancel();
            _debounceCts?.Dispose();
            _debounceCts = null;
        }
    }
}
