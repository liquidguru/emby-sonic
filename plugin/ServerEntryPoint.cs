using System;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Plugins;
using MediaBrowser.Model.Events;

namespace EmbysonicPlugin;

/// <summary>
/// Subscribes to library changes and triggers an incremental scan on the
/// coordinator when audio is added.
///
/// NOTE: the constructor must only depend on services Emby's SimpleInjector
/// container actually registers. <c>ILogger&lt;T&gt;</c> is NOT registered (it
/// threw ActivationException and the entry point never instantiated, so the
/// ItemAdded hook never ran) — so this class takes only <see cref="ILibraryManager"/>.
/// </summary>
public class ServerEntryPoint : IServerEntryPoint
{
    // Reused across events; a new HttpClient per ItemAdded would leak sockets
    // during a bulk import.
    private static readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };

    // Coalesce a burst of ItemAdded events (e.g. importing thousands of tracks)
    // into a single scan trigger.
    private static readonly TimeSpan DebounceWindow = TimeSpan.FromSeconds(10);

    private readonly ILibraryManager _libraryManager;
    private readonly object _lock = new();
    private CancellationTokenSource? _debounceCts;

    public ServerEntryPoint(ILibraryManager libraryManager)
    {
        _libraryManager = libraryManager;
    }

    public void Run()
    {
        _libraryManager.ItemAdded += OnItemAdded;
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

        // No API key configured → automatic scan is disabled (manual scan still works).
        if (string.IsNullOrWhiteSpace(apiKey))
            return;

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, $"{url}/sonic/library/scan");
            request.Headers.TryAddWithoutValidation("X-Emby-Token", apiKey);
            request.Content = new StringContent("{\"full\":false}", Encoding.UTF8, "application/json");
            using var response = await _http.SendAsync(request);
        }
        catch
        {
            // Coordinator unreachable / transient — the next import (or a manual
            // scan) will retry. Swallowed to avoid disrupting Emby's import path.
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
