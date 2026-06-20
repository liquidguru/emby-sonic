using MediaBrowser.Model.Plugins;

namespace EmbysonicPlugin;

public class PluginConfiguration : BasePluginConfiguration
{
    public string ServiceUrl { get; set; } = "http://localhost:8765";

    /// <summary>
    /// Emby API key used to authenticate the automatic incremental-scan trigger
    /// (the same key the coordinator/workers use). Without it, the server-side
    /// ItemAdded trigger has no user token and the coordinator rejects it (401),
    /// so new imports are never auto-analysed.
    /// </summary>
    public string ApiKey { get; set; } = "";
}
