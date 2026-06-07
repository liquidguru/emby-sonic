using System;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Plugins;
using MediaBrowser.Model.Events;
using Microsoft.Extensions.Logging;

namespace EmbysonicPlugin;

public class ServerEntryPoint : IServerEntryPoint
{
    private readonly ILibraryManager _libraryManager;
    private readonly ILogger<ServerEntryPoint> _logger;

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
        // Only trigger on audio items
        if (e.Item is not MediaBrowser.Controller.Entities.Audio.Audio)
            return;

        _ = Task.Run(async () =>
        {
            try
            {
                var url = Plugin.Instance?.Configuration.ServiceUrl ?? "http://localhost:8765";
                using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(10) };
                await http.PostAsync($"{url}/sonic/library/scan", null);
                _logger.LogInformation("Emby Sonic: triggered incremental scan after library change");
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Emby Sonic: failed to trigger incremental scan");
            }
        });
    }

    public void Dispose()
    {
        _libraryManager.ItemAdded -= OnItemAdded;
    }
}
